package io.github.yinvoker.foxlet

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Model operations share the client's store and source. Only download/prepare/checkUpdates may use the network. */
class ModelManager internal constructor(
    private val root: File,
    private val config: ModelsConfig,
    private val lifecycle: ClientLifecycle,
    private val transport: ModelTransport,
) {
    suspend fun listBundled(): List<ModelDescriptor> = operation { Catalog.models.map(::bundledDescriptor) }
    suspend fun findBundled(pair: LanguagePair): ModelDescriptor? = operation { bundled(pair) }

    suspend fun listInstalled(pair: LanguagePair? = null, verification: VerificationMode = VerificationMode.Skip): List<InstalledModel> =
        operation {
            records(pair).map { record ->
                val model = installed(record)
                if (verification == VerificationMode.Check) checked(model).model else model
            }
        }

    /** Validates candidates newest first. An unverifiable or damaged version never hides a usable older one. */
    suspend fun findUsable(pair: LanguagePair): InstalledModel? = operation { usable(pair) }
    suspend fun verify(model: InstalledModel): VerificationReport = operation { requireOwned(model); checked(model) }

    suspend fun prepare(
        pair: LanguagePair,
        policy: PreparePolicy = PreparePolicy.LocalOrBundled,
        options: DownloadOptions? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): InstalledModel = operation {
        val progress = observer(onProgress)
        progress(DownloadProgress(DownloadStage.CheckingLocal))
        usable(pair)?.let { model ->
            progress(DownloadProgress(DownloadStage.Ready, model.descriptor!!.sizeBytes, model.descriptor.sizeBytes))
            return@operation model
        }
        if (policy == PreparePolicy.LocalOnly) throw ModelNotInstalledException(pair)
        val descriptor = bundled(pair) ?: throw UnsupportedLanguagePairException(pair)
        downloadExact(descriptor, options ?: config.downloadOptions, progress)
    }

    suspend fun download(
        model: ModelDescriptor,
        options: DownloadOptions? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): InstalledModel = operation {
        val progress = observer(onProgress)
        progress(DownloadProgress(DownloadStage.CheckingLocal, totalBytes = model.sizeBytes))
        downloadExact(model, options ?: config.downloadOptions, progress)
    }

    suspend fun checkUpdates(pairs: Set<LanguagePair>? = null, options: UpdateOptions? = null): UpdateReport {
        val selectedPairs = pairs?.toSet()
        return operation {
            val policy = options ?: config.updateOptions
            val network = policy.networkOptions ?: config.networkOptions
            val source = config.source.asSource()
            val index = RemoteIndex(transport.open)
            var attempt = 0
            val changeset = retryIndex(policy) {
                attempt++
                try { index.fetch(source, network) }
                catch (e: HttpStatusException) {
                    throw NetworkException("Model index returned HTTP " + e.status, e.url, e.status, attempt, cause = e)
                } catch (e: IOException) {
                    throw NetworkException("Cannot fetch model index", source.changesetUrl, attempt = attempt, cause = e)
                } catch (e: IllegalStateException) {
                    throw ModelIndexException(e.message ?: "Invalid model index", e)
                }
            }
            currentCoroutineContext().ensureActive()
            val report = index.report(
                records(null), index.availability(changeset, source), source, changeset.timestamp,
                selectedPairs?.map { it.source to it.target }?.toSet(),
            )
            val updates = mutableListOf<ModelUpdate>()
            val assessments = report.candidates.map { candidate ->
                val local = candidate.installed?.let(::installed)
                val target = candidate.available?.descriptor()
                val state = when {
                    target == null -> UpdateState.NoCompatibleRelease
                    local == null -> UpdateState.NotInstalled
                    candidate.updateAvailable -> UpdateState.UpdateAvailable
                    MozillaVersion.compare(local.version, target.version) > 0 -> UpdateState.LocalAhead
                    else -> UpdateState.Current
                }
                if (state == UpdateState.UpdateAvailable && local != null && target != null) {
                    updates += ModelUpdate(local, target,
                        if (MozillaVersion.compare(target.version, local.version) > 0) UpdateReason.NewerVersion else UpdateReason.Repackaged)
                }
                UpdateAssessment(
                    LanguagePair(candidate.from, candidate.to), state, local, target,
                    candidate.installedStillListed, candidate.newerMajorVersion,
                )
            }
            UpdateReport(config.source, Instant.ofEpochMilli(report.checkedAtEpochMillis),
                Instant.ofEpochMilli(report.indexTimestamp), assessments, updates)
        }
    }

    suspend fun delete(model: InstalledModel): DeleteReport = operation {
        Catalog.writeLock.withLock {
            requireOwned(model)
            validateIdentity(model)
            DeleteReport(listOf(ModelStore.deleteDetailed(root, model.record)))
        }
    }

    /** All versions are checked for occupancy before deleting any. Filesystem failures are reported per directory. */
    suspend fun deleteAll(pair: LanguagePair): DeleteReport = operation {
        Catalog.writeLock.withLock {
            val targets = records(pair)
            ActiveModels.ifInactive(targets.map { it.directory }) {
                DeleteReport(targets.map { ModelStore.deleteDetailed(root, it) })
            } ?: throw ModelInUseException(pair = pair)
        }
    }

    suspend fun cleanup(options: CleanupOptions = CleanupOptions(), keep: Set<InstallationId> = emptySet()): CleanupReport {
        val retainedIds = keep.toSet()
        val retainedDirectories = options.keepDirectories.toSet()
        return operation {
            Catalog.writeLock.withLock {
                val keepPaths = records(null).map(::installed).filter { it.id in retainedIds }.map { it.directory }.toSet() + retainedDirectories
                val report = ModelStore.cleanup(root, options.staleTempAge.inWholeMilliseconds, options.removeSuperseded, keepPaths)
                CleanupReport(report.removedTempDirs, report.removedSuperseded.map(::installed), report.skippedInUse, report.failures, report.bytesFreed)
            }
        }
    }

    private suspend fun downloadExact(
        descriptor: ModelDescriptor, options: DownloadOptions, progress: (DownloadProgress) -> Unit,
    ): InstalledModel = Catalog.writeLock.withLock {
        require(MozillaVersion.parse(descriptor.version)?.major in Catalog.supportedMajorVersions) { "This engine does not support the descriptor's model major version" }
        require(descriptor.assets.all { URL(it.url).host.lowercase(java.util.Locale.ROOT) in config.source.allowedAttachmentHosts }) {
            "Descriptor contains an asset outside this client's allowed attachment hosts"
        }
        val network = options.networkOptions ?: config.networkOptions
        val policy = Catalog.DownloadPolicy(
            network.connectTimeout.timeoutMillis(), network.readTimeout.timeoutMillis(), options.maxRetries,
            options.initialBackoff.inWholeMilliseconds, options.maxBackoff.inWholeMilliseconds, options.resume,
            config.source.allowedAttachmentHosts, config.source.userAgent,
        )
        val files = transport.download(root, descriptor.asCatalog(), policy) { value ->
            progress(DownloadProgress(DownloadStage.Downloading, value.downloaded, value.total, value.assetName,
                value.assetIndex, value.assetCount, value.assetDownloaded, value.assetSize, value.attempt))
        }
        progress(DownloadProgress(DownloadStage.Verifying, descriptor.sizeBytes, descriptor.sizeBytes))
        val publishedDirectory = checkNotNull(files.model.absoluteFile.parentFile)
        val record = records(descriptor.pair).firstOrNull { it.directory.canonicalFile == publishedDirectory.canonicalFile }
            ?: throw ModelStorageException("Published model cannot be found", publishedDirectory)
        val verified = checked(installed(record))
        if (verified.status != VerificationStatus.Verified) throw ModelIntegrityException("Published model failed verification", verified.model.id)
        progress(DownloadProgress(DownloadStage.Ready, descriptor.sizeBytes, descriptor.sizeBytes))
        verified.model
    }

    private fun bundledDescriptor(model: Catalog.Model): ModelDescriptor {
        val descriptor = model.descriptor()
        if (config.source.attachmentBaseUrl == ModelSource.Mozilla.attachmentBaseUrl) return descriptor
        return ModelDescriptor(descriptor.pair, descriptor.version, descriptor.assets.map {
            it.copy(url = config.source.attachmentBaseUrl + it.url.removePrefix(ModelSource.Mozilla.attachmentBaseUrl))
        })
    }
    private fun bundled(pair: LanguagePair) = Catalog.models.firstOrNull { it.from == pair.source && it.to == pair.target }?.let(::bundledDescriptor)
    private fun records(pair: LanguagePair?) = ModelStore.installed(root, false).filter {
        pair == null || (it.from == pair.source && it.to == pair.target)
    }
    private fun installed(record: Catalog.InstalledModel) = InstalledModel(
        record, root,
        when {
            record.model == null || MozillaVersion.parse(record.version)?.major !in Catalog.supportedMajorVersions -> VerificationStatus.Unverifiable
            record.verified == true -> VerificationStatus.Verified
            record.verified == false -> VerificationStatus.Invalid
            else -> VerificationStatus.NotChecked
        },
    )

    private suspend fun usable(pair: LanguagePair): InstalledModel? {
        for (record in records(pair)) {
            currentCoroutineContext().ensureActive()
            val result = checked(installed(record))
            if (result.status == VerificationStatus.Verified) return result.model
        }
        return null
    }

    private suspend fun checked(model: InstalledModel): VerificationReport {
        val descriptor = model.descriptor
        if (descriptor == null || MozillaVersion.parse(descriptor.version)?.major !in Catalog.supportedMajorVersions)
            return VerificationReport(model, VerificationStatus.Unverifiable, emptyList())
        val assets = descriptor.assets.map { asset ->
            currentCoroutineContext().ensureActive()
            val file = File(model.directory, asset.name)
            if (!file.isFile) {
                AssetVerification(asset.name, VerificationStatus.Invalid, asset.sizeBytes, null, asset.sha256, null, "Missing regular file")
            } else {
                val size = file.length()
                val hash = Catalog.sha256(file)
                AssetVerification(asset.name,
                    if (size == asset.sizeBytes && hash == asset.sha256) VerificationStatus.Verified else VerificationStatus.Invalid,
                    asset.sizeBytes, size, asset.sha256, hash)
            }
        }
        val layoutValid = try {
            val files = model.record.files()
            files.files().map { it.name }.toSet() == descriptor.assets.map { it.name }.toSet()
        } catch (_: IllegalArgumentException) { false }
        val status = if (layoutValid && assets.all { it.status == VerificationStatus.Verified }) VerificationStatus.Verified else VerificationStatus.Invalid
        return VerificationReport(InstalledModel(model.record, root, status), status, assets)
    }

    private fun requireOwned(model: InstalledModel) {
        require(model.store == root && model.directory.canonicalFile.parentFile == root && !java.nio.file.Files.isSymbolicLink(model.directory.toPath())) {
            "Installation does not belong to this model store"
        }
    }

    private fun validateIdentity(model: InstalledModel) {
        if (!model.directory.exists()) return
        val actual = records(null).firstOrNull { it.directory.canonicalFile == model.directory.canonicalFile }
        require(actual != null && actual.identity == model.identity && actual.version == model.version &&
            actual.from == model.pair.source && actual.to == model.pair.target) { "Installation identity changed since it was selected" }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = lifecycle.run {
        try { block() }
        catch (e: ObserverFailure) { throw e.original }
        catch (e: IOException) { throw ModelStorageException("Model storage operation failed", root, e) }
        catch (e: SecurityException) { throw ModelStorageException("Model storage access denied", root, e) }
    }

    private fun observer(callback: (DownloadProgress) -> Unit): (DownloadProgress) -> Unit = {
        try { callback(it) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { throw ObserverFailure(e) }
    }

    private suspend fun <T> retryIndex(options: UpdateOptions, block: () -> T): T {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try { return block() }
            catch (e: NetworkException) {
                if (failures++ >= options.maxRetries || (e.httpStatus != null && e.httpStatus !in setOf(408, 429) && e.httpStatus !in 500..599)) throw e
                val initial = options.initialBackoff.inWholeMilliseconds
                val factor = 1L shl (failures - 1)
                val bound = minOf(options.maxBackoff.inWholeMilliseconds, if (initial > Long.MAX_VALUE / factor) Long.MAX_VALUE else initial * factor)
                val backoff = (e.cause as? HttpStatusException)?.retryAfterMillis ?: 0L
                delay(maxOf(backoff, if (bound > 0) Random.nextLong(bound.coerceAtMost(Long.MAX_VALUE - 1) + 1) else 0))
            }
        }
    }
}

internal class ObserverFailure(val original: Throwable) : RuntimeException(original)
internal class ModelTransport(val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    suspend fun download(root: File, model: Catalog.Model, policy: Catalog.DownloadPolicy, progress: (Catalog.DownloadProgress) -> Unit) =
        ModelDownloader(policy, open).download(root, model, progress)
}

internal class HttpStatusException(val url: String, val status: Int, val retryAfterMillis: Long? = null) :
    IOException("HTTP " + status)
