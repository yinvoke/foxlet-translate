package io.github.yinvoker.foxlet.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yinvoker.foxlet.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Mandatory final-AAR/R8 gate: no Assume, no skipped downloads or native calls. */
@RunWith(AndroidJUnit4::class)
class DeliveryTest {
    @Test fun unifiedClientDeliversTranslationAndModelManagement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "delivery-test-models")
        val pair = LanguagePair("en", "zh-Hans")
        val config = FoxletConfig(ModelsConfig(directory = root))
        var client = Foxlet.create(context, config)
        try {
            val stages = mutableListOf<DownloadStage>()
            val model = client.models.prepare(pair) { stages += it.stage }
            assertEquals(DownloadStage.CheckingLocal, stages.first())
            assertEquals(DownloadStage.Ready, stages.last())
            assertEquals(VerificationStatus.Verified, model.verificationStatus)
            assertEquals(emptyList<String>(), client.translator.translate(emptyList(), model))
            assertEquals(0, client.translator.getState().loadedModelCount)
            val result = client.translator.translate(listOf("Hello, world.", "A happy day. 😀", "A\u0000B"), model)
            assertEquals(3, result.size)
            assertTrue(result[0].isNotBlank())
            assertNotEquals("Hello, world.", result[0])
            assertEquals(client.translator.translate(listOf("Hello."), model).single(), client.translator.translate("Hello.", model))
            assertTrue(client.translator.translate("<p>Hello.</p>", model, TextFormat.Html).isNotBlank())
            try { Foxlet.create(context, config); fail("Multiple native clients accepted") } catch (_: IllegalStateException) { }
            val current = client.models.listInstalled(pair, VerificationMode.Check).single()
            assertEquals(model.id, current.id)
            assertTrue(current.isBundledVersion)
            assertEquals(VerificationStatus.Verified, client.models.verify(current).status)
            assertEquals(model.id, client.models.prepare(pair, PreparePolicy.LocalOnly).id)
            assertEquals(model.id, client.models.download(checkNotNull(model.descriptor)).id)
            try { client.models.delete(current); fail("Deleted resident model") } catch (_: ModelInUseException) { }
            val unload = client.translator.unloadModels()
            assertTrue(unload.allReleased)
            assertEquals(1, unload.unloadedModelCount)
            assertEquals(0, client.translator.getState().loadedModelCount)
            val cleaned = client.models.cleanup(keep = setOf(model.id))
            assertTrue(cleaned.failures.isEmpty())
            assertTrue(current.directory.isDirectory)

            val files = ModelFiles.fromDirectory(model.directory)
            val directory = File(context.cacheDir, "damaged-model").apply { mkdirs() }
            val bad = File(directory, "model.enzh.bad.bin").apply { writeBytes(ByteArray(16).also { it[0] = 1 }) }
            val assets = listOf(bad, files.srcVocab, files.trgVocab, files.shortlist).distinct()
            fun sha(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            val damaged = ExternalModel(pair, ModelFiles(bad, files.srcVocab, files.trgVocab, files.shortlist,
                expectedSha256 = assets.associate { it.name to sha(it) }))
            try { client.translator.translate("Hello.", damaged); fail("Damaged model accepted") } catch (_: FoxletException) { }
            directory.deleteRecursively()

            client.shutdown()
            client.shutdown()
            try { client.models.listInstalled(); fail("Closed client accepted work") } catch (_: ClientClosedException) { }
            client = Foxlet.create(context) { models { this.directory = root } }
            assertTrue(client.translator.translate("Hello, world.", model).isNotBlank())
            val report = client.models.checkUpdates(setOf(pair))
            assertTrue(report.indexUpdatedAt.toEpochMilli() > 0)
            assertEquals(model.identity, report.assessments.single().installed?.identity)
            report.updates.forEach { assertEquals(pair, it.target.pair) }
            for (name in listOf("SOURCE.txt", "NOTICE.txt", "THIRD_PARTY_NOTICES.txt")) {
                javaClass.getResourceAsStream("/io/github/yinvoker/foxlet/licenses/" + name).use { assertNotNull(it) }
            }
            assertTrue(client.translator.unloadModels().allReleased)
            val deletion = client.models.delete(current)
            assertEquals(DeleteStatus.Deleted, deletion.results.single().status)
            assertTrue(deletion.freedBytes > 0)
            assertEquals(DeleteStatus.AlreadyAbsent, client.models.delete(current).results.single().status)
            assertTrue(client.models.listInstalled(pair).isEmpty())
            assertNull(client.models.findUsable(pair))
        } finally { client.shutdown() }
    }
}
