package io.github.yinvoker.foxlet

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Fetches one [ModelCatalog.Model] into a fresh directory under a root:
 * resumable, retrying, verified, published atomically.
 *
 * Layout under the root while a download runs: `.download-<directoryName>`
 * holding each finished asset under its own name and the asset in flight as
 * `<name>.part`. The temp name is deterministic on purpose — it is what lets
 * a later call (or a later process) pick up where a cancelled or failed one
 * stopped — and it carries the model identity so a partial file of one
 * release can never be appended to by another. On success the manifest is
 * written beside the files and the whole directory is renamed into place;
 * nothing under the published name is ever modified afterwards.
 *
 * Trust is the TLS connection plus the size and SHA-256 the model record
 * carries: every asset URL must be HTTPS to a host in
 * [ModelCatalog.DownloadPolicy.allowedHosts], redirects are not followed, and
 * a resumed prefix is re-hashed so the final digest covers every byte on disk,
 * not only the bytes this attempt received. A size or digest mismatch is a data
 * problem and fails at once ([IllegalArgumentException], offending file removed);
 * transport failures ([IOException]) and HTTP 408/429/5xx are retried with
 * full-jitter backoff; any other status is a protocol failure
 * ([IllegalStateException]) that retrying would not fix.
 *
 * The CDN's fixed-length responses matter for one subtle case: when the peer
 * closes early, OkHttp (Android's `HttpURLConnection`) throws, but the JDK
 * client just returns end-of-stream. The downloader therefore compares the
 * bytes received against the declared `Content-Length` itself and raises an
 * [EOFException], so a dropped connection resumes on both runtimes instead of
 * surfacing as a checksum mismatch on one of them.
 *
 * The three seams exist for tests: [open] rewrites URLs to a local server after
 * the production HTTPS/host checks have run, [sleep] records delays instead of
 * waiting, [random] makes the jitter reproducible. The caller holds
 * [ModelCatalog]'s download mutex and is on an IO dispatcher.
 */
internal class ModelDownloader(
    private val policy: ModelCatalog.DownloadPolicy,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) {
    /** An asset with its parsed, allow-listed URL, built before any I/O so the trust check cannot be skipped. */
    private class Target(val asset: ModelCatalog.Asset, val url: URL)

    /** Where one asset sits in the whole download; builds the progress events for it. */
    private class Slot(
        val asset: ModelCatalog.Asset,
        val index: Int,
        val count: Int,
        /** Bytes of the assets before this one, all finished. */
        val before: Long,
        val total: Long,
    ) {
        fun at(done: Long, attempt: Int) = ModelCatalog.DownloadProgress(
            assetName = asset.name,
            assetIndex = index,
            assetCount = count,
            assetDownloaded = done,
            assetSize = asset.size,
            downloaded = before + done,
            total = total,
            attempt = attempt,
        )
    }

    /** How one attempt at an asset failed in a way worth retrying. */
    private sealed class Failure {
        abstract val retryAfterMillis: Long?
        abstract fun error(): Throwable

        class Status(val code: Int, override val retryAfterMillis: Long?) : Failure() {
            override fun error() = IllegalStateException("Model download returned HTTP $code")
        }

        class Transport(val cause: IOException) : Failure() {
            override val retryAfterMillis: Long? get() = null
            override fun error() = cause
        }
    }

    /**
     * Download [model] under [root] and return its files with hashes pinned.
     *
     * An already published directory that still verifies is returned without
     * any request, after one progress event that reports the last asset and
     * the whole model as complete (`downloaded == total == model.sizeBytes`),
     * so a host that drives a bar off the events ends in the same state as
     * after a real download. A published directory that fails verification is
     * left alone and the model is downloaded beside it.
     *
     * Failure and cancellation with [ModelCatalog.DownloadPolicy.resume] keep
     * the temp directory — finished assets and the partial file — for the next
     * call; without it the temp directory is removed, as before resume existed.
     * Other `.download-*` directories are never touched; that is cleanup's job.
     */
    suspend fun download(
        root: File,
        model: ModelCatalog.Model,
        onProgress: (ModelCatalog.DownloadProgress) -> Unit,
    ): ModelFiles {
        val targets = validate(root, model)
        val hashes = model.assets.associate { it.name to it.sha256 }
        val destination = File(root, model.directoryName)
        val candidates = sequenceOf(destination) + root.listFiles().orEmpty().asSequence()
            .filter { it.name.startsWith(model.directoryName + "-") && ModelStore.parseName(it.name, model)?.let { parsed ->
                parsed.version == model.version && parsed.identity == model.identity && parsed.collisionSuffix != null
            } == true }
            .sortedBy { it.name }
        candidates.firstNotNullOfOrNull { reusable(it, hashes) }?.let { existing ->
            val last = model.assets.last()
            onProgress(Slot(last, targets.lastIndex, targets.size, model.sizeBytes - last.size, model.sizeBytes).at(last.size, 1))
            return existing
        }
        val temp = File(root, TEMP_PREFIX + model.directoryName)
        check(temp.isDirectory || temp.mkdir()) { "Cannot create download directory: $temp" }
        val published = try {
            var before = 0L
            for ((index, target) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                fetch(temp, Slot(target.asset, index, targets.size, before, model.sizeBytes), target.url, onProgress)
                before += target.asset.size
            }
            currentCoroutineContext().ensureActive()
            // A resumed directory may contain leftovers. Resolve it before the
            // publishing rename, just as a caller will resolve the finished bundle.
            val staged = ModelFiles.fromDirectory(temp)
            require(staged.files().map { it.name }.toSet() == hashes.keys) { "Downloaded model layout does not match its assets" }
            // Written last so a temp directory never carries a manifest for
            // files that are not all there yet.
            ModelManifest.write(temp, model)
            // A concurrent installer (another process) may have published the
            // same name meanwhile; rename never merges, so publish beside it.
            val target = if (!destination.exists()) destination else File(root, "${destination.name}-${UUID.randomUUID()}")
            check(temp.renameTo(target)) { "Cannot publish downloaded model directory: $target" }
            target
        } catch (e: Throwable) {
            if (!policy.resume) temp.deleteRecursively()
            throw e
        }
        return ModelFiles.fromDirectory(published).copy(expectedSha256 = hashes)
    }

    /**
     * Everything that can be wrong with the input, checked before the root is
     * touched and before any request: the allow-list is the trust boundary, so
     * it is applied to every asset even when the download will end up skipping
     * it. Names are constrained to what the manifest can round-trip and what
     * [ModelCatalog.installed] can parse back — a model that downloads but
     * cannot be identified afterwards is worse than one rejected here.
     */
    private fun validate(root: File, model: ModelCatalog.Model): List<Target> {
        for (field in listOf(model.from, model.to, model.version)) {
            require(field.isNotEmpty() && field.none { it <= ' ' || it == '/' || it == '\\' }) { "Model from/to/version must be plain tokens: '$field'" }
        }
        require(!model.from.startsWith('.') && !model.to.startsWith('.')) { "Model from/to must not start with '.': hidden directories are not listed" }
        // The directory name is the only key the listing has; a model whose name
        // it cannot parse back to the same pair, version and identity would be
        // downloaded, verified and then invisible to installed()/cleanup().
        val parsed = ModelStore.parseName(model.directoryName, model)
        require(
            parsed != null && parsed.collisionSuffix == null && parsed.from == model.from && parsed.to == model.to &&
                parsed.version == model.version && parsed.identity == model.identity,
        ) { "Model from/to/version do not form a directory name ModelCatalog.installed can read back: '${model.directoryName}'" }
        require(model.assets.isNotEmpty()) { "Model has no assets" }
        require(model.assets.map { it.name }.toSet().size == model.assets.size) { "Model repeats an asset name" }
        // identity is a digest over the hashes in list order; a list that is not
        // in file-name order would publish under a name no other builder derives.
        require(model.assets == model.assets.sortedBy { it.name }) { "Model assets must be in file-name order" }
        val targets = model.assets.map { asset ->
            val name = asset.name
            require(name.isNotBlank() && name == File(name).name && name != "." && name != ".." && name.none { it < ' ' }) { "Asset name must be a plain file name: '$name'" }
            require(!name.endsWith(PART_SUFFIX) && name != ModelManifest.FILE_NAME) { "Asset name is reserved: '$name'" }
            require(asset.size in 1..MAX_ASSET_BYTES) { "Asset size out of range: $name" }
            require(SHA256.matches(asset.sha256)) { "Asset SHA-256 must be 64 lowercase hex digits: $name" }
            require(asset.url.none { it <= ' ' }) { "Asset URL must be a single token: '${asset.url}'" }
            val url = try {
                URL(asset.url)
            } catch (e: MalformedURLException) {
                throw IllegalArgumentException("Malformed asset URL: '${asset.url}'", e)
            }
            require(url.protocol == "https") { "Model downloads require HTTPS: ${asset.url}" }
            require(url.host.orEmpty().lowercase() in policy.allowedHosts) { "Asset host is not in DownloadPolicy.allowedHosts: ${asset.url}" }
            Target(asset, url)
        }
        val resolved = ModelFiles.fromNames(File(root, model.directoryName), model.assets.map { it.name })
        require(resolved.files().map { it.name }.toSet() == model.assets.map { it.name }.toSet()) { "Model assets do not form one complete model layout" }
        require(root.isDirectory || root.mkdirs()) { "Cannot create model directory: $root" }
        return targets
    }

    /** The published directory as [ModelFiles] when it is complete and its bytes still hash; null otherwise. */
    private fun reusable(destination: File, hashes: Map<String, String>): ModelFiles? {
        if (!destination.isDirectory) return null
        return try {
            ModelFiles.fromDirectory(destination).copy(expectedSha256 = hashes).also { it.verify() }
        } catch (_: IllegalArgumentException) {
            null // Download a fresh bundle beside it; never overwrite what is there.
        } catch (_: IOException) {
            null // A concurrently removed or unreadable directory is not reusable.
        }
    }

    /**
     * Bring one asset to its finished name under [temp], retrying the transfer
     * per [policy]. A finished file that still hashes is accepted as is (it may
     * be left from an earlier run); one that does not is removed first.
     */
    private suspend fun fetch(temp: File, slot: Slot, url: URL, onProgress: (ModelCatalog.DownloadProgress) -> Unit) {
        val asset = slot.asset
        val complete = File(temp, asset.name)
        val part = File(temp, asset.name + PART_SUFFIX)
        if (complete.exists()) {
            if (complete.isFile && complete.length() == asset.size && ModelCatalog.sha256(complete) == asset.sha256) {
                discard(part)
                onProgress(slot.at(asset.size, 1))
                return
            }
            discard(complete)
        }
        var attempt = 0
        while (true) {
            attempt++
            currentCoroutineContext().ensureActive()
            val failure: Failure = try {
                transfer(slot, url, part, attempt, onProgress) ?: run {
                    check(part.renameTo(complete)) { "Cannot finalise downloaded file: $complete" }
                    onProgress(slot.at(asset.size, attempt))
                    return
                }
            } catch (e: IOException) {
                Failure.Transport(e)
            }
            if (attempt > policy.maxRetries) throw failure.error()
            val pause = backoff(attempt, failure.retryAfterMillis)
            currentCoroutineContext().ensureActive()
            sleep(pause)
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * One request for [slot]'s asset into [part]: resumes with `Range` when a
     * usable partial file exists, otherwise starts from zero. Returns null once
     * [part] holds the complete, verified asset; a [Failure.Status] for an HTTP
     * status worth retrying. Transport failures propagate as [IOException] with
     * whatever was written kept in [part] for the next attempt.
     *
     * A rejected resume (416, or a `Content-Range` that does not describe the
     * remainder we asked for) discards the partial file and issues one fresh
     * request from zero within the same attempt, which cannot recurse: without
     * a `Range` header neither answer is possible, so both are then protocol
     * failures. A 200 to a range request is the CDN ignoring `Range`; its body
     * is the whole file, so the partial is truncated and the body used as is.
     */
    private suspend fun transfer(
        slot: Slot,
        url: URL,
        part: File,
        attempt: Int,
        onProgress: (ModelCatalog.DownloadProgress) -> Unit,
    ): Failure.Status? {
        val asset = slot.asset
        while (true) {
            val digest = MessageDigest.getInstance("SHA-256")
            var have = resumePoint(part, asset.size, digest)
            // Announces the attempt (a host may show "retrying") and, on a
            // resume, the jump to the prefix already on disk.
            onProgress(slot.at(have, attempt))
            val connection = open(url)
            connection.connectTimeout = policy.connectTimeoutMillis
            connection.readTimeout = policy.readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = connection.responseCode
                val append = when {
                    code < 0 -> throw IOException("Model download got no valid HTTP response: ${asset.name}")
                    code == HttpURLConnection.HTTP_OK -> {
                        digest.reset()
                        have = 0
                        false
                    }
                    code == HttpURLConnection.HTTP_PARTIAL && have > 0 -> {
                        if (coversRemainder(connection.getHeaderField("Content-Range"), have, asset.size)) {
                            true
                        } else {
                            discard(part)
                            continue
                        }
                    }
                    code == HTTP_RANGE_NOT_SATISFIABLE && have > 0 -> {
                        discard(part)
                        continue
                    }
                    code == HttpURLConnection.HTTP_CLIENT_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code in 500..599 ->
                        return Failure.Status(code, retryAfter(connection))
                    else -> error("Model download returned HTTP $code")
                }
                val remaining = asset.size - have
                val declared = connection.contentLengthLong
                if (declared > remaining) {
                    discard(part)
                    throw IllegalArgumentException("Model download exceeds expected size: ${asset.name}")
                }
                var received = 0L
                try {
                    // Raw bytes only: `.spm` vocabularies arrive as text/plain and
                    // must not pass through any charset handling.
                    connection.inputStream.use { input ->
                        FileOutputStream(part, append).use { out ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                received += n
                                require(received <= remaining) { "Model download exceeds expected size: ${asset.name}" }
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                                onProgress(slot.at(have + received, attempt))
                            }
                            // The bytes must be durable before the rename that
                            // publishes them; a rename is metadata only.
                            out.fd.sync()
                        }
                    }
                    if (declared >= 0 && received < declared) {
                        throw EOFException("Model download ended after $received of $declared bytes: ${asset.name}")
                    }
                    require(have + received == asset.size && ModelCatalog.hex(digest.digest()) == asset.sha256) { "Model checksum mismatch: ${asset.name}" }
                } catch (e: IllegalArgumentException) {
                    discard(part) // Wrong bytes; the next call must start this asset over.
                    throw e
                }
                return null
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Bytes of [part] to continue from, with those bytes fed into [digest]; 0
     * when there is nothing usable, in which case [part] is gone. Hashing the
     * prefix costs one read of it, which is what makes the final digest cover
     * the file on disk rather than only this attempt's bytes. Without
     * [ModelCatalog.DownloadPolicy.resume] any partial file is dropped, so a
     * retry in that mode starts from zero too.
     */
    private suspend fun resumePoint(part: File, size: Long, digest: MessageDigest): Long {
        if (!part.exists()) return 0
        if (!policy.resume || !part.isFile || part.length() <= 0 || part.length() >= size) {
            discard(part)
            return 0
        }
        var have = 0L
        part.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                have += n
            }
        }
        if (have <= 0 || have >= size) {
            digest.reset()
            discard(part)
            return 0
        }
        return have
    }

    /**
     * Full-jitter exponential backoff before the retry that follows [failed]
     * failed attempts: uniform over `[0, min(max, initial * 2^(failed-1))]`.
     * A `Retry-After` from the server is a floor on top of that, capped at
     * two minutes so a misconfigured header cannot park a download.
     */
    private fun backoff(failed: Int, retryAfterMillis: Long?): Long {
        val shift = failed - 1
        val initial = policy.initialBackoffMillis
        val doubled = if (shift < 63 && initial <= (Long.MAX_VALUE shr shift)) initial shl shift else Long.MAX_VALUE
        val bound = minOf(policy.maxBackoffMillis, doubled)
        val jittered = if (bound <= 0) 0 else random.nextLong(0, bound.coerceAtMost(Long.MAX_VALUE - 1) + 1)
        return if (retryAfterMillis == null) jittered else maxOf(jittered, retryAfterMillis)
    }

    /** `Retry-After` in milliseconds, clamped to `0..MAX_RETRY_AFTER_MILLIS`; null when absent or unreadable. */
    private fun retryAfter(connection: HttpURLConnection): Long? {
        val header = connection.getHeaderField("Retry-After")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = header.toLongOrNull()
        val millis = if (seconds != null) {
            seconds.coerceIn(0, MAX_RETRY_AFTER_MILLIS / 1000) * 1000
        } else {
            // The other form is an HTTP-date; the JDK parser handles the formats.
            val at = connection.getHeaderFieldDate("Retry-After", 0L)
            if (at <= 0) return null
            at - System.currentTimeMillis()
        }
        return millis.coerceIn(0, MAX_RETRY_AFTER_MILLIS)
    }

    /** True when a 206's `Content-Range` is exactly `bytes <have>-<size-1>/<size>`, the remainder we asked for. */
    private fun coversRemainder(contentRange: String?, have: Long, size: Long): Boolean {
        val match = CONTENT_RANGE.matchEntire(contentRange?.trim() ?: return false) ?: return false
        val (start, end, total) = match.destructured
        return start.toLongOrNull() == have && end.toLongOrNull() == size - 1 && total.toLongOrNull() == size
    }

    private fun discard(file: File) {
        if (file.exists()) check(file.deleteRecursively()) { "Cannot remove $file" }
    }

    private companion object {
        const val TEMP_PREFIX = ".download-"
        const val PART_SUFFIX = ".part"
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_ASSET_BYTES = 1024L * 1024 * 1024 // what ModelCatalog.verify accepts
        const val MAX_RETRY_AFTER_MILLIS = 120_000L
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val HTTP_TOO_MANY_REQUESTS = 429
        // Same string as ModelCatalog's private default; Remote Settings asks clients to name themselves.
        const val USER_AGENT = ModelCatalog.DEFAULT_USER_AGENT
        val SHA256 = Regex("[0-9a-f]{64}")
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}
