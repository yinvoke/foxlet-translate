package io.github.yinvoker.foxlet

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/** Owns only SDK operation children. Caller jobs are never cancelled by shutdown. */
internal class ClientLifecycle(
    private val closeBackend: suspend () -> Unit,
    context: CoroutineContext = Dispatchers.IO,
) {
    private val lock = Any()
    private val operations = SupervisorJob()
    private val scope = CoroutineScope(context + operations)
    private var closing: Deferred<Unit>? = null
    private var closed = false

    suspend fun <T> run(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val task = synchronized(lock) {
            if (closed) throw ClientClosedException()
            // Register the lazy child before releasing the lock, so shutdown cannot miss it.
            scope.async(start = CoroutineStart.LAZY) { block() }.also { it.start() }
        }
        return try { task.await() } finally {
            // Await cancellation does not automatically cancel an independently parented child.
            if (!task.isCompleted) task.cancel()
        }
    }

    suspend fun shutdown() {
        val termination = synchronized(lock) {
            closing ?: kotlin.run {
                closed = true
                CoroutineScope(Dispatchers.IO).async(start = CoroutineStart.LAZY) {
                    try { operations.cancelAndJoin() } finally { closeBackend() }
                }.also { closing = it; it.start() }
            }
        }
        // Finish teardown even if the caller was already cancelled or is cancelled while waiting.
        withContext(NonCancellable) { termination.await() }
    }
}
