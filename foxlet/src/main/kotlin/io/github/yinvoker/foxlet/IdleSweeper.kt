package io.github.yinvoker.foxlet

/**
 * When a resident model has gone unused long enough to drop, and the single
 * timer that wakes up to drop it.
 *
 * Deliberately free of Android, JNI and any real clock: the clock and the
 * scheduler are constructor parameters, so the whole policy — the deadline
 * arithmetic and the re-arming both — is exercisable from a JVM unit test.
 * [NativeEngine] supplies the real pair (`System.nanoTime` and its own
 * engine thread).
 *
 * Exactly one sweep is armed at a time, for the earliest deadline among the
 * tracked keys. A key used again just moves its deadline; the next [rearm]
 * cancels the outstanding sweep and books the new one.
 *
 * Not thread-safe, and does not try to be. Every method, and the sweep the
 * scheduler runs, must happen on the one thread that owns the models — that
 * confinement is what makes a sweep unable to interleave with a batch.
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

    /** The only thing the sweeper needs from an executor. */
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

    /** Record that [key] is in use as of now. Does not arm anything; [rearm] does. */
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
