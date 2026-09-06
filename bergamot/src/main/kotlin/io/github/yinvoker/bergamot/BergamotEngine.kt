package io.github.yinvoker.bergamot

import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Local files of one translation direction (Mozilla student model layout). */
data class ModelFiles(
    val model: File,
    val srcVocab: File,
    val trgVocab: File,
    val shortlist: File,
) {
    companion object {
        /**
         * Hard-wired in the config YAML below. The engine SIGABRTs (no Java
         * exception) when mini-batch-words < 2x this value, so [EngineConfig]
         * validates against it.
         */
        const val MAX_LENGTH_BREAK = 128

        /** Directory holding model.*.bin, *vocab*.spm, lex.*.bin for one direction. */
        fun fromDirectory(dir: File): ModelFiles {
            fun pick(what: String, predicate: (String) -> Boolean): File =
                dir.listFiles()?.firstOrNull { predicate(it.name) }
                    ?: throw IllegalArgumentException("no $what file in $dir")
            val model = pick("model") { it.startsWith("model.") && it.endsWith(".bin") }
            val shortlist = pick("shortlist") { it.startsWith("lex.") && it.endsWith(".bin") }
            val srcVocab = pick("vocab") { it.contains("vocab") && it.endsWith(".spm") && !it.startsWith("trg") }
            val trgVocab = dir.listFiles()?.firstOrNull { it.name.startsWith("trgvocab") && it.name.endsWith(".spm") }
                ?: srcVocab // single shared vocab
            return ModelFiles(model, srcVocab, trgVocab, shortlist)
        }
    }

    internal fun toConfigYaml(workspaceMb: Int, miniBatchWords: Int = 512): String = """
        models:
          - ${model.absolutePath}
        vocabs:
          - ${srcVocab.absolutePath}
          - ${trgVocab.absolutePath}
        shortlist:
          - ${shortlist.absolutePath}
          - false
        beam-size: 1
        normalize: 1.0
        word-penalty: 0
        max-length-break: $MAX_LENGTH_BREAK
        mini-batch-words: $miniBatchWords
        workspace: $workspaceMb
        max-length-factor: 2.0
        skip-cost: true
        cpu-threads: 0
        quiet: true
        quiet-translation: true
        gemm-precision: int8shiftAlphaAll
        alignment: soft
    """.trimIndent()
}

class EngineConfig(
    /** Worker translation threads inside the engine (>=1). */
    val threads: Int = 1,
    /** Marian workspace per worker, MB. Smaller = less RAM, may cost speed. */
    val workspaceMb: Int = 128,
    /** Unload a model after this long without use. */
    val idleUnloadMillis: Long = 60_000,
    /**
     * Pin worker threads to the fastest CPU cores (big.LITTLE SoCs schedule
     * translation onto mid cores surprisingly often; the prime core is ~1.5x
     * faster at equal clocks). Silent no-op on uniform topologies or when the
     * OS refuses; affinity is re-applied on every batch, so it heals itself
     * after background/foreground cpuset moves.
     */
    val pinToFastCores: Boolean = true,
    /**
     * Marian mini-batch-words. 512 beats 1024 across every worker tier
     * (device-controlled A/B: never slower, up to -23% at 4 workers, slightly
     * less RAM) because a smaller batch keeps the shortlist union — and with
     * it the output layer — narrow.
     */
    val miniBatchWords: Int = 512,
    /**
     * Translation cache entries, 0 = off. Zero-cost on miss; repeated texts
     * hit at ~40x. Off by default: a hit can legitimately differ from a fresh
     * translation by a line (batch-context dependence), so enable only where
     * repeated inputs dominate (suggested 4096-16384).
     */
    val cacheSize: Int = 0,
) {
    init {
        require(miniBatchWords >= 2 * ModelFiles.MAX_LENGTH_BREAK) {
            "miniBatchWords ($miniBatchWords) must be >= ${2 * ModelFiles.MAX_LENGTH_BREAK}: " +
                "below 2x max-length-break the engine aborts the process"
        }
        require(cacheSize >= 0) { "cacheSize must be >= 0" }
    }
}

/**
 * Bergamot engine with lazy model loading and idle-based unloading.
 *
 * All native work runs on one dedicated thread; [translate] and
 * [translatePivot] suspend until their batch completes. Cancellation is
 * cooperative at batch granularity: a single native batch cannot be
 * interrupted (mirror of the engine's own contract).
 */
class BergamotEngine(private val config: EngineConfig = EngineConfig()) : Closeable {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "bergamot") }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var service: Long = 0
    private val models = HashMap<String, LoadedModel>()

    private class LoadedModel(val handle: Long, var lastUsedAt: Long)

    /** Translate [texts] with the direction in [model]. */
    suspend fun translate(texts: List<String>, model: ModelFiles, html: Boolean = false): List<String> =
        withContext(dispatcher) {
            val handle = acquire(model)
            try {
                NativeBridge.translate(serviceHandle(), handle, texts.toTypedArray(), html).toList()
            } finally {
                touch(model)
                unloadIdle()
            }
        }

    /**
     * Pivot translation (e.g. ja->en->zh) with both models resident — fastest,
     * but peak memory is the sum of both. For a RAM-capped sequential pivot,
     * call [translate] twice and let idle-unload reclaim the first model.
     */
    suspend fun translatePivot(
        texts: List<String>,
        first: ModelFiles,
        second: ModelFiles,
        html: Boolean = false,
    ): List<String> = withContext(dispatcher) {
        val firstHandle = acquire(first)
        val secondHandle = acquire(second)
        try {
            NativeBridge.translatePivot(serviceHandle(), firstHandle, secondHandle, texts.toTypedArray(), html)
                .toList()
        } finally {
            touch(first)
            touch(second)
            unloadIdle()
        }
    }

    /**
     * Release models regardless of idle deadline (hook for onTrimMemory).
     *
     * The work runs on the engine thread. The returned future completes when
     * the release has actually finished, and carries true when every model was
     * really destroyed — false means something was still holding a reference.
     * Callers that do not care may ignore it; a caller under memory pressure
     * that wants to know the RAM is back should wait on it.
     */
    fun releaseAllModels(): Future<Boolean> = executor.submit<Boolean> { releaseAll() }

    override fun close() {
        executor.execute {
            releaseAll()
            if (service != 0L) NativeBridge.destroyService(service)
            service = 0
        }
        executor.shutdown()
    }

    // ---- All below runs on the engine thread. ----

    private fun serviceHandle(): Long {
        if (service == 0L) {
            service = NativeBridge.createService(config.threads, config.pinToFastCores, config.cacheSize)
        }
        return service
    }

    private fun keyOf(model: ModelFiles) = model.model.absolutePath

    private fun acquire(model: ModelFiles): Long {
        serviceHandle()
        val loaded = models.getOrPut(keyOf(model)) {
            LoadedModel(
                NativeBridge.loadModel(
                    serviceHandle(),
                    model.toConfigYaml(config.workspaceMb, config.miniBatchWords),
                ),
                System.nanoTime(),
            )
        }
        loaded.lastUsedAt = System.nanoTime()
        return loaded.handle
    }

    private fun touch(model: ModelFiles) {
        models[keyOf(model)]?.lastUsedAt = System.nanoTime()
    }

    /** Returns true when every model was actually destroyed. */
    private fun releaseAll(): Boolean {
        var allDestroyed = true
        models.values.forEach { allDestroyed = NativeBridge.releaseModel(service, it.handle) && allDestroyed }
        models.clear()
        return allDestroyed
    }

    private fun unloadIdle() {
        val deadline = System.nanoTime() - config.idleUnloadMillis * 1_000_000
        val iterator = models.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastUsedAt < deadline) {
                // Safe here: this runs on the engine thread, never on an engine
                // worker, and the batch that just finished is the last one.
                NativeBridge.releaseModel(service, entry.value.handle)
                iterator.remove()
            }
        }
    }
}
