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
 * Serializes native calls, lazy model loading and model release on a dedicated
 * executor. With one configured thread, BlockingService runs inference on the
 * executor; larger configurations submit batches to AsyncService workers.
 * Cancellation takes effect between native calls, not during a native batch.
 *
 * Positive idle retention schedules unloading on the same executor. Zero
 * releases models in the translation's finally block; negative retention keeps
 * models until explicit unloading or shutdown. Unconfirmed native releases
 * retain file reservations until the service is destroyed.
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
        // Prevent delayed sweeps from accessing a destroyed service after close().
        executeExistingDelayedTasksAfterShutdownPolicy = false
        // Remove cancelled sweeps immediately to avoid retaining obsolete tasks.
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
            // Model release on the engine thread must complete without interruption.
            IdleSweeper.Pending { scheduled.cancel(false) }
        },
        onIdle = ::unload,
    )

    /**
     * Translates [texts] in input order using [model].
     * Output regression requires fixed model bytes, input order, submission groups,
     * thread count, sentence rules, batch size and cache settings. The lexical
     * shortlist is shared within each inference batch, so changing batch composition
     * can change a sentence's output. AsyncService may reduce batch size to provide
     * work for each worker; different thread counts need not produce identical text.
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
     * Translates through [first] and [second] while both models remain resident.
     * For sequential pivot with lower model residency, use two [translate] calls
     * and await [unloadModels] between them. Output constraints match [translate].
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
     * Internal future-based release adapter. Completion follows all preceding
     * executor work; true confirms that every model was destroyed. A false result
     * retains disk reservations until service destruction. Public callers use
     * [Translator.unloadModels] and inspect [UnloadReport].
     */
    fun releaseAllModels(): Future<Boolean> = synchronized(lifecycleLock) {
        checkOpen()
        executor.submit<Boolean> { releaseAll() }
    }

    /**
     * Returns the number of resident handles after preceding executor work.
     * The count excludes unconfirmed releases; [state] reports both values.
     * Public callers use [Translator.getState].
     */
    fun loadedModelCount(): Future<Int> = synchronized(lifecycleLock) {
        checkOpen()
        executor.submit<Int> { models.size }
    }

    /**
     * Rejects subsequent translation work, releases models, destroys the native
     * service and stops the executor. Concurrent calls wait for the same teardown.
     * The process lease is released after teardown, allowing a replacement engine.
     * This blocking method runs off the Android main thread through [ClientLifecycle].
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
                    model.toNativeConfigYaml(config.miniBatchWords, config.nonbreakingPrefixes),
                    if (!config.nonbreakingPrefixes) null else model.nonbreakingPrefixFile?.let {
                        require(it.length() <= 1024 * 1024) { "prefix file exceeds 1 MiB" }
                        it.readBytes()
                    },
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
