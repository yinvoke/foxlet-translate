package io.github.yinvoker.foxlet

/**
 * Raw JNI surface. Blocking, batch-in/batch-out; no threading or lifecycle
 * here — [NativeEngine] owns both. Handles are opaque native pointers.
 *
 * Every call for a given service handle must be made from one and the same
 * thread: at `workers <= 1` the native side is a BlockingService, which is not
 * thread-safe and does the translating on the caller's thread.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("foxlet")
    }

    /**
     * Cores that are *not* in the slowest CPU cluster — the "big core" count
     * [ThreadPlanner.recommend] wants. 0 means the topology gave no usable
     * answer (single cluster, near-uniform clusters, sysfs unreadable), not
     * "no fast cores": callers fall back to their own estimate.
     *
     * Static topology, probed once and cached natively. It does not shrink
     * when the app is pushed into a background cpuset, so it is an upper bound
     * on what is actually schedulable at any given moment.
     *
     * Free of any service handle and callable from any thread.
     */
    external fun fastCoreCount(): Int

    /**
     * Create the one service this engine will use. [workers] <= 1 selects a
     * BlockingService: every later call on this handle translates on the
     * *calling* thread, so all of them — including this one — must come from
     * the same thread. [workers] >= 2 selects an AsyncService with that many
     * worker threads. The mode is fixed for the handle's lifetime; there is no
     * second service and no second set of models.
     */
    external fun createService(workers: Int, cacheSize: Int): Long

    /** Destroy [service] and wait until its workers and native caches are gone. */
    external fun destroyService(service: Long)
    /**
     * Loads model, vocabulary and shortlist files from [configYaml].
     * [ssplitPrefix] optionally supplies an application-owned UTF-8 abbreviation
     * table. Built-in rules are compiled into the native scanner. Null or empty
     * bytes leave rule selection to `ssplit-language`, `ssplit-builtin` and any
     * `ssplit-prefix-file` option; the SDK does not set a prefix-file YAML path.
     */
    external fun loadModel(service: Long, configYaml: String, ssplitPrefix: ByteArray?): Long

    /**
     * Releases the model handle and requests removal of service-owned model
     * references and weight-packing caches. Async release waits for worker
     * acknowledgements. The handle is consumed regardless of the return value.
     *
     * Returns true only when native destruction is confirmed. On false,
     * [NativeEngine] retains file reservations until service destruction.
     */
    external fun releaseModel(service: Long, model: Long): Boolean
    /** Translate [texts] in order; the native result has exactly one item per input. */
    external fun translate(service: Long, model: Long, texts: Array<String>, html: Boolean): Array<String>

    /** Translate [texts] through [first] and then [second], keeping both models resident. */
    external fun translatePivot(
        service: Long,
        first: Long,
        second: Long,
        texts: Array<String>,
        html: Boolean,
    ): Array<String>
}
