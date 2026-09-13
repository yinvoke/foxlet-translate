package io.github.yinvoker.foxlet

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pinned Mozilla models plus the on-device model store.
 *
 * The index ships with the SDK; text is never sent to a server. The only
 * functions that touch the network are the [download] overloads and
 * [checkForUpdates], and each of them says so. Everything else here reads
 * and writes the model root the host passes in.
 */
internal object Catalog {
    data class Asset(val name: String, val size: Long, val sha256: String, val url: String)

    /**
     * One translation direction and its files. [assets] are kept in file-name
     * order; [identity] is a digest over their hashes in that order, so the
     * same bytes always yield the same identity and any changed byte a new one.
     */
    data class Model(val from: String, val to: String, val version: String, val assets: List<Asset>) {
        val sizeBytes: Long get() = assets.sumOf { it.size }
        /** First 20 hex digits of SHA-256 over the asset hashes; part of [directoryName]. */
        val identity: String get() = digest(assets.joinToString { it.sha256 }.toByteArray()).take(20)
        /** Directory [download] publishes under its root: `<from>-<to>-<version>-<identity>`. */
        val directoryName: String get() = "$from-$to-$version-$identity"
    }
    val models: List<Model> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/io/github/yinvoker/foxlet/models.tsv")) { "Model catalog missing from AAR" }
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t') }.toList()
                .groupBy { it.take(3) }.map { (key, rows) ->
                    Model(key[0], key[1], key[2], rows.map {
                        Asset(it[3], it[4].toLong(), it[5], it[6])
                    }.sortedBy { it.name })
                }
        }
    }
    fun find(from: String, to: String): Model = models.singleOrNull { it.from == from && it.to == to }
        ?: throw IllegalArgumentException("No direct model for $from → $to")

    /**
     * Model major versions this engine build can load. Mozilla's `translations-models`
     * collection carries 1.x and 2.x records (uncompressed student models, the
     * Firefox ESR line); 3.x records live in a separate zstd-compressed collection
     * the engine does not read. [checkForUpdates] reports newer majors, never selects them.
     */
    val supportedMajorVersions: IntRange = 1..2

    /** Host every bundled asset URL points at; the default [DownloadPolicy.allowedHosts]. */
    const val MOZILLA_ATTACHMENT_HOST = "firefox-settings-attachments.cdn.mozilla.net"
    internal const val DEFAULT_USER_AGENT = "FoxletTranslate (+https://github.com/yinvoke/foxlet-translate)"

    // ---------------------------------------------------------------- download

    /**
     * Transport policy for [download]. Retries cover transport failures and
     * HTTP 408/429/5xx with full-jitter exponential backoff; a size or SHA-256
     * mismatch is never retried. With [resume] a failed or cancelled download
     * keeps its partial files under the root and the next call continues them
     * with HTTP range requests; without it, temp files are removed on failure.
     * [allowedHosts] is the trust boundary for asset URLs: HTTPS to one of these
     * hosts, no redirects. Add a host here before downloading from a custom
     * [UpdateSource].
     */
    data class DownloadPolicy(
        val connectTimeoutMillis: Int = 15_000,
        val readTimeoutMillis: Int = 15_000,
        val maxRetries: Int = 3,
        val initialBackoffMillis: Long = 1_000,
        val maxBackoffMillis: Long = 30_000,
        val resume: Boolean = true,
        val allowedHosts: Set<String> = setOf(MOZILLA_ATTACHMENT_HOST),
        val userAgent: String = DEFAULT_USER_AGENT,
    ) {
        init {
            // HttpURLConnection reads a zero timeout as "wait forever".
            require(connectTimeoutMillis > 0 && readTimeoutMillis > 0) { "timeouts must be positive" }
            require(maxRetries in 0..20) { "maxRetries must be in 0..20" }
            require(initialBackoffMillis >= 0 && maxBackoffMillis >= initialBackoffMillis) { "backoff must satisfy 0 <= initial <= max" }
            require(allowedHosts.isNotEmpty() && allowedHosts.all { it.isNotBlank() && it == it.lowercase() }) { "allowedHosts must list lowercase host names" }
        }
        companion object { val DEFAULT = DownloadPolicy() }
    }

    /**
     * One progress step of [download]. [downloaded]/[total] cover the whole
     * model (finished assets plus the current one, including any resumed
     * prefix); the asset fields cover the file in flight. [attempt] is 1 on
     * the first try of an asset and grows with each retry, so a host can show
     * "retrying". Delivered on the calling (IO) thread; keep handlers cheap.
     */
    data class DownloadProgress(
        val assetName: String,
        val assetIndex: Int,
        val assetCount: Int,
        val assetDownloaded: Long,
        val assetSize: Long,
        val downloaded: Long,
        val total: Long,
        val attempt: Int,
    )

    internal val writeLock = Mutex()

    /**
     * Download to a fresh directory under [root], check size and SHA-256, then atomically
     * publish that directory. Never updates an active model in place. [onProgress] runs
     * on an IO thread. An already verified directory is reused without any network
     * request. Interrupted transfers resume and transient failures are retried under
     * [DownloadPolicy.DEFAULT]; a size or hash mismatch fails without publishing anything.
     * Use app-private storage; model bytes must not be modified while an engine uses them.
     */
    suspend fun download(
        root: File, from: String, to: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): ModelFiles = download(root, find(from, to), DownloadPolicy.DEFAULT) { onProgress(it.downloaded, it.total) }

    /**
     * [download] with an explicit [policy] and per-asset progress. Same
     * guarantees: a fresh directory, size and SHA-256 checks, atomic publish,
     * reuse of an already verified directory without any network request.
     * The policy is what selects this overload: pass [DownloadPolicy.DEFAULT]
     * to get [DownloadProgress] with default transport settings, since the
     * overload without a policy takes the two-argument progress lambda.
     */
    suspend fun download(
        root: File, from: String, to: String,
        policy: DownloadPolicy,
        onProgress: (DownloadProgress) -> Unit = {},
    ): ModelFiles = download(root, find(from, to), policy, onProgress)

    /**
     * Download any [model], including one returned by [checkForUpdates] that the
     * bundled catalog does not know yet. Trust for such a model is the HTTPS
     * connection to the index plus the size and SHA-256 its record carries.
     * This client does not verify Remote Settings collection signatures.
     * Asset URLs must be HTTPS on
     * a host in [DownloadPolicy.allowedHosts]. Downloads are serialised
     * process-wide. The published directory carries a manifest so [installed]
     * can identify it later.
     */
    suspend fun download(
        root: File, model: Model,
        policy: DownloadPolicy = DownloadPolicy.DEFAULT,
        onProgress: (DownloadProgress) -> Unit = {},
    ): ModelFiles = withContext(Dispatchers.IO) {
        writeLock.withLock { ModelDownloader(policy).download(root, model, onProgress) }
    }

    // ---------------------------------------------------------------- installed

    /**
     * One published model directory under a root.
     *
     * [model] is the asset manifest the directory was downloaded with, or the
     * bundled catalog entry with the same [identity]; null for a directory this
     * SDK cannot account for (it is listed, but nothing can be verified against).
     * [isCurrentCatalogVersion] says whether the bundled catalog still points at
     * exactly these bytes for the pair. [verified] is null unless the listing was
     * asked to hash the files, then true/false for pass/fail; it stays null when
     * there is no [model] to check against. [sizeBytes] is what the directory
     * occupies on disk.
     */
    data class InstalledModel(
        val from: String,
        val to: String,
        val version: String,
        val identity: String,
        val directory: File,
        val sizeBytes: Long,
        val model: Model?,
        val isCurrentCatalogVersion: Boolean,
        val verified: Boolean?,
    ) {
        /** Engine handle for this directory, with hashes pinned when [model] is known. */
        fun files(): ModelFiles {
            val resolved = ModelFiles.fromDirectory(directory)
            val known = model ?: return resolved
            return resolved.copy(expectedSha256 = known.assets.associate { it.name to it.sha256 })
        }
    }

    /**
     * Every published model directory under [root], newest version first per
     * pair. No network. With [verify] each directory with a known [InstalledModel.model]
     * is hashed (tens of MB per pair, the same cost as the reuse path of [download]);
     * without it the listing is a directory scan.
     */
    suspend fun installed(root: File, verify: Boolean = false): List<InstalledModel> =
        withContext(Dispatchers.IO) { ModelStore.installed(root, verify) }

    /**
     * The newest installed and verified directory for a pair as a [ModelFiles],
     * or null when nothing usable is installed. Hashes every candidate it tries;
     * never downloads. This is the offline half of "use what we have now,
     * update later": pair it with [checkForUpdates] and [download].
     */
    suspend fun installedFor(root: File, from: String, to: String): ModelFiles? =
        withContext(Dispatchers.IO) { ModelStore.installedFor(root, from, to) }

    // ---------------------------------------------------------------- delete / cleanup

    /**
     * Remove one published directory and return the bytes freed. The directory is
     * unpublished with a rename before its files go, so a concurrent [installed]
     * never sees a half-deleted model. Throws [IllegalStateException] while a
     * [NativeEngine] is loading or holds files from it; call
     * [NativeEngine.releaseAllModels] first and stop submitting work using those
     * files. A saved [ModelFiles] alone does not reserve its directory after an
     * idle unload; pass such directories to [cleanup]'s `keep` set.
     * [IllegalArgumentException] if the
     * directory is not directly under [root].
     */
    suspend fun delete(root: File, model: InstalledModel): Long =
        withContext(Dispatchers.IO) { writeLock.withLock { ModelStore.delete(root, model) } }

    /**
     * Remove every installed version of a pair; all-or-nothing with respect to
     * the in-use check (nothing is deleted when any version is held by an engine).
     * Returns the bytes freed.
     */
    suspend fun delete(root: File, from: String, to: String): Long =
        withContext(Dispatchers.IO) { writeLock.withLock { ModelStore.delete(root, from, to) } }

    data class CleanupReport(
        val removedTempDirs: List<File>,
        val removedSuperseded: List<InstalledModel>,
        val bytesFreed: Long,
        val skippedInUse: List<File>,
        val failures: List<DeleteResult> = emptyList(),
    )

    /**
     * Reclaim space under [root]: download temp directories untouched for longer
     * than [staleTempAgeMillis] (a paused resumable download younger than that
     * survives), leftovers of interrupted deletes, and — with [removeSuperseded] —
     * every older version of a pair once a newer version is installed and passes
     * verification. Directories an engine is loading or holds are skipped and
     * reported in [CleanupReport.skippedInUse]; directories in [keep] are
     * silently preserved. Nothing is downloaded. Hashes the newest version of
     * each pair to decide what it supersedes.
     */
    suspend fun cleanup(
        root: File,
        staleTempAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
        removeSuperseded: Boolean = true,
        keep: Set<File> = emptySet(),
    ): CleanupReport = withContext(Dispatchers.IO) {
        // Under the download lock: a temp directory an in-flight download is
        // writing to is only protected by its fresh mtime otherwise.
        writeLock.withLock { ModelStore.cleanup(root, staleTempAgeMillis, removeSuperseded, keep) }
    }

    // ---------------------------------------------------------------- update detection

    /**
     * Where [checkForUpdates] reads the model index. [changesetUrl] is a Remote
     * Settings changeset endpoint; [attachmentBaseUrl] is prepended to each
     * record's attachment location. Downloads from the resulting URLs need the
     * base URL's host in [DownloadPolicy.allowedHosts]. [userAgent] is the only
     * identifying header sent (Remote Settings asks clients to name themselves).
     */
    data class UpdateSource(
        val changesetUrl: String,
        val attachmentBaseUrl: String,
        val userAgent: String = DEFAULT_USER_AGENT,
    ) {
        init {
            require(changesetUrl.startsWith("https://") && attachmentBaseUrl.startsWith("https://")) { "Update sources must use HTTPS" }
            require(userAgent.isNotBlank() && userAgent.none { it < ' ' }) { "userAgent must be a single printable line" }
        }
        companion object {
            /** Mozilla's `translations-models` collection, the source of the bundled catalog. */
            val MOZILLA = UpdateSource(
                changesetUrl = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/changeset",
                attachmentBaseUrl = "https://firefox-settings-attachments.cdn.mozilla.net/",
            )
        }
    }

    /**
     * One pair as seen by [checkForUpdates]. [installed] is the newest version
     * on disk (not hashed), [available] the best upstream release within
     * [supportedMajorVersions] — pass it to [download] to install it.
     * [updateAvailable] is true when [available] differs from [installed] and is
     * not older. [installedStillListed] is false when upstream no longer offers
     * the installed bytes (Firefox deletes such files; this SDK only reports).
     * [newerMajorVersion] names an upstream release this engine build cannot
     * load, as a hint to upgrade the SDK.
     */
    data class UpdateCandidate(
        val from: String,
        val to: String,
        val installed: InstalledModel?,
        val available: Model?,
        val updateAvailable: Boolean,
        val downloadSizeBytes: Long,
        val installedStillListed: Boolean,
        val newerMajorVersion: String?,
    )

    /** Result of one [checkForUpdates]; [indexTimestamp] is the upstream collection timestamp (ms epoch). */
    data class UpdateReport(
        val source: UpdateSource,
        val checkedAtEpochMillis: Long,
        val indexTimestamp: Long,
        val candidates: List<UpdateCandidate>,
    ) {
        val updates: List<UpdateCandidate> get() = candidates.filter { it.updateAvailable }
        val notInstalled: List<UpdateCandidate> get() = candidates.filter { it.installed == null && it.available != null }
    }

    /**
     * Ask [source] what it publishes and compare with what is installed under
     * [root]. This is the one network request outside [download]: a single
     * HTTPS GET of the index (about 80 KB compressed), carrying no identifier
     * beyond [UpdateSource.userAgent]. Nothing is downloaded or deleted; the
     * host decides what to do with the report. Selection uses Firefox's version
     * ordering with conservative filtering:
     * highest release version per pair within [supportedMajorVersions], only
     * records published for every channel or explicitly for Android, only
     * versions that ship a complete file set. [pairs] restricts the report;
     * null reports every pair installed or available. Throws [java.io.IOException]
     * when the index cannot be fetched and [IllegalStateException] when it
     * cannot be parsed. Call it when the user asks, not on a timer.
     */
    suspend fun checkForUpdates(
        root: File,
        source: UpdateSource = UpdateSource.MOZILLA,
        pairs: Set<Pair<String, String>>? = null,
    ): UpdateReport = withContext(Dispatchers.IO) {
        val index = RemoteIndex()
        val changeset = index.fetch(source)
        index.report(ModelStore.installed(root, verify = false), index.availability(changeset, source), source, changeset.timestamp, pairs)
    }

    // ---------------------------------------------------------------- verification

    internal fun verify(files: ModelFiles) {
        val paths = files.files()
        val hashes = paths.associate { file ->
            require(file.isFile && file.length() in 1..(1024L * 1024 * 1024)) { "Missing, empty or oversized file: $file" }
            file.name to sha256(file)
        }
        require(hashes.size == paths.size) { "Bundle has duplicate file names" }
        val expected = files.expectedSha256
        if (expected.isNotEmpty()) {
            require(expected.keys == hashes.keys && expected.all { (name, sha) -> sha.matches(Regex("[0-9a-fA-F]{64}")) && sha.equals(hashes[name], ignoreCase = true) }) {
                "Model SHA-256 mismatch; use hashes from a trusted publisher, not the downloaded file"
            }
        } else {
            require(models.any { model ->
                model.assets.associate { it.name to it.sha256 } == hashes
            }) { "Files do not match a bundled Mozilla model; provide a complete trusted expectedSha256 manifest for a custom model" }
        }
    }
    internal fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
        }
        return hex(sha.digest())
    }
    internal fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
