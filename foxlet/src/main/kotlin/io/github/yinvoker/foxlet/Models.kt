package io.github.yinvoker.foxlet

import java.io.File
import java.net.URI
import java.time.Instant
import java.util.Collections
import java.util.Locale

/** Explicit BCP-47 style language tags; zh is never expanded to a script variant. */
class LanguagePair(source: String, target: String) {
    val source = normalize(source)
    val target = normalize(target)
    override fun equals(other: Any?) = other is LanguagePair && source == other.source && target == other.target
    override fun hashCode() = 31 * source.hashCode() + target.hashCode()
    override fun toString() = source + " → " + target
    private fun normalize(tag: String): String {
        require(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*").matches(tag)) { "Invalid language tag: " + tag }
        return tag.split('-').mapIndexed { index, part ->
            when {
                index == 0 -> part.lowercase(Locale.ROOT)
                part.length == 4 && part.all(Char::isLetter) -> part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                part.length == 2 && part.all(Char::isLetter) -> part.uppercase(Locale.ROOT)
                else -> part.lowercase(Locale.ROOT)
            }
        }.joinToString("-")
    }
}

data class ModelAsset(val name: String, val sizeBytes: Long, val sha256: String, val url: String) {
    init {
        require(name.isNotBlank() && name == File(name).name && name !in setOf(".", "..", ModelManifest.FILE_NAME) &&
            !name.endsWith(".part") && name.none { it < ' ' || it == '\\' }) { "Invalid asset name" }
        require(sizeBytes in 1..(1024L * 1024 * 1024)) { "Asset size must be 1 byte..1 GiB" }
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "Expected lowercase SHA-256" }
        val uri = URI.create(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) { "Asset URL must use HTTPS without user info or fragment" }
    }
}

/** Exact downloadable content. Asset order and identity are normalized by the SDK. */
class ModelDescriptor(val pair: LanguagePair, val version: String, assets: List<ModelAsset>) {
    val assets: List<ModelAsset> = Collections.unmodifiableList(assets.sortedBy { it.name })
    val sizeBytes: Long get() = assets.sumOf { it.sizeBytes }
    val identity: String get() = asCatalog().identity
    init {
        require(MozillaVersion.parse(version) != null) { "Unsupported model version shape: " + version }
        require(this.assets.isNotEmpty() && this.assets.map { it.name }.distinct().size == this.assets.size) { "Assets must be nonempty and unique" }
        val names = this.assets.map { it.name }
        require(ModelFiles.fromNames(File("."), names).files().map { it.name }.toSet() == names.toSet()) { "Assets must form one complete model" }
    }
    internal fun asCatalog() = Catalog.Model(pair.source, pair.target, version, assets.map { Catalog.Asset(it.name, it.sizeBytes, it.sha256, it.url) })
    override fun equals(other: Any?) = other is ModelDescriptor && pair == other.pair && version == other.version && assets == other.assets
    override fun hashCode() = 31 * (31 * pair.hashCode() + version.hashCode()) + assets.hashCode()
    override fun toString() = pair.toString() + " " + version + " (" + identity + ")"
}

sealed interface LocalModel { val pair: LanguagePair }

/** Identity of one directory in one store. Repair copies have distinct IDs. */
class InstallationId internal constructor(val value: String) {
    override fun equals(other: Any?) = other is InstallationId && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = value
}

enum class VerificationStatus { NotChecked, Verified, Invalid, Unverifiable }
enum class VerificationMode { Skip, Check }
enum class PreparePolicy { LocalOnly, LocalOrBundled }

/** Read-only installation snapshot. Holding this object does not pin its files; pass id to cleanup(keep). */
class InstalledModel internal constructor(
    internal val record: Catalog.InstalledModel,
    internal val store: File,
    verificationStatus: VerificationStatus,
) : LocalModel {
    override val pair = LanguagePair(record.from, record.to)
    val id = installationId(store, record.directory)
    val version: String get() = record.version
    val identity: String get() = record.identity
    val directory: File get() = record.directory
    val sizeBytes: Long get() = record.sizeBytes
    val descriptor: ModelDescriptor? = try { record.model?.descriptor() } catch (_: IllegalArgumentException) { null }
    val verificationStatus = if (descriptor == null) VerificationStatus.Unverifiable else verificationStatus
    val isBundledVersion: Boolean get() = record.isCurrentCatalogVersion
    override fun toString() = pair.toString() + " " + version + " [" + verificationStatus + "]"
}

/** Caller-owned files; never deleted by model management. Files must remain immutable while loaded. */
class ExternalModel(override val pair: LanguagePair, files: ModelFiles) : LocalModel {
    val files = files.copy(
        sourceLanguage = pair.source,
        expectedSha256 = Collections.unmodifiableMap(files.expectedSha256.toMap()),
    )
}

data class AssetVerification(
    val assetName: String,
    val status: VerificationStatus,
    val expectedSizeBytes: Long,
    val actualSizeBytes: Long?,
    val expectedSha256: String,
    val actualSha256: String?,
    val detail: String? = null,
)
data class VerificationReport(val model: InstalledModel, val status: VerificationStatus, val assets: List<AssetVerification>)

enum class DownloadStage { CheckingLocal, Downloading, Verifying, Ready }

/** IO-thread callback; byte counts include resumed bytes and may move backwards after a rejected range. */
data class DownloadProgress(
    val stage: DownloadStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val assetName: String? = null,
    val assetIndex: Int? = null,
    val assetCount: Int? = null,
    val assetDownloadedBytes: Long? = null,
    val assetTotalBytes: Long? = null,
    val attempt: Int = 1,
)

enum class UpdateState { NotInstalled, UpdateAvailable, Current, LocalAhead, NoCompatibleRelease }
enum class UpdateReason { NewerVersion, Repackaged }
data class ModelUpdate(val installed: InstalledModel, val target: ModelDescriptor, val reason: UpdateReason)
data class UpdateAssessment(
    val pair: LanguagePair,
    val state: UpdateState,
    val installed: InstalledModel?,
    val target: ModelDescriptor?,
    val installedStillListed: Boolean,
    val newerMajorVersion: String?,
)
data class UpdateReport(
    val source: ModelSource,
    val checkedAt: Instant,
    val indexUpdatedAt: Instant,
    val assessments: List<UpdateAssessment>,
    val updates: List<ModelUpdate>,
) {
    val notInstalled: List<UpdateAssessment> get() = assessments.filter { it.state == UpdateState.NotInstalled }
}

enum class DeleteStatus { Deleted, AlreadyAbsent, PartiallyDeleted, Failed }
data class DeleteResult(
    val id: InstallationId?,
    val directory: File,
    val status: DeleteStatus,
    val freedBytes: Long,
    val failure: ModelStorageException? = null,
)
data class DeleteReport(val results: List<DeleteResult>) {
    val freedBytes: Long get() = results.sumOf { it.freedBytes }
}
data class CleanupReport(
    val removedTempDirectories: List<File>,
    val removedSuperseded: List<InstalledModel>,
    val skippedInUse: List<File>,
    val failures: List<DeleteResult>,
    val freedBytes: Long,
)

internal fun Catalog.Model.descriptor() = ModelDescriptor(LanguagePair(from, to), version, assets.map { ModelAsset(it.name, it.size, it.sha256, it.url) })
internal fun installationId(root: File, directory: File) =
    InstallationId(Catalog.digest((root.canonicalPath + "\u0000" + directory.canonicalPath).toByteArray()).take(32))
internal fun LocalModel.modelFiles(): ModelFiles = when (this) {
    is ExternalModel -> files
    is InstalledModel -> {
        if (descriptor == null || verificationStatus == VerificationStatus.Unverifiable)
            throw ModelIntegrityException("Installation cannot be verified or is unsupported by this engine", id)
        record.files().copy(sourceLanguage = pair.source)
    }
}
