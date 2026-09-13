package io.github.yinvoker.foxlet

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UnifiedApiTest {
    private lateinit var directory: File
    private lateinit var root: File
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val payloads = mutableMapOf<String, ByteArray>()
    private val clients = mutableListOf<Foxlet>()
    private var handler: ((HttpExchange) -> Boolean)? = null
    private val pair = LanguagePair("en", "fr")
    private val source = ModelSource("https://index.example/changeset", "https://cdn.example/", setOf("cdn.example"), "UnifiedApiTest/1")
    private class Backend : TranslationBackend {
        var calls = 0
        var closed = false
        override suspend fun translate(texts: List<String>, model: ModelFiles, html: Boolean): List<String> {
            calls++
            return texts.map { "translated:" + it }
        }
        override suspend fun translatePivot(texts: List<String>, first: ModelFiles, second: ModelFiles, html: Boolean) =
            translate(texts, first, html)
        override suspend fun state() = TranslatorState(0)
        override suspend fun unloadModels() = UnloadReport(0, true)
        override fun close() { closed = true }
    }

    @Before fun setup() {
        directory = Files.createTempDirectory("foxlet-api").toFile()
        root = File(directory, "models")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            assertEquals("UnifiedApiTest/1", exchange.requestHeaders.getFirst("User-Agent"))
            try {
                if (handler?.invoke(exchange) != true) {
                    val body = payloads[exchange.requestURI.path]
                    if (body == null) exchange.sendResponseHeaders(404, -1)
                    else { exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body) }
                }
            } finally { exchange.close() }
        }
        server.start()
    }

    @After fun cleanup() {
        runBlocking { clients.forEach { it.shutdown() } }
        server.stop(0)
        ActiveModels.clearForTests()
        directory.deleteRecursively()
    }

    private fun client(backend: Backend = Backend(), location: File = root): Foxlet {
        val config = FoxletConfig(ModelsConfig(location, source))
        val transport = ModelTransport { url ->
            URL("http", "127.0.0.1", server.address.port, url.file).openConnection() as HttpURLConnection
        }
        return Foxlet.assemble(config, ThreadingInfo(Threading.Fixed(1), 1), backend, transport).also { clients += it }
    }

    private fun descriptor(version: String = "2.9", salt: Int = 0): ModelDescriptor {
        val names = listOf("model.enfr.bin", "vocab.enfr.spm", "lex.enfr.bin")
        return ModelDescriptor(pair, version, names.mapIndexed { index, name ->
            val bytes = ByteArray(256 + index) { (it + index + salt).toByte() }
            payloads["/" + name] = bytes
            ModelAsset(name, bytes.size.toLong(), Catalog.digest(bytes), "https://cdn.example/" + name)
        })
    }

    private suspend inline fun <reified T : Throwable> expect(noinline action: suspend () -> Unit): T {
        try { action() } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected " + T::class.java.name, e)
        }
        throw AssertionError("Expected " + T::class.java.name)
    }

    @Test fun configurationAndLanguageTagsHaveOneValidatedRepresentation() {
        val objectConfig = FoxletConfig(ModelsConfig(root, source), TranslationConfig(Threading.Auto(Workload.BATCH)))
        val dsl = FoxletConfigBuilder().apply {
            models { directory = root; this.source = this@UnifiedApiTest.source }
            translation { threading = Threading.Auto(Workload.BATCH) }
        }.build()
        assertEquals(objectConfig, dsl)
        assertEquals(LanguagePair("EN", "zh-hANT"), LanguagePair("en", "zh-Hant"))
        assertNotEquals(LanguagePair("en", "zh"), LanguagePair("en", "zh-Hans"))
        assertThrows(IllegalArgumentException::class.java) { NetworkOptions(readTimeout = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { NetworkOptions(readTimeout = Duration.INFINITE) }
        assertThrows(IllegalArgumentException::class.java) { Threading.Fixed(0) }
        assertThrows(IllegalArgumentException::class.java) { TranslationConfig(miniBatchWords = 255) }
        assertThrows(IllegalArgumentException::class.java) { DownloadOptions(maxRetries = 21) }
        assertThrows(IllegalArgumentException::class.java) { ModelRetention.Idle(Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { ModelSource("http://index.example", "https://cdn.example/", setOf("cdn.example")) }
    }

    @Test fun descriptorSortsAndCopiesAssetsWithoutChangingExistingContentIdentity() {
        val model = descriptor()
        val mutable = model.assets.reversed().toMutableList()
        val copy = ModelDescriptor(pair, model.version, mutable)
        mutable.clear()
        assertEquals(model, copy)
        assertEquals(model.asCatalog().identity, copy.identity)
        assertThrows(UnsupportedOperationException::class.java) { (copy.assets as MutableList).clear() }
    }

    @Test fun queriesAndLocalOnlyNeverCreateAStoreOrUseNetwork() = runBlocking {
        val client = client()
        assertTrue(client.models.listInstalled().isEmpty())
        assertNull(client.models.findUsable(pair))
        assertNull(client.models.findBundled(LanguagePair("xx", "yy")))
        expect<ModelNotInstalledException> { client.models.prepare(pair, PreparePolicy.LocalOnly) }
        expect<UnsupportedLanguagePairException> { client.models.prepare(LanguagePair("xx", "yy")) }
        assertFalse(root.exists())
        assertTrue(requests.isEmpty())
    }

    @Test fun bundledDescriptorsUseTheConfiguredAttachmentMirrorWithoutChangingIdentity() = runBlocking {
        val selected = checkNotNull(client().models.findBundled(pair))
        assertTrue(selected.assets.all { it.url.startsWith(source.attachmentBaseUrl) })
        assertEquals(Catalog.find(pair.source, pair.target).identity, selected.identity)
        assertTrue(requests.isEmpty())
    }

    @Test fun unsupportedModelMajorFailsBeforeAnyDownload() = runBlocking {
        expect<IllegalArgumentException> { client().models.download(descriptor("3.0")) }
        assertTrue(requests.isEmpty())
        assertFalse(root.exists())
    }

    @Test fun downloadPrepareTranslateAndDeleteUseTheSameInstallation() = runBlocking {
        val backend = Backend()
        val client = client(backend)
        val descriptor = descriptor()
        val progress = mutableListOf<DownloadProgress>()
        val model = client.models.download(descriptor) { progress += it }
        assertEquals(VerificationStatus.Verified, model.verificationStatus)
        assertEquals(DownloadStage.CheckingLocal, progress.first().stage)
        assertEquals(DownloadStage.Ready, progress.last().stage)
        assertTrue(progress.any { it.stage == DownloadStage.Verifying })
        assertEquals(descriptor.sizeBytes, progress.last().downloadedBytes)
        assertEquals(3, requests.size)
        val ready = client.models.prepare(pair)
        assertEquals(model.id, ready.id)
        assertEquals(3, requests.size)
        assertEquals("translated:hi", client.translator.translate("hi", model))
        assertEquals(listOf("translated:one", "translated:two"), client.translator.translate(listOf("one", "two"), ready))
        val calls = backend.calls
        assertTrue(client.translator.translate(emptyList(), model).isEmpty())
        assertEquals(calls, backend.calls)
        val deleted = client.models.delete(model)
        assertEquals(DeleteStatus.Deleted, deleted.results.single().status)
        assertTrue(deleted.freedBytes >= descriptor.sizeBytes)
        assertEquals(DeleteStatus.AlreadyAbsent, client.models.delete(model).results.single().status)
        assertNull(client.models.findUsable(pair))
    }

    @Test fun verificationSeparatesUncheckedInvalidAndUnverifiable() = runBlocking {
        val client = client()
        val model = client.models.download(descriptor())
        assertEquals(VerificationStatus.NotChecked, client.models.listInstalled().single().verificationStatus)
        File(model.directory, "vocab.enfr.spm").appendText("bad")
        val report = client.models.verify(model)
        assertEquals(VerificationStatus.Invalid, report.status)
        assertEquals(listOf("vocab.enfr.spm"), report.assets.filter { it.status == VerificationStatus.Invalid }.map { it.assetName })
        assertNull(client.models.findUsable(pair))
        File(model.directory, ModelManifest.FILE_NAME).delete()
        val unknown = client.models.listInstalled(verification = VerificationMode.Check).single()
        assertNull(unknown.descriptor)
        assertEquals(VerificationStatus.Unverifiable, unknown.verificationStatus)
    }

    @Test fun damagedNewestFallsBackAndRepairHasDistinctInstallationId() = runBlocking {
        val client = client()
        val old = client.models.download(descriptor("2.8"))
        val newerDescriptor = descriptor("2.9", 1)
        val newer = client.models.download(newerDescriptor)
        File(newer.directory, "model.enfr.bin").appendText("bad")
        assertEquals(old.id, client.models.findUsable(pair)?.id)
        val repaired = client.models.download(newerDescriptor)
        assertNotEquals(newer.id, repaired.id)
        assertEquals(newer.identity, repaired.identity)
        assertEquals(repaired.id, client.models.prepare(pair).id)
        client.models.cleanup(keep = setOf(old.id, repaired.id))
        assertTrue(old.directory.exists())
        assertTrue(repaired.directory.exists())
    }

    @Test fun foreignStoreAndActiveModelsCannotBeDeleted() = runBlocking {
        val client = client()
        val first = client.models.download(descriptor("2.8"))
        val second = client.models.download(descriptor("2.9"))
        val other = client(location = File(directory, "other"))
        expect<IllegalArgumentException> { other.models.delete(first) }
        val files = second.record.files()
        ActiveModels.acquire(files)
        try {
            expect<ModelInUseException> { client.models.deleteAll(pair) }
            assertTrue(first.directory.exists())
            assertTrue(second.directory.exists())
        } finally { ActiveModels.release(files) }
        assertEquals(2, client.models.deleteAll(pair).results.size)
    }

    @Test fun unreadableStoreIsNotReportedAsEmptyInventory() = runBlocking {
        root.writeText("a file is not a model store")
        expect<ModelStorageException> { client().models.listInstalled() }
        Unit
    }

    @Test fun pivotDirectionsAndClosedStateAreEnforcedAtTheUnifiedBoundary() = runBlocking {
        val client = client()
        val model = client.models.download(descriptor())
        expect<IllegalArgumentException> { client.translator.translatePivot("hi", model, model) }
        client.shutdown()
        expect<ClientClosedException> { client.models.listBundled() }
        expect<ClientClosedException> { client.models.prepare(pair) }
        expect<ClientClosedException> { client.models.checkUpdates() }
        expect<ClientClosedException> { client.models.cleanup() }
        expect<ClientClosedException> { client.translator.translate(emptyList(), model) }
        expect<ClientClosedException> { client.translator.getState() }
        expect<ClientClosedException> { client.translator.unloadModels() }
        Unit
    }

    @Test fun partialDeletionReportsOnlyFreedBytesAndCleanupReclaimsTheRemainder() = runBlocking {
        val client = client()
        val model = client.models.download(descriptor())
        val protectedDirectory = File(model.directory, "protected").apply { mkdir() }
        val held = File(protectedDirectory, "held").apply { writeText("retained") }
        val readonly = java.nio.file.attribute.PosixFilePermissions.fromString("r-x------")
        val writable = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(protectedDirectory.toPath(), readonly)
        try {
            val report = client.models.delete(model)
            val outcome = report.results.single()
            assertEquals(DeleteStatus.PartiallyDeleted, outcome.status)
            assertTrue(outcome.freedBytes > 0)
            assertNotNull(outcome.failure)
            assertFalse(model.directory.exists())
            val trash = root.listFiles()!!.single { it.name.startsWith(".trash-") }
            assertFalse(held.exists()) // The old path was atomically unpublished.
            Files.setPosixFilePermissions(File(trash, "protected").toPath(), writable)
            val cleaned = client.models.cleanup()
            assertTrue(cleaned.failures.isEmpty())
            assertEquals("retained".toByteArray().size.toLong(), cleaned.freedBytes)
            assertFalse(trash.exists())
        } finally {
            root.walkTopDown().filter { it.isDirectory && it.name == "protected" }
                .forEach { Files.setPosixFilePermissions(it.toPath(), writable) }
        }
    }

    @Test fun indexPerCallReadTimeoutOverridesTheClientDefault() = runBlocking {
        handler = { exchange ->
            if (exchange.requestURI.path == "/changeset") {
                Thread.sleep(300)
                exchange.sendResponseHeaders(503, -1)
                true
            } else false
        }
        val error = expect<NetworkException> {
            client().models.checkUpdates(options = UpdateOptions(networkOptions = NetworkOptions(readTimeout = 30.milliseconds)))
        }
        assertNull(error.httpStatus)
        assertTrue(error.cause is java.net.SocketTimeoutException)
        assertEquals(1, requests.size)
    }

    @Test fun callbackIOExceptionIsNeverRetriedOrWrapped() = runBlocking {
        val client = client()
        val failure = IOException("observer failed")
        val thrown = expect<IOException> {
            client.models.download(descriptor()) { if (it.stage == DownloadStage.Downloading) throw failure }
        }
        // Coroutine stack recovery may reconstruct IOException while keeping the original as its cause.
        assertEquals(failure.message, thrown.message)
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
        assertTrue(requests.isEmpty())
        assertTrue(client.models.listInstalled().isEmpty())
    }

    @Test fun indexDefaultsToNoRetryAndExplicitRetryReturnsExactRepackagedTarget() = runBlocking {
        val client = client()
        val installed = client.models.download(descriptor())
        val target = descriptor(salt = 3)
        val records = JSONArray()
        target.assets.forEach { asset ->
            records.put(JSONObject().put("id", asset.name).put("name", asset.name)
                .put("fromLang", pair.source).put("toLang", pair.target).put("version", target.version)
                .put("fileType", when { asset.name.startsWith("model.") -> "model"; asset.name.startsWith("lex.") -> "lex"; else -> "vocab" })
                .put("last_modified", 10L).put("attachment", JSONObject().put("size", asset.sizeBytes).put("hash", asset.sha256).put("location", asset.name)))
        }
        val body = JSONObject().put("timestamp", 10L).put("changes", records).toString().toByteArray()
        var tries = 0
        handler = { exchange ->
            if (exchange.requestURI.path == "/changeset") {
                tries++
                if (tries <= 2) exchange.sendResponseHeaders(503, -1)
                else { exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body) }
                true
            } else false
        }
        val failure = expect<NetworkException> { client.models.checkUpdates(setOf(pair)) }
        assertEquals(503, failure.httpStatus)
        assertEquals(1, failure.attempt)
        assertEquals(1, tries)
        val report = client.models.checkUpdates(setOf(pair), UpdateOptions(1, Duration.ZERO, Duration.ZERO))
        assertEquals(3, tries)
        val update = report.updates.single()
        assertEquals(installed.id, update.installed.id)
        assertEquals(target.identity, update.target.identity)
        assertEquals(UpdateReason.Repackaged, update.reason)
        assertEquals(UpdateState.UpdateAvailable, report.assessments.single().state)
        assertEquals(VerificationStatus.NotChecked, update.installed.verificationStatus)
    }
}
