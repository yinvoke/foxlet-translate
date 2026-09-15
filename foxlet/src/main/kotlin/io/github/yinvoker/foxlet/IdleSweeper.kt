package io.github.yinvoker.foxlet

/**
 * Tracks per-model idle deadlines with one scheduled sweep for the earliest
 * deadline. [touch] updates usage; [rearm] cancels the previous task and schedules
 * a replacement. Injected clock and scheduler support deterministic JVM tests.
 *
 * All methods and scheduled callbacks must run on the model-owning thread.
 * [NativeEngine] uses its executor to serialize sweeps with translation batches.
 */
internal class IdleSweeper(
    /**
     * Idle time before a key is due. Zero means "due as soon as the batch that
     * used it is over" (a sweep is booked with no delay); negative disables
     * sweeping altogether.
     */
    private val idleMillis: Long,
    /** Monotonic nanoseconds; only differences are read. */
    private val nowNanos: () -> Long,
    private val scheduler: Scheduler,
    /**
     * Release the model identified by the supplied key, on the owning thread.
     * The sweep removes the key from its own bookkeeping before this callback,
     * so the callback only needs to release the associated model resource.
     */
    private val onIdle: (String) -> Unit,
) {

    /** Scheduler contract for execution on the model-owning thread. */
    fun interface Scheduler {
        /** Run [task] after [delayMillis], on the thread that owns the models. */
        fun schedule(delayMillis: Long, task: Runnable): Pending
    }

    /** A sweep that has been booked and has not run yet. */
    fun interface Pending {
        /** Drop the booking. Never interrupts a sweep already running. */
        fun cancel()
    }

    private val lastUsedAt = HashMap<String, Long>()
    private var pending: Pending? = null

    /** False when [idleMillis] switches automatic unloading off entirely. */
    val enabled: Boolean get() = idleMillis >= 0

    /** Keys still being watched. */
    val trackedCount: Int get() = lastUsedAt.size

    /** Updates the usage timestamp for [key]; scheduling requires a separate [rearm] call. */
    fun touch(key: String) {
        lastUsedAt[key] = nowNanos()
    }

    /** Stop watching everything and drop the pending sweep. */
    fun forgetAll() {
        lastUsedAt.clear()
        cancel()
    }

    /**
     * Book the next sweep for the earliest deadline among the tracked keys,
     * replacing whatever was booked before.
     *
     * A deadline already in the past books a sweep with no delay rather than
     * sweeping inline, so a drop always happens from the scheduler and never
     * from inside a caller's `finally`.
     */
    fun rearm() {
        cancel()
        if (!enabled) return
        val earliest = lastUsedAt.values.minOrNull() ?: return
        val idleFor = millisSince(earliest, nowNanos())
        pending = scheduler.schedule((idleMillis - idleFor).coerceAtLeast(0)) { sweep() }
    }

    /** Drop the pending sweep, if any. */
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    private fun sweep() {
        pending = null
        val now = nowNanos()
        val due = lastUsedAt.filterValues { millisSince(it, now) >= idleMillis }.keys
        lastUsedAt.keys.removeAll(due)
        due.forEach(onIdle)
        // Whatever survived is younger than the deadline by at least a
        // millisecond, so this books a real delay and never spins.
        rearm()
    }

    /**
     * Elapsed milliseconds between two [nowNanos] readings. Milliseconds, not
     * nanoseconds, because [idleMillis] may legitimately be large enough that
     * scaling it up to nanos would overflow.
     */
    private fun millisSince(then: Long, now: Long): Long = (now - then) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
