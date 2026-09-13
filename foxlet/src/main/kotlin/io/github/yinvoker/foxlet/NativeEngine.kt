package io.github.yinvoker.foxlet

import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

internal class EngineOptions(
    val threads: Int = 1,
    val idleUnloadMillis: Long = 60_000,
    val miniBatchWords: Int = 512,
    val cacheSize: Int = 0,
    val nonbreakingPrefixes: Boolean = true,
) {
    init {
        require(threads in 1..64) { "threads must be in 1..64" }
        require(miniBatchWords in 256..65536) { "miniBatchWords must be in 256..65536" }
        require(cacheSize in 0..1_000_000) { "cacheSize must be in 0..1000000" }
    }
}

/**
 * Foxlet translation engine with lazy model loading and idle-based unloading.
 *
 * All native work runs on one dedicated thread; [translate] and
 * [translatePivot] suspend until their batch completes. At
 * [EngineOptions.threads] = 1 the translation itself runs on that same thread
 * (no engine workers exist); above that the thread only submits the batch and
 * waits for the workers. Cancellation is cooperative at batch granularity: a
 * single native batch cannot be interrupted (mirror of the engine's own
 * contract).
 *
 * Models load on first use and are dropped again by a timer once they have
 * gone [EngineOptions.idleUnloadMillis] without one — the sweep is posted to
 * the same engine thread, which is what keeps it from ever landing in the
 * middle of a batch. Reloading is invisible to the caller; [loadedModelCount]
 * is there for anyone who wants to watch it happen.
 */
internal class NativeEngine(
    private val config: EngineOptions = EngineOptions(),
    private val runtime: NativeRuntime = JniRuntime,
) : Closeable, TranslationBackend {
    companion object { private val processLease = AtomicBoolean(false) }
    init { check(processLease.compareAndSet(false, true)) { "Only one NativeEngine may be open per process; close it before creating another" } }
    private val lifecycleLock = Any()
    @Volatile private var closing = false
    private var closeFuture: Future<*>? = null
    private fun checkOpen() = check(!closing) { "NativeEngine is closed" }


    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "foxlet") }.apply {
        // A sweep left in the queue by close() would call into a destroyed
        // service. close() cancels it, this is the belt to that pair of braces.
        executeExistingDelayedTasksAfterShutdownPolicy = false
        // Every translation cancels the previous sweep; without this the dead
        // bookings would sit in the queue until their original deadline.
        removeOnCancelPolicy = true
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var service: Long = 0

    /** Model key -> resident model. Read and written on the engine thread only. */
    private val models = HashMap<String, Loaded>()
    // A false native release consumes the handle but does not confirm destruction.
    // Retain disk reservations until destroying the service proves those references are gone.
    private val unconfirmedReleases = ArrayList<ModelFiles>()

    /**
     * A resident model: its native handle and the files it was loaded from.
     * The files are kept so [ActiveModels] can be told, on unload, that the
     * directory is free again — [ModelFiles] is what the guard is keyed on.
     */
    private class Loaded(val handle: Long, val files: ModelFiles)

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
     * with [EngineOptions.cacheSize] = 0: batches are a function of the list,
     * not of worker timing. The one exception is a list too short to give
     * every worker a batch (at the default mini-batch of 512 words, roughly
     * 15 lines per worker): it is split across the workers instead, so its
     * output can differ from the 1-thread output, while staying fixed for that
     * thread count. The list is what fixes the batches: the same line in a
     * different list can come out differently.
     */
    override suspend fun translate(texts: List<String>, model: ModelFiles, html: Boolean): List<String> =
        withContext(dispatcher) {
            checkOpen()
            val key = keyOf(model)
            val handle = acquire(model, key)
            try {
                currentCoroutineContext().ensureActive()
                runtime.translate(serviceHandle(), handle, texts.toTypedArray(), html).toList()
            } finally {
                if (config.idleUnloadMillis == 0L) releaseAll() else {
                    sweeper.touch(key)
                    sweeper.rearm()
                }
            }
        }

    /**
     * Pivot translation (e.g. ja->en->zh) with both models resident — fastest,
     * but peak memory is the sum of both. For a RAM-capped sequential pivot,
     * call [translate] twice and let idle-unload reclaim the first model.
     * Same output contract as [translate].
     */
    override suspend fun translatePivot(
        texts: List<String>,
        first: ModelFiles,
        second: ModelFiles,
        html: Boolean,
    ): List<String> = withContext(dispatcher) {
        checkOpen()
        // Resolve both keys before loading either model; failure cleanup must not
        // inspect an invalid/missing second bundle and prevent the idle sweep.
        val firstKey = keyOf(first)
        val secondKey = keyOf(second)
        val firstHandle = acquire(first, firstKey)
        try {
            val secondHandle = acquire(second, secondKey)
            currentCoroutineContext().ensureActive()
            runtime.translatePivot(serviceHandle(), firstHandle, secondHandle, texts.toTypedArray(), html)
                .toList()
        } finally {
            if (config.idleUnloadMillis == 0L) releaseAll() else {
                sweeper.touch(firstKey)
                sweeper.touch(secondKey)
                sweeper.rearm()
            }
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
     * drops to 0 on its own [EngineOptions.idleUnloadMillis] after the last
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
                        try { releaseAll() } finally {
                            if (service != 0L) runtime.destroyService(service)
                            service = 0
                            unconfirmedReleases.forEach(ActiveModels::release)
                            unconfirmedReleases.clear()
                        }
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

    override suspend fun state(): TranslatorState = withContext(dispatcher) {
        checkOpen()
        TranslatorState(models.size, unconfirmedReleases.size)
    }

    override suspend fun unloadModels(): UnloadReport = withContext(dispatcher) {
        checkOpen()
        val count = models.size
        val pendingBefore = unconfirmedReleases.size
        val released = releaseAll()
        UnloadReport(count - (unconfirmedReleases.size - pendingBefore), released, unconfirmedReleases.size)
    }

    // ---- All below runs on the engine thread. ----

    private fun serviceHandle(): Long {
        if (service == 0L) {
            service = runtime.createService(config.threads, config.cacheSize)
        }
        return service
    }

    private fun keyOf(model: ModelFiles) = model.cacheKey()

    /** Load [model] if it is not resident, and mark it used. */
    private suspend fun acquire(model: ModelFiles, key: String): Long {
        val context = currentCoroutineContext()
        context.ensureActive()
        val loaded = models.getOrPut(key) {
            ActiveModels.load(model) {
                model.verify()
                context.ensureActive()
                val handle = runtime.loadModel(
                    serviceHandle(),
                    model.toConfigYaml(config.miniBatchWords),
                    if (!config.nonbreakingPrefixes) null else model.nonbreakingPrefixFile?.let {
                        require(it.length() <= 1024 * 1024) { "prefix file exceeds 1 MiB" }
                        it.readBytes()
                    } ?: PrefixTables.bytesFor(model.sourceLanguage),
                )
                Loaded(handle, model)
            }
        }
        sweeper.touch(key)
        return loaded.handle
    }

    /** Returns true when every model was actually destroyed. */
    private fun releaseAll(): Boolean {
        var allDestroyed = unconfirmedReleases.isEmpty()
        models.values.forEach { loaded ->
            val destroyed = runtime.releaseModel(service, loaded.handle)
            if (destroyed) ActiveModels.release(loaded.files) else unconfirmedReleases += loaded.files
            allDestroyed = destroyed && allDestroyed
        }
        models.clear()
        sweeper.forgetAll()
        return allDestroyed
    }

    /** One model has been idle long enough. Called from a sweep. */
    private fun unload(key: String) {
        val loaded = models.remove(key) ?: return
        // Safe here: a sweep runs on the engine thread, never on an engine
        // worker, and the thread is single — no batch can be in flight.
        if (runtime.releaseModel(service, loaded.handle)) ActiveModels.release(loaded.files)
        else unconfirmedReleases += loaded.files
    }
}
