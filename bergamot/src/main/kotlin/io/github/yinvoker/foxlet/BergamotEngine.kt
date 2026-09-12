package io.github.yinvoker.foxlet

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Trusted, out-of-band SHA-256 by file name. Empty uses the bundled Mozilla catalog. */
    val expectedSha256: Map<String, String> = emptyMap(),
    /** Optional app-provided UTF-8 prefix table (also works with the no-prefix AAR). */
    val nonbreakingPrefixFile: File? = null,
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
            require(dir.isDirectory) { "not a model directory: $dir" }
            val files = requireNotNull(dir.listFiles()) { "cannot read $dir" }
            fun pick(what: String, predicate: (String) -> Boolean): File {
                val matches = files.filter { predicate(it.name) }
                require(matches.size == 1 && matches.single().isFile) { "expected one $what file in $dir" }
                return matches.single()
            }
            val model = pick("model") { it.startsWith("model.") && it.endsWith(".bin") }
            val shortlist = pick("shortlist") { it.startsWith("lex.") && it.endsWith(".bin") }
            val srcVocab = pick("vocab") { it.contains("vocab") && it.endsWith(".spm") && !it.startsWith("trg") }
            val targets = files.filter { it.name.startsWith("trgvocab") && it.name.endsWith(".spm") }
            val trgVocab = if (targets.isEmpty()) srcVocab else pick("target vocab") { it.startsWith("trgvocab") && it.endsWith(".spm") }
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

    /**
     * Engine configuration for Marian. mini-batch-words must be at least twice
     * MAX_LENGTH_BREAK; worker count is supplied separately through JNI.
     * The shortlist flag is the integer string `1` for std::stoi. Native bundle
     * loading validates shortlist bounds regardless of caller-provided flags.
     */
    internal fun toConfigYaml(miniBatchWords: Int = 512): String = """
        models:
          - ${yamlString(model.absolutePath)}
        vocabs:
          - ${yamlString(srcVocab.absolutePath)}
          - ${yamlString(trgVocab.absolutePath)}
        shortlist:
          - ${yamlString(shortlist.absolutePath)}
          - 1
        beam-size: 1
        normalize: 1.0
        word-penalty: 0
        max-length-break: $MAX_LENGTH_BREAK
        mini-batch-words: $miniBatchWords
        max-length-factor: 2.0
        skip-cost: true
        gemm-precision: int8shiftAlphaAll
        alignment: soft
        check-bytearray: true
    """.trimIndent()
    internal fun files(): List<File> = listOf(model, srcVocab, trgVocab, shortlist).distinct()

    /** Files must stay immutable while in use; downloads install complete versioned directories. */
    internal fun cacheKey(): String = (files() + listOfNotNull(nonbreakingPrefixFile)).joinToString("\u0000") {
        require(it.isFile && it.length() > 0) { "missing or empty model file: $it" }
        "${it.canonicalPath}:${it.length()}:${it.lastModified()}"
    } + "\u0000${sourceLanguage}\u0000" + expectedSha256.toSortedMap().toString()

    internal fun verify() = ModelCatalog.verify(this)

    private fun yamlString(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (c.code < 32 || c.code == 127) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

}

class EngineConfig(
    /**
     * Translation threads inside the engine (>=1).
     *
     * 1 (the default) runs the translation synchronously on the engine thread
     * — a BlockingService, with no worker dispatch. This uses the least memory
     * and is the default for interactive, sentence-at-a-time translation.
     *
     * >=2 spawns that many AsyncService workers, which translate one batch in
     * parallel, at the cost of additional memory. Batch composition is fixed
     * by the input, not worker timing. See [BergamotEngine.translate] for the
     * output contract, including short inputs and the translation cache.
     */
    val threads: Int = 1,
    /**
     * Unused compatibility parameter. The native allocator reserves workspace
     * in 128 MiB chunks per worker independently of this value.
     */
    @Deprecated(
        "No effect: the engine never reads it. Marian reserves a hard-coded 5 MB " +
            "(translation_model.cpp) which TensorAllocator rounds up to 128 MiB per " +
            "worker, so 32 and 512 behave alike. Kept for source compatibility; " +
            "drop the argument.",
        level = DeprecationLevel.WARNING,
    )
    val workspaceMb: Int = 128,
    /**
     * Unload idle models after this interval on the engine thread, between batches.
     * The next translation reloads them. Zero unloads after each batch; a negative
     * value keeps models resident until releaseAllModels or close.
     */
    val idleUnloadMillis: Long = 60_000,
    /**
     * Marian mini-batch-words. Smaller batches limit the shortlist union and output
     * layer size. Must be at least twice [ModelFiles.MAX_LENGTH_BREAK].
     */
    val miniBatchWords: Int = 512,
    /**
     * Translation cache slots; 0 disables caching. Entries are keyed by sentence
     * tokens and the loaded model's id. Collisions can change batch composition
     * and therefore output; use 0 for the [BergamotEngine.translate] output contract.
     * Unloading a model makes its entries unreachable until overwritten.
     */
    val cacheSize: Int = 0,
    /**
     * Use the sentence-splitter prefix table for [ModelFiles.sourceLanguage].
     * It keeps abbreviations such as `Dr.` from splitting a sentence. Set false
     * for pre-split input; languages without a table use the regex splitter.
     */
    val nonbreakingPrefixes: Boolean = true,
    /**
     * Thread recommendation and device inputs from [forDevice], or null for an
     * explicit configuration. Informational; the engine uses [threads].
     */
    val tuning: ThreadTuning.Decision? = null,
) {
    init {
        require(miniBatchWords >= 2 * ModelFiles.MAX_LENGTH_BREAK) {
            "miniBatchWords ($miniBatchWords) must be >= ${2 * ModelFiles.MAX_LENGTH_BREAK}: " +
                "below 2x max-length-break the engine aborts the process"
        }
        require(cacheSize in 0..1_000_000) { "cacheSize must be in 0..1000000" }
        require(threads in 1..64) { "threads must be in 1..64" }
        require(miniBatchWords <= 65536) { "miniBatchWords must be <= 65536" }
    }

    companion object {
        /**
         * Config with [threads] chosen for this device and [workload].
         * `EngineConfig()` on its own is still `threads = 1` and reads
         * nothing; auto-tiering only happens when you call this.
         *
         * Reads `ActivityManager.getMemoryInfo().totalMem`, `isLowRamDevice`
         * and [NativeBridge.fastCoreCount], then defers to
         * [ThreadTuning.recommend]. The resulting [ThreadTuning.Decision] is kept in
         * [tuning]. The engine fixes its worker count when the service is
         * created, so build a new engine to re-tier.
         *
         * To override the tier, ignore this and construct [EngineConfig]
         * directly: an explicit [threads] always wins.
         *
         * @param workspaceMb no effect, see [EngineConfig.workspaceMb]. Kept
         *   only so existing calls keep compiling; Kotlin cannot deprecate a
         *   single function parameter, hence the doc instead of an annotation.
         */
        @Suppress("DEPRECATION")
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
    companion object { private val processLease = AtomicBoolean(false) }
    init { check(processLease.compareAndSet(false, true)) { "Only one BergamotEngine may be open per process; close it before creating another" } }
    private val lifecycleLock = Any()
    @Volatile private var closing = false
    private var closeFuture: Future<*>? = null
    private fun checkOpen() = check(!closing) { "BergamotEngine is closed" }


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
     * the result is byte-identical across calls, processes and thread counts
     * with [EngineConfig.cacheSize] = 0: batches are a function of the list,
     * not of worker timing. The one exception is a list too short to give
     * every worker a batch (at the default mini-batch of 512 words, roughly
     * 15 lines per worker): it is split across the workers instead, so its
     * output can differ from the 1-thread output, while staying fixed for that
     * thread count. The list is what fixes the batches: the same line in a
     * different list can come out differently.
     */
    suspend fun translate(texts: List<String>, model: ModelFiles, html: Boolean = false): List<String> =
        withContext(dispatcher) {
            checkOpen()
            val key = keyOf(model)
            val handle = acquire(model, key)
            try {
                NativeBridge.translate(serviceHandle(), handle, texts.toTypedArray(), html).toList()
            } finally {
                sweeper.touch(key)
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
        checkOpen()
        // Resolve both keys before loading either model; failure cleanup must not
        // inspect an invalid/missing second bundle and prevent the idle sweep.
        val firstKey = keyOf(first)
        val secondKey = keyOf(second)
        val firstHandle = acquire(first, firstKey)
        try {
            val secondHandle = acquire(second, secondKey)
            NativeBridge.translatePivot(serviceHandle(), firstHandle, secondHandle, texts.toTypedArray(), html)
                .toList()
        } finally {
            sweeper.touch(firstKey)
            sweeper.touch(secondKey)
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
    fun releaseAllModels(): Future<Boolean> = synchronized(lifecycleLock) {
        checkOpen()
        executor.submit<Boolean> { releaseAll() }
    }

    /**
     * How many models are resident right now.
     *
     * Counted on the engine thread, so the future queues behind any batch in
     * flight — waiting on it from the main thread can block for the length of
     * a translation. Mostly a way to observe idle unloading from outside: it
     * drops to 0 on its own [EngineConfig.idleUnloadMillis] after the last
     * translation, and the next [translate] silently loads the model again.
     */
    fun loadedModelCount(): Future<Int> = synchronized(lifecycleLock) {
        checkOpen()
        executor.submit<Int> { models.size }
    }

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
        val future = synchronized(lifecycleLock) {
            closeFuture ?: run {
                closing = true // Queued translations must not recreate a service after teardown.
                executor.submit {
                    try {
                        releaseAll()
                        if (service != 0L) NativeBridge.destroyService(service)
                        service = 0
                    } finally {
                        executor.shutdown()
                        processLease.set(false)
                    }
                }.also { closeFuture = it }
            }
        }
        var interrupted = false
        try {
            while (true) try { future.get(); break }
            catch (_: InterruptedException) { interrupted = true }
            catch (e: ExecutionException) { throw e.cause ?: e }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    // ---- All below runs on the engine thread. ----

    private fun serviceHandle(): Long {
        if (service == 0L) {
            service = NativeBridge.createService(config.threads, config.cacheSize)
        }
        return service
    }

    private fun keyOf(model: ModelFiles) = model.cacheKey()

    /** Load [model] if it is not resident, and mark it used. */
    private fun acquire(model: ModelFiles, key: String): Long {
        val handle = models.getOrPut(key) {
            model.verify()
            NativeBridge.loadModel(
                serviceHandle(),
                model.toConfigYaml(config.miniBatchWords),
                if (!config.nonbreakingPrefixes) null else model.nonbreakingPrefixFile?.let {
                    require(it.length() <= 1024 * 1024) { "prefix file exceeds 1 MiB" }
                    it.readBytes()
                } ?: NonbreakingPrefixes.bytesFor(model.sourceLanguage),
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
