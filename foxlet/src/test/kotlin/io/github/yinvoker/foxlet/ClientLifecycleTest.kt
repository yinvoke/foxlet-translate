package io.github.yinvoker.foxlet

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ClientLifecycleTest {
    @Test fun shutdownCancelsOwnedWorkButLeavesTheCallerScopeAlive() = runBlocking {
        val closed = AtomicInteger()
        val lifecycle = ClientLifecycle({ closed.incrementAndGet(); Unit })
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val operation = async {
            lifecycle.run {
                try { started.complete(Unit); awaitCancellation() }
                finally { finished.complete(Unit) }
            }
        }
        started.await()
        lifecycle.shutdown()
        assertTrue(finished.isCompleted)
        assertTrue(operation.isCancelled)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(1, closed.get())
        lifecycle.shutdown()
        assertEquals(1, closed.get())
        try { lifecycle.run { fail("Ran after shutdown") }; fail("Accepted work after shutdown") } catch (_: ClientClosedException) { }
    }

    @Test fun callerCancellationStopsOnlyItsOperation() = runBlocking {
        val lifecycle = ClientLifecycle({})
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val job = launch {
            lifecycle.run {
                try { started.complete(Unit); awaitCancellation() }
                finally { stopped.complete(Unit) }
            }
        }
        started.await()
        job.cancelAndJoin()
        withTimeout(2000) { stopped.await() }
        assertEquals(42, lifecycle.run { 42 })
        lifecycle.shutdown()
    }

    @Test fun shutdownWaitsForAnUninterruptibleBatchAndSurvivesCallerCancellation() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        val lifecycle = ClientLifecycle({ closed.incrementAndGet(); Unit })
        val work = async(Dispatchers.Default) {
            lifecycle.run {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val shutdown = launch { lifecycle.shutdown() }
        delay(30)
        shutdown.cancel()
        assertEquals(0, closed.get())
        assertFalse(shutdown.isCompleted)
        release.countDown()
        withTimeout(2000) { shutdown.join(); work.join() }
        assertEquals(1, closed.get())
        assertTrue(work.isCancelled)
    }

    @Test fun submitVersusShutdownNeverLeavesAnOperationRunningAfterClose() = runBlocking {
        repeat(30) {
            val running = AtomicInteger()
            val closed = AtomicInteger()
            val lifecycle = ClientLifecycle({
                assertEquals(0, running.get())
                closed.incrementAndGet()
                Unit
            })
            val jobs = List(12) {
                launch(Dispatchers.Default) {
                    try {
                        lifecycle.run {
                            assertEquals(0, closed.get())
                            running.incrementAndGet()
                            try { yield(); delay(1) } finally { running.decrementAndGet() }
                        }
                    } catch (_: ClientClosedException) { }
                }
            }
            lifecycle.shutdown()
            jobs.joinAll()
            assertEquals(1, closed.get())
        }
    }

    @Test fun alreadyCancelledCallerStillCompletesShutdown() = runBlocking {
        val closed = AtomicInteger()
        val lifecycle = ClientLifecycle({ delay(10); closed.incrementAndGet(); Unit })
        launch {
            cancel()
            lifecycle.shutdown()
        }.join()
        assertEquals(1, closed.get())
    }
}
