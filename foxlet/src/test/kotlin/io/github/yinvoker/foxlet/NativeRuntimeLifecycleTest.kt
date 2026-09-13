package io.github.yinvoker.foxlet

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeRuntimeLifecycleTest {
    private class Runtime : NativeRuntime {
        val events = CopyOnWriteArrayList<String>()
        var releasesSucceed = true
        var sequence = 0L
        val firstStarted = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        var blockFirst = false
        override fun createService(threads: Int, cacheSize: Int): Long { events += "create"; return 1 }
        override fun destroyService(service: Long) { events += "destroy" }
        override fun loadModel(service: Long, config: String, prefixes: ByteArray?): Long { events += "load"; return ++sequence }
        override fun releaseModel(service: Long, model: Long): Boolean { events += "release"; return releasesSucceed }
        override fun translate(service: Long, model: Long, texts: Array<String>, html: Boolean): Array<String> {
            events += "translate"
            if (blockFirst && firstStarted.count > 0) { firstStarted.countDown(); check(finishFirst.await(5, TimeUnit.SECONDS)) }
            return texts
        }
        override fun translatePivot(service: Long, first: Long, second: Long, texts: Array<String>, html: Boolean) =
            translate(service, first, texts, html)
    }
    private fun model(directory: File): ModelFiles {
        val names = listOf("model.enfr.bin", "vocab.enfr.spm", "lex.enfr.bin")
        directory.mkdirs()
        names.forEach { File(directory, it).writeText(it) }
        return ModelFiles.fromDirectory(directory).copy(expectedSha256 = names.associateWith { Catalog.sha256(File(directory, it)) })
    }

    @Test fun afterRequestReleasesBeforeAlreadyQueuedTranslations() = runBlocking {
        val directory = Files.createTempDirectory("foxlet-native-fake").toFile()
        val files = model(directory)
        val runtime = Runtime().apply { blockFirst = true }
        val engine = NativeEngine(EngineOptions(idleUnloadMillis = 0), runtime)
        try {
            val first = async(Dispatchers.Default) { engine.translate(listOf("one"), files) }
            assertTrue(runtime.firstStarted.await(2, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { engine.translate(listOf("two"), files) }
            delay(30)
            runtime.finishFirst.countDown()
            first.await()
            second.await()
            assertEquals(listOf("create", "load", "translate", "release", "load", "translate", "release"), runtime.events.toList())
            assertEquals(0, engine.state().loadedModelCount)
            assertFalse(ActiveModels.isActive(directory))
        } finally { runtime.finishFirst.countDown(); engine.close(); directory.deleteRecursively() }
    }

    @Test fun unconfirmedNativeDestructionKeepsDiskReservationUntilShutdown() = runBlocking {
        val directory = Files.createTempDirectory("foxlet-native-fake").toFile()
        val files = model(directory)
        val runtime = Runtime().apply { releasesSucceed = false }
        val engine = NativeEngine(runtime = runtime)
        try {
            engine.translate(listOf("one"), files)
            val report = engine.unloadModels()
            assertFalse(report.allReleased)
            assertEquals(0, report.unloadedModelCount)
            assertEquals(1, report.unconfirmedReleaseCount)
            assertEquals(1, engine.state().unconfirmedReleaseCount)
            assertTrue(ActiveModels.isActive(directory))
            assertNull(ActiveModels.ifInactive(listOf(directory)) { true })
            engine.close()
            assertEquals("destroy", runtime.events.last())
            assertFalse(ActiveModels.isActive(directory))
        } finally { engine.close(); directory.deleteRecursively() }
    }
}
