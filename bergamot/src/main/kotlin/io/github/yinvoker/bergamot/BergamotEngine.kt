package io.github.yinvoker.bergamot

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Local files of one translation direction (Mozilla student model layout). */
data class ModelFiles(
    val model: File,
    val srcVocab: File,
    val trgVocab: File,
    val shortlist: File,
    /**
     * Language the source text is in, used to pick the sentence-splitter prefix
     * table ([NonbreakingPrefixes]). [fromDirectory] reads it off the model file
     * name; set it by hand to override that, or to null to split with the bare
     * regex. A language with no table behaves like null.
     *
     * It says nothing about what the model can translate -- the engine never
     * sees this value, only the bytes it selects.
     */
    val sourceLanguage: String? = null,
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
            return ModelFiles(model, srcVocab, trgVocab, shortlist, sourceLanguageOf(model.name))
        }

        /**
         * Source language of a Mozilla model file name, or null when the name
         * does not follow the convention.
         *
         * Mozilla names every direction `model.<src><trg>.<...>.bin` with two
         * ISO 639-1 letters a side: `model.enzh.intgemm.alphas.bin` is en->zh,
         * `model.jaen.intgemm.alphas.bin` is ja->en. Script variants collapse in
         * the file name (zh-Hans and zh-Hant are both `zh`), which is what the
         * prefix tables want anyway.
         *
         * Four lowercase letters after `model.` is all this has to go on, so a
         * name that merely looks like the convention yields a two-letter tag that
         * is not a real language. Harmless: an unknown tag has no table and the
         * splitter falls back to the regex.
         */
        fun sourceLanguageOf(modelFileName: String): String? =
            MODEL_NAME.matchEntire(modelFileName)?.groupValues?.get(1)

        private val MODEL_NAME = Regex("""model\.([a-z]{2})([a-z]{2})\..+\.bin""")
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
    /**
     * Translation threads inside the engine (>=1).
     *
     * 1 (the default) runs the translation synchronously on the engine thread
     * — a BlockingService, no dispatch to workers at all. Cheapest and the only
     * reproducible setting: output is byte-identical across processes, memory
     * is the lowest of any thread count, and the per-batch dispatch cost is
     * gone. The right choice for interactive, sentence-at-a-time translation.
     *
     * >=2 spawns that many AsyncService workers, which translate one batch in
     * parallel. Worth it for bulk batches; the cost is that batch composition
     * now depends on worker timing, so the output of a given sentence can
     * differ between runs of the same input.
     */
    val threads: Int = 1,
    /**
     * Marian workspace per replica, MB — one replica per worker, so exactly one
     * at [threads] = 1. Smaller = less RAM, may cost speed.
     */
    val workspaceMb: Int = 128,
    /**
     * Unload a model after this long without use.
     *
     * A timer on the engine thread does it, so the model goes away on its own
     * once translation stops — no further call is needed to trigger the
     * reclaim, and the sweep can never interleave with a batch. The next
     * [BergamotEngine.translate] reloads transparently; the reload costs
     * roughly 140 ms plus ~110 ms of extra latency on the sentence that
     * triggers it (en->zh, Mi 12), against ~103 MB of RSS held while resident.
     *
     * Zero keeps its old meaning: the model is dropped right after the batch
     * that used it (for a RAM-capped sequential pivot, say). A negative value
     * switches automatic unloading off: models then stay resident until
     * [BergamotEngine.releaseAllModels] or [BergamotEngine.close] — the right
     * setting for a benchmark, and the wrong one for an app that translates in
     * bursts.
     */
    val idleUnloadMillis: Long = 60_000,
    /**
     * Marian mini-batch-words. 512 beats 1024 across every worker tier
     * (device-controlled A/B: never slower, up to -23% at 4 workers, slightly
     * less RAM) because a smaller batch keeps the shortlist union — and with
     * it the output layer — narrow.
     */
    val miniBatchWords: Int = 512,
    /**
     * Translation cache slots, 0 = off. Zero-cost on miss; a repeated sentence
     * hits at ~40-50x (a fully cached 200-line pass takes ~60 ms on a Mi 10
     * against ~4 s cold). Off by default, for two reasons.
     *
     * A hit is not always byte-identical to a cold translation. The cache is a
     * direct-mapped table keyed by the sentence's tokens: when two sentences
     * land in the same slot the later one evicts the earlier, and the evicted
     * sentence is re-translated on its own the next time — in a different
     * batch, whose shortlist and length shape the output. Measured on 200
     * FLORES lines (212 sentences): 1 line differs at 20000 slots, 3 at 2048,
     * 5 at 512, none at 100000+; identical across host and devices. Both
     * versions are valid translations, so it only matters to a host that
     * expects re-translating the same text to give the same bytes. That
     * guarantee holds with the cache off (see [BergamotEngine.translate]).
     *
     * The cache lives on the native service and is keyed by a per-load model
     * id, so every idle unload ([idleUnloadMillis]) orphans the entries of the
     * reloaded model: they stop hitting but keep their memory until
     * overwritten. Enable the cache where repeats recur within the idle window
     * or with idleUnloadMillis < 0; suggested 4096-16384 slots. This is a
     * cache, not deduplication — every input line gets its own output either
     * way.
     */
    val cacheSize: Int = 0,
    /**
     * Give the sentence splitter the prefix table for the model's
     * [ModelFiles.sourceLanguage] (on by default).
     *
     * Without it the splitter is a bare regex that ends a sentence at every
     * `.`, so `Dr. Smith arrived.` is translated as two fragments instead of
     * one sentence. The table costs a few hundred microseconds at model load
     * and nothing per translation.
     *
     * Set it to false to reproduce output from before this existed, or when the
     * host has already split its input into single sentences. A model whose
     * source language has no table is unaffected either way.
     */
    val nonbreakingPrefixes: Boolean = true,
    /**
     * Why [threads] is what it is, when it came from [forDevice]. null for a
     * hand-written config — nothing in the engine reads this field; it exists
     * so hosts and benchmarks can record (and second-guess) the tiering.
     */
    val tuning: ThreadTuning.Decision? = null,
) {
    init {
        require(miniBatchWords >= 2 * ModelFiles.MAX_LENGTH_BREAK) {
            "miniBatchWords ($miniBatchWords) must be >= ${2 * ModelFiles.MAX_LENGTH_BREAK}: " +
                "below 2x max-length-break the engine aborts the process"
        }
        require(cacheSize >= 0) { "cacheSize must be >= 0" }
    }

    companion object {
        /**
         * Config with [threads] chosen for this device and [workload].
         * `EngineConfig()` on its own is still `threads = 1` and reads
         * nothing; auto-tiering only happens when you call this.
         *
         * Reads `ActivityManager.getMemoryInfo().totalMem`, `isLowRamDevice`
         * and [NativeBridge.fastCoreCount], then defers to
         * [ThreadTuning.recommend]. The resulting [Decision] is kept in
         * [tuning]. The engine fixes its worker count when the service is
         * created, so build a new engine to re-tier.
         *
         * To override the tier, ignore this and construct [EngineConfig]
         * directly: an explicit [threads] always wins.
         */
        fun forDevice(
            context: Context,
            workload: Workload,
            workspaceMb: Int = 128,
            idleUnloadMillis: Long = 60_000,
            miniBatchWords: Int = 512,
            cacheSize: Int = 0,
            nonbreakingPrefixes: Boolean = true,
        ): EngineConfig {
            val decision = ThreadTuning.forDevice(context, workload)
            return EngineConfig(
                threads = decision.threads,
                workspaceMb = workspaceMb,
                idleUnloadMillis = idleUnloadMillis,
                miniBatchWords = miniBatchWords,
                cacheSize = cacheSize,
                nonbreakingPrefixes = nonbreakingPrefixes,
                tuning = decision,
            )
        }
    }
}

/**
 * Bergamot engine with lazy model loading and idle-based unloading.
 *
 * All native work runs on one dedicated thread; [translate] and
 * [translatePivot] suspend until their batch completes. At
 * [EngineConfig.threads] = 1 the translation itself runs on that same thread
 * (no engine workers exist); above that the thread only submits the batch and
 * waits for the workers. Cancellation is cooperative at batch granularity: a
 * single native batch cannot be interrupted (mirror of the engine's own
 * contract).
 *
 * Models load on first use and are dropped again by a timer once they have
 * gone [EngineConfig.idleUnloadMillis] without one — the sweep is posted to
 * the same engine thread, which is what keeps it from ever landing in the
 * middle of a batch. Reloading is invisible to the caller; [loadedModelCount]
 * is there for anyone who wants to watch it happen.
 */
class BergamotEngine(private val config: EngineConfig = EngineConfig()) : Closeable {

    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "bergamot") }.apply {
        // A sweep left in the queue by close() would call into a destroyed
        // service. close() cancels it, this is the belt to that pair of braces.
        executeExistingDelayedTasksAfterShutdownPolicy = false
        // Every translation cancels the previous sweep; without this the dead
        // bookings would sit in the queue until their original deadline.
        removeOnCancelPolicy = true
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var service: Long = 0

    /** Model key -> native handle. Read and written on the engine thread only. */
    private val models = HashMap<String, Long>()

    private val sweeper = IdleSweeper(
        idleMillis = config.idleUnloadMillis,
        nowNanos = System::nanoTime,
        scheduler = { delayMillis, task ->
            val scheduled = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
            // false: a sweep already running is on the engine thread and owns
            // the models; interrupting it mid-release is never what we want.
            IdleSweeper.Pending { scheduled.cancel(false) }
        },
        onIdle = ::unload,
    )

    /**
     * Translate [texts] with the direction in [model].
     *
     * One output per input, in order; a line that was translated before is
     * translated again — the engine keeps no "already seen" state (a document
     * host that wants to skip unchanged nodes tracks that itself, see the
     * README). Given the same list, the same model files and the same config,
     * the result is byte-identical across calls and processes at
     * [EngineConfig.threads] = 1 with [EngineConfig.cacheSize] = 0. The list is
     * what fixes the batches: the same line in a different list can come out
     * differently.
     */
    suspend fun translate(texts: List<String>, model: ModelFiles, html: Boolean = false): List<String> =
        withContext(dispatcher) {
            val handle = acquire(model)
            try {
                NativeBridge.translate(serviceHandle(), handle, texts.toTypedArray(), html).toList()
            } finally {
                sweeper.touch(keyOf(model))
                sweeper.rearm()
            }
        }

    /**
     * Pivot translation (e.g. ja->en->zh) with both models resident — fastest,
     * but peak memory is the sum of both. For a RAM-capped sequential pivot,
     * call [translate] twice and let idle-unload reclaim the first model.
     * Same output contract as [translate].
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
            sweeper.touch(keyOf(first))
            sweeper.touch(keyOf(second))
            sweeper.rearm()
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

    /**
     * How many models are resident right now.
     *
     * Counted on the engine thread, so the future queues behind any batch in
     * flight — waiting on it from the main thread can block for the length of
     * a translation. Mostly a way to observe idle unloading from outside: it
     * drops to 0 on its own [EngineConfig.idleUnloadMillis] after the last
     * translation, and the next [translate] silently loads the model again.
     */
    fun loadedModelCount(): Future<Int> = executor.submit<Int> { models.size }

    /**
     * Release every model, cancel the pending idle sweep, destroy the native
     * service and stop the engine thread. Blocks until the native side is
     * actually gone (bounded by one in-flight batch), so a caller may create
     * the next engine right after —
     * marian keeps process-global state (its logger registry among it) and a
     * second service created while the first is still being torn down fails.
     * Call it off the main thread when a batch may still be running.
     */
    override fun close() {
        try {
            executor.submit {
                releaseAll()
                if (service != 0L) NativeBridge.destroyService(service)
                service = 0
            }.get()
        } catch (e: RejectedExecutionException) {
            // Already closed.
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            executor.shutdown()
        }
    }

    // ---- All below runs on the engine thread. ----

    private fun serviceHandle(): Long {
        if (service == 0L) {
            service = NativeBridge.createService(config.threads, config.cacheSize)
        }
        return service
    }

    private fun keyOf(model: ModelFiles) = model.model.absolutePath

    /** Load [model] if it is not resident, and mark it used. */
    private fun acquire(model: ModelFiles): Long {
        val key = keyOf(model)
        val handle = models.getOrPut(key) {
            NativeBridge.loadModel(
                serviceHandle(),
                model.toConfigYaml(config.workspaceMb, config.miniBatchWords),
                if (config.nonbreakingPrefixes) NonbreakingPrefixes.bytesFor(model.sourceLanguage) else null,
            )
        }
        sweeper.touch(key)
        return handle
    }

    /** Returns true when every model was actually destroyed. */
    private fun releaseAll(): Boolean {
        var allDestroyed = true
        models.values.forEach { allDestroyed = NativeBridge.releaseModel(service, it) && allDestroyed }
        models.clear()
        sweeper.forgetAll()
        return allDestroyed
    }

    /** One model has been idle long enough. Called from a sweep. */
    private fun unload(key: String) {
        val handle = models.remove(key) ?: return
        // Safe here: a sweep runs on the engine thread, never on an engine
        // worker, and the thread is single — no batch can be in flight.
        NativeBridge.releaseModel(service, handle)
    }
}
