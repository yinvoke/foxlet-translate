package io.github.yinvoker.foxlet.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yinvoker.foxlet.BergamotEngine
import io.github.yinvoker.foxlet.ModelCatalog
import io.github.yinvoker.foxlet.ModelFiles
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No Assume/skip: a device release gate must really load and translate with pinned models. */
@RunWith(AndroidJUnit4::class)
class DeliveryTest {
    @Test fun finalAarTranslatesAndRejectsDamagedModels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = ModelCatalog.download(File(context.filesDir, "delivery-test-models"), "en", "zh-Hans")
        BergamotEngine().use { engine ->
            val result = engine.translate(listOf("Hello, world.", "A happy day. 😀", "A\u0000B"), model)
            assertEquals(3, result.size)
            assertTrue(result[0].isNotBlank())
            assertNotEquals("Hello, world.", result[0])
            assertTrue(engine.releaseAllModels().get())
            assertEquals(0, engine.loadedModelCount().get())
        }
        // Even with caller-pinned hashes, malformed model structure must fail as a Java error.
        val directory = File(context.cacheDir, "damaged-model").apply { mkdirs() }
        val bad = File(directory, "model.enzh.bad.bin").apply { writeBytes(ByteArray(16).also { it[0] = 1 }) }
        val assets = listOf(bad, model.srcVocab, model.trgVocab, model.shortlist).distinct()
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val damaged = ModelFiles(bad, model.srcVocab, model.trgVocab, model.shortlist,
            expectedSha256 = assets.associate { it.name to sha(it) })
        BergamotEngine().use { engine ->
            try { engine.translate(listOf("Hello."), damaged); fail("damaged model accepted") }
            catch (_: RuntimeException) { /* required recoverable failure */ }
        }
        BergamotEngine().use { assertTrue(it.translate(listOf("Hello, world."), model).single().isNotBlank()) }
        for (name in listOf("SOURCE.txt", "NOTICE.txt", "THIRD_PARTY_NOTICES.txt")) {
            assertNotNull(javaClass.getResourceAsStream("/io/github/yinvoker/foxlet/licenses/$name"))
        }
    }
}
