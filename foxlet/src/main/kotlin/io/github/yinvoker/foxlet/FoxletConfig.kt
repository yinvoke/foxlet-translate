package io.github.yinvoker.foxlet

import java.io.File
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Network deadlines shared by index and asset requests. Zero/infinite deadlines are rejected. */
data class NetworkOptions(
    val connectTimeout: Duration = 15.seconds,
    val readTimeout: Duration = 15.seconds,
) {
    init { connectTimeout.timeoutMillis(); readTimeout.timeoutMillis() }
}

data class DownloadOptions(
    val maxRetries: Int = 3,
    val resume: Boolean = true,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 30.seconds,
    val networkOptions: NetworkOptions? = null,
) {
    init { validateRetry(maxRetries, initialBackoff, maxBackoff) }
}

data class UpdateOptions(
    val maxRetries: Int = 0,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 30.seconds,
    val networkOptions: NetworkOptions? = null,
) {
    init { validateRetry(maxRetries, initialBackoff, maxBackoff) }
}

/** A complete index/attachment trust configuration. Redirects are never followed. */
class ModelSource(
    val indexUrl: String,
    val attachmentBaseUrl: String,
    allowedAttachmentHosts: Set<String>,
    val userAgent: String = "FoxletTranslate (+https://github.com/yinvoke/foxlet-translate)",
) {
    val allowedAttachmentHosts: Set<String> = java.util.Collections.unmodifiableSet(allowedAttachmentHosts.toSet())
    init {
        fun https(value: String): URI = URI.create(value).also {
            require(it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null) {
                "Model sources require HTTPS URLs without user info or fragments"
            }
        }
        https(indexUrl)
        val base = https(attachmentBaseUrl)
        require(base.rawQuery == null && attachmentBaseUrl.endsWith("/")) { "Attachment base must end in / and have no query" }
        require(this.allowedAttachmentHosts.isNotEmpty() && this.allowedAttachmentHosts.all {
            it.isNotBlank() && it == it.lowercase(java.util.Locale.ROOT) && URI.create("https://" + it).host == it
        }) { "Allowed attachment hosts must be lowercase host names" }
        require(base.host.lowercase(java.util.Locale.ROOT) in this.allowedAttachmentHosts) { "Attachment base host is not allowed" }
        require(userAgent.isNotBlank() && userAgent.all { it.code in 32..126 }) { "User-Agent must be one printable ASCII line" }
    }
    internal fun asSource() = Catalog.UpdateSource(indexUrl, attachmentBaseUrl, userAgent)
    override fun equals(other: Any?) = other is ModelSource && indexUrl == other.indexUrl &&
        attachmentBaseUrl == other.attachmentBaseUrl && allowedAttachmentHosts == other.allowedAttachmentHosts && userAgent == other.userAgent
    override fun hashCode() = listOf(indexUrl, attachmentBaseUrl, allowedAttachmentHosts, userAgent).hashCode()
    companion object {
        val Mozilla = ModelSource(
            "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/changeset",
            "https://firefox-settings-attachments.cdn.mozilla.net/",
            setOf("firefox-settings-attachments.cdn.mozilla.net"),
        )
    }
}

sealed interface Threading {
    data class Fixed(val threads: Int = 1) : Threading {
        init { require(threads in 1..64) { "threads must be in 1..64" } }
    }
    data class Auto(val workload: Workload) : Threading
}

sealed interface ModelRetention {
    data class Idle(val duration: Duration = 60.seconds) : ModelRetention {
        init { require(duration.isFinite() && duration.inWholeMilliseconds > 0) { "Idle retention must be finite and at least 1 ms" } }
    }
    data object AfterRequest : ModelRetention
    data object UntilShutdown : ModelRetention
}

data class TranslationConfig(
    val threading: Threading = Threading.Fixed(1),
    val retention: ModelRetention = ModelRetention.Idle(),
    val miniBatchWords: Int = 512,
    val cacheSize: Int = 0,
    val nonbreakingPrefixes: Boolean = true,
) {
    init {
        require(miniBatchWords in 256..65536) { "miniBatchWords must be in 256..65536" }
        require(cacheSize in 0..1_000_000) { "cacheSize must be in 0..1000000" }
    }
    internal fun engineOptions(threads: Int) = EngineOptions(
        threads = threads,
        idleUnloadMillis = when (val value = retention) {
            is ModelRetention.Idle -> value.duration.inWholeMilliseconds
            ModelRetention.AfterRequest -> 0
            ModelRetention.UntilShutdown -> -1
        },
        miniBatchWords = miniBatchWords, cacheSize = cacheSize, nonbreakingPrefixes = nonbreakingPrefixes,
    )
}

data class ModelsConfig(
    /** Null selects application noBackupFilesDir/translation-models; set explicitly to reuse another root. */
    val directory: File? = null,
    val source: ModelSource = ModelSource.Mozilla,
    val networkOptions: NetworkOptions = NetworkOptions(),
    val downloadOptions: DownloadOptions = DownloadOptions(),
    val updateOptions: UpdateOptions = UpdateOptions(),
)

data class FoxletConfig(
    val models: ModelsConfig = ModelsConfig(),
    val translation: TranslationConfig = TranslationConfig(),
)

@DslMarker
annotation class FoxletDsl

@FoxletDsl
class FoxletConfigBuilder {
    private var modelConfig = ModelsConfig()
    private var translationConfig = TranslationConfig()
    fun models(block: ModelsConfigBuilder.() -> Unit) { modelConfig = ModelsConfigBuilder().apply(block).build() }
    fun translation(block: TranslationConfigBuilder.() -> Unit) { translationConfig = TranslationConfigBuilder().apply(block).build() }
    fun build(): FoxletConfig = FoxletConfig(modelConfig, translationConfig)
}

@FoxletDsl
class ModelsConfigBuilder {
    var directory: File? = null
    var source: ModelSource = ModelSource.Mozilla
    var networkOptions = NetworkOptions()
    var downloadOptions = DownloadOptions()
    var updateOptions = UpdateOptions()
    fun build() = ModelsConfig(directory, source, networkOptions, downloadOptions, updateOptions)
}

@FoxletDsl
class TranslationConfigBuilder {
    var threading: Threading = Threading.Fixed(1)
    var retention: ModelRetention = ModelRetention.Idle()
    var miniBatchWords = 512
    var cacheSize = 0
    var nonbreakingPrefixes = true
    fun build() = TranslationConfig(threading, retention, miniBatchWords, cacheSize, nonbreakingPrefixes)
}

data class CleanupOptions(
    val staleTempAge: Duration = 7.days,
    val removeSuperseded: Boolean = true,
    val keepDirectories: Set<File> = emptySet(),
) {
    init { require(staleTempAge.isFinite() && staleTempAge >= Duration.ZERO) { "Temp age must be finite and nonnegative" } }
}

internal fun Duration.timeoutMillis(): Int {
    require(isFinite() && inWholeMilliseconds in 1..Int.MAX_VALUE.toLong()) { "Timeout must be 1 ms..Int.MAX_VALUE ms" }
    return inWholeMilliseconds.toInt()
}

private fun validateRetry(count: Int, initial: Duration, max: Duration) {
    require(count in 0..20) { "maxRetries must be in 0..20" }
    require(initial.isFinite() && max.isFinite() && initial >= Duration.ZERO && max >= initial) { "Backoff must be finite and satisfy 0 <= initial <= max" }
}
