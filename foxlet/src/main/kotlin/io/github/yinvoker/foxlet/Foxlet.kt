package io.github.yinvoker.foxlet

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*

enum class TextFormat {
    Plain,
    /** Experimental native HTML processing; callers should validate their document/output requirements. */
    Html,
}
data class DeviceThreadingInfo(val totalRamBytes: Long, val isLowRam: Boolean, val bigCoreCount: Int, val workload: Workload)
data class ThreadingInfo(val requested: Threading, val threads: Int, val device: DeviceThreadingInfo? = null)
data class TranslatorState(val loadedModelCount: Int, val unconfirmedReleaseCount: Int = 0)
data class UnloadReport(val unloadedModelCount: Int, val allReleased: Boolean, val unconfirmedReleaseCount: Int = 0)
data class FoxletCapabilities(val supportedModelMajorVersions: IntRange, val prefixLanguages: Set<String>)

/** One long-lived client per process. Creation probes configuration but does not scan or download models. */
class Foxlet private constructor(
    val config: FoxletConfig,
    val capabilities: FoxletCapabilities,
    private val lifecycle: ClientLifecycle,
    val models: ModelManager,
    val translator: Translator,
) {
    /** Reject new work, cancel SDK operations, wait for native batches, and release the process lease. */
    suspend fun shutdown() = lifecycle.shutdown()

    companion object {
        suspend fun create(context: Context, configure: FoxletConfigBuilder.() -> Unit): Foxlet =
            create(context, FoxletConfigBuilder().apply(configure).build())

        suspend fun create(context: Context, config: FoxletConfig = FoxletConfig()): Foxlet {
            var created: Foxlet? = null
            return try { withContext(Dispatchers.IO) {
                val application = context.applicationContext
                val root = (config.models.directory ?: File(application.noBackupFilesDir, "translation-models")).canonicalFile
                val resolved = config.copy(models = config.models.copy(directory = root))
                val threading = when (val requested = config.translation.threading) {
                    is Threading.Fixed -> ThreadingInfo(requested, requested.threads)
                    is Threading.Auto -> ThreadPlanner.forDevice(application, requested.workload).let {
                        ThreadingInfo(requested, it.threads, DeviceThreadingInfo(it.totalRamBytes, it.isLowRam, it.bigCoreCount, it.workload))
                    }
                }
                // No suspension between acquiring the lease and constructing its lifecycle owner.
                val engine = NativeEngine(config.translation.engineOptions(threading.threads))
                try {
                    assemble(resolved, threading, engine).also { created = it }
                } catch (e: Throwable) {
                    engine.close()
                    throw e
                }
            } } catch (e: Throwable) {
                // This also covers prompt cancellation while dispatching the successfully built result.
                created?.shutdown()
                throw e
            }
        }

        internal fun assemble(
            config: FoxletConfig,
            threading: ThreadingInfo,
            backend: TranslationBackend,
            transport: ModelTransport = ModelTransport(),
        ): Foxlet {
            val root = requireNotNull(config.models.directory).canonicalFile
            val lifecycle = ClientLifecycle({ backend.close() })
            return Foxlet(
                config,
                FoxletCapabilities(Catalog.supportedMajorVersions, PrefixTables.languages.toSet()),
                lifecycle,
                ModelManager(root, config.models, lifecycle, transport),
                Translator(threading, lifecycle, backend),
            )
        }
    }
}

/** Translation operations accept the same InstalledModel that prepare/download return. */
class Translator internal constructor(
    val threadingInfo: ThreadingInfo,
    private val lifecycle: ClientLifecycle,
    private val backend: TranslationBackend,
) {
    suspend fun translate(text: String, model: LocalModel, format: TextFormat = TextFormat.Plain): String =
        translate(listOf(text), model, format).single()

    /** Preserves input order. Empty lists do not load models. JNI batches cannot be interrupted mid-call. */
    suspend fun translate(texts: List<String>, model: LocalModel, format: TextFormat = TextFormat.Plain): List<String> {
        val input = texts.toList()
        return lifecycle.run {
            if (input.isEmpty()) emptyList() else translation(model) { backend.translate(input, model.modelFiles(), format == TextFormat.Html) }
        }
    }

    suspend fun translatePivot(text: String, first: LocalModel, second: LocalModel, format: TextFormat = TextFormat.Plain): String =
        translatePivot(listOf(text), first, second, format).single()

    suspend fun translatePivot(texts: List<String>, first: LocalModel, second: LocalModel, format: TextFormat = TextFormat.Plain): List<String> {
        val input = texts.toList()
        return lifecycle.run {
            require(first.pair.target == second.pair.source) { "Pivot model directions do not connect" }
            if (input.isEmpty()) emptyList() else translation(first) {
                backend.translatePivot(input, first.modelFiles(), second.modelFiles(), format == TextFormat.Html)
            }
        }
    }

    suspend fun getState(): TranslatorState = lifecycle.run { control { backend.state() } }
    /** Stop submitting uses of a model before deleting; this is not a barrier against later translations. */
    suspend fun unloadModels(): UnloadReport = lifecycle.run { control { backend.unloadModels() } }

    private suspend fun <T> control(block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: FoxletException) { throw e }
        catch (e: Exception) { throw TranslationException("Native state operation failed", e) }
        catch (e: LinkageError) { throw TranslationException("Native translation library could not be loaded", e) }

    private suspend fun <T> translation(model: LocalModel, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) { throw e
    } catch (e: FoxletException) { throw e
    } catch (e: IllegalArgumentException) {
        throw ModelIntegrityException(e.message ?: "Invalid model files", (model as? InstalledModel)?.id, cause = e)
    } catch (e: IOException) {
        val directory = when (model) {
            is InstalledModel -> model.directory
            is ExternalModel -> model.files.model.absoluteFile.parentFile ?: File(".")
        }
        throw ModelStorageException("Cannot read model files", directory, e)
    } catch (e: Exception) {
        throw TranslationException("Native translation failed: " + e.message, e)
    } catch (e: LinkageError) {
        throw TranslationException("Native translation library could not be loaded", e)
    }
}

internal interface TranslationBackend {
    suspend fun translate(texts: List<String>, model: ModelFiles, html: Boolean = false): List<String>
    suspend fun translatePivot(texts: List<String>, first: ModelFiles, second: ModelFiles, html: Boolean = false): List<String>
    suspend fun state(): TranslatorState
    suspend fun unloadModels(): UnloadReport
    fun close()
}
