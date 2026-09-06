package io.github.yinvoker.bergamot

/**
 * Raw JNI surface. Blocking, batch-in/batch-out; no threading or lifecycle
 * here — [BergamotEngine] owns both. Handles are opaque native pointers.
 *
 * Every call for a given service handle must be made from one and the same
 * thread: at `workers <= 1` the native side is a BlockingService, which is not
 * thread-safe and does the translating on the caller's thread.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("bergamot")
    }

    /**
     * Cores that are *not* in the slowest CPU cluster — the "big core" count
     * [ThreadTuning.recommend] wants. 0 means the topology gave no usable
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
    external fun destroyService(service: Long)
    /**
     * Load a model into [service]. Everything the engine needs comes from
     * [configYaml] as file paths, except [ssplitPrefix]: the sentence-splitter
     * prefix table travels as bytes because it ships inside the AAR rather than
     * on the filesystem. null (or empty) means no table, and the splitter falls
     * back to its regex.
     */
    external fun loadModel(service: Long, configYaml: String, ssplitPrefix: ByteArray?): Long

    /**
     * Hand a model back to [service] and reclaim what it holds on the model's
     * behalf: the aggregate queue's reference, the GEMM weight-packing caches,
     * and under async each worker's cached reference and last batch. Just
     * dropping the handle frees none of those until the service itself dies.
     *
     * Under async this blocks until every worker acknowledges (bounded by one
     * in-flight batch); under blocking there is nothing in flight to wait for.
     * Returns true when the model was actually destroyed; false means a
     * request was still in flight and it dies when that finishes.
     */
    external fun releaseModel(service: Long, model: Long): Boolean
    external fun translate(service: Long, model: Long, texts: Array<String>, html: Boolean): Array<String>
    external fun translatePivot(
        service: Long,
        first: Long,
        second: Long,
        texts: Array<String>,
        html: Boolean,
    ): Array<String>
}
