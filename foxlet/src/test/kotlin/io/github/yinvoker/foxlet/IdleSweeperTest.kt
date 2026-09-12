package io.github.yinvoker.foxlet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IdleSweeper] takes its clock and its scheduler from the caller, so the
 * whole idle policy runs here with no Android, no JNI and no sleeping: the
 * test moves time by hand and fires the armed sweep itself.
 *
 * [FoxletEngine] cannot be built in a JVM test — its constructor reaches
 * [NativeBridge] and `System.loadLibrary` — which is exactly why the policy
 * lives in its own class.
 */
class IdleSweeperTest {

    private companion object {
        const val IDLE_MILLIS = 60_000L
        const val ENZH = "/models/enzh/model.bin"
        const val JAEN = "/models/jaen/model.bin"
    }

    /** Monotonic nanoseconds the test controls. Starts off zero to catch sign mistakes. */
    private class FakeClock(var nanos: Long = 1_000_000_000L) {
        fun advance(millis: Long) {
            nanos += millis * 1_000_000L
        }
    }

    /** Records the one booking [IdleSweeper] is allowed to hold, and runs it on demand. */
    private class FakeScheduler : IdleSweeper.Scheduler {
        var armedDelayMillis: Long? = null
        private var armed: Runnable? = null
        var bookings = 0
        var cancellations = 0

        override fun schedule(delayMillis: Long, task: Runnable): IdleSweeper.Pending {
            check(armed == null) { "two sweeps armed at once" }
            bookings++
            armedDelayMillis = delayMillis
            armed = task
            return IdleSweeper.Pending {
                cancellations++
                if (armed === task) {
                    armed = null
                    armedDelayMillis = null
                }
            }
        }

        /** Fire the armed sweep, as the real executor would when the delay expires. */
        fun fire() {
            val task = requireNotNull(armed) { "nothing armed" }
            armed = null
            armedDelayMillis = null
            task.run()
        }
    }

    private val clock = FakeClock()
    private val scheduler = FakeScheduler()
    private val unloaded = mutableListOf<String>()

    private fun sweeper(idleMillis: Long = IDLE_MILLIS) =
        IdleSweeper(idleMillis, { clock.nanos }, scheduler) { unloaded += it }

    @Test
    fun `using a model arms a sweep for the full idle time`() {
        val sweeper = sweeper()
        sweeper.touch(ENZH)
        sweeper.rearm()

        assertEquals(1, scheduler.bookings)
        assertEquals(IDLE_MILLIS, scheduler.armedDelayMillis)
        assertEquals(emptyList<String>(), unloaded)
        assertEquals(1, sweeper.trackedCount)
    }

    @Test
    fun `using it again cancels the old sweep and books a fresh one`() {
        val sweeper = sweeper()
        sweeper.touch(ENZH)
        sweeper.rearm()

        clock.advance(30_000)
        sweeper.touch(ENZH)
        sweeper.rearm()

        assertEquals(1, scheduler.cancellations)
        assertEquals(2, scheduler.bookings)
        assertEquals("the deadline moved, it did not shrink", IDLE_MILLIS, scheduler.armedDelayMillis)
    }

    @Test
    fun `a sweep that arrives early unloads nothing and books the remainder`() {
        val sweeper = sweeper()
        sweeper.touch(ENZH)
        sweeper.rearm()

        // The executor is free to run the task late relative to a *newer*
        // deadline; the sweep must recompute rather than trust its booking.
        clock.advance(IDLE_MILLIS - 1_000)
        scheduler.fire()

        assertEquals(emptyList<String>(), unloaded)
        assertEquals(1, sweeper.trackedCount)
        assertEquals(1_000L, scheduler.armedDelayMillis)
    }

    @Test
    fun `the model is unloaded once the idle time has passed`() {
        val sweeper = sweeper()
        sweeper.touch(ENZH)
        sweeper.rearm()

        clock.advance(IDLE_MILLIS)
        scheduler.fire()

        assertEquals(listOf(ENZH), unloaded)
        assertEquals(0, sweeper.trackedCount)
        assertNull("nothing left to sweep", scheduler.armedDelayMillis)
    }

    @Test
    fun `two models with different idle times unload one at a time`() {
        val sweeper = sweeper()
        sweeper.touch(ENZH)
        clock.advance(30_000)
        sweeper.touch(JAEN)
        sweeper.rearm()

        // The booking follows the *earliest* deadline, which is enzh's.
        assertEquals(IDLE_MILLIS - 30_000, scheduler.armedDelayMillis)

        clock.advance(30_000)
        scheduler.fire()
        assertEquals(listOf(ENZH), unloaded)
        assertEquals(1, sweeper.trackedCount)
        assertEquals("re-armed for jaen's remaining time", 30_000L, scheduler.armedDelayMillis)

        clock.advance(30_000)
        scheduler.fire()
        assertEquals(listOf(ENZH, JAEN), unloaded)
        assertEquals(0, sweeper.trackedCount)
    }

    @Test
    fun `a deadline already in the past books a sweep with no delay`() {
        val sweeper = sweeper()
        sweeper.touch(ENZH)
        clock.advance(5 * IDLE_MILLIS) // process frozen, timers did not run
        sweeper.rearm()

        assertEquals(0L, scheduler.armedDelayMillis)
        assertEquals("still swept from the scheduler, not inline", emptyList<String>(), unloaded)

        scheduler.fire()
        assertEquals(listOf(ENZH), unloaded)
    }

    @Test
    fun `zero idle time unloads right after use`() {
        val sweeper = sweeper(0L)
        assertTrue(sweeper.enabled)
        sweeper.touch(ENZH)
        sweeper.rearm()
        assertEquals(1, scheduler.bookings)
        assertEquals(0L, scheduler.armedDelayMillis)
        scheduler.fire()
        assertEquals(listOf(ENZH), unloaded)
        assertEquals(0, sweeper.trackedCount)
    }

    @Test
    fun `a negative idle time never sweeps`() {
        for (idleMillis in listOf(-1L, Long.MIN_VALUE)) {
            unloaded.clear()
            val sweeper = sweeper(idleMillis)
            assertFalse(sweeper.enabled)
            sweeper.touch(ENZH)
            sweeper.rearm()
            clock.advance(365L * 24 * 3600 * 1000)
            sweeper.rearm()

            assertEquals("idleMillis=$idleMillis armed something", 0, scheduler.bookings)
            assertEquals(emptyList<String>(), unloaded)
            assertEquals("still tracked, just never due", 1, sweeper.trackedCount)
        }
    }

    @Test
    fun `forgetAll cancels the pending sweep and drops everything`() {
        val sweeper = sweeper()
        sweeper.touch(ENZH)
        sweeper.touch(JAEN)
        sweeper.rearm()

        sweeper.forgetAll()

        assertEquals(1, scheduler.cancellations)
        assertNull(scheduler.armedDelayMillis)
        assertEquals(0, sweeper.trackedCount)
        assertEquals("close() releases models itself, not through the sweep", emptyList<String>(), unloaded)

        // Nothing tracked, so a later re-arm must not resurrect the timer.
        sweeper.rearm()
        assertEquals(1, scheduler.bookings)
    }

    @Test
    fun `the shortest idle time still re-arms with a real delay`() {
        val sweeper = sweeper(idleMillis = 1)
        sweeper.touch(ENZH)
        sweeper.rearm()
        assertEquals(1L, scheduler.armedDelayMillis)

        clock.advance(1)
        scheduler.fire()
        assertTrue(unloaded.isNotEmpty())
        assertNull(scheduler.armedDelayMillis)
    }
}
