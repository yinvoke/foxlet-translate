package io.github.yinvoker.foxlet

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelDownloader] against a scripted stand-in for the Mozilla CDN on
 * loopback. Models keep their production `https://firefox-settings-attachments…`
 * URLs; only the `open` seam rewrites them to the local port, so the HTTPS and
 * allow-list checks run exactly as they would on a device. Sleeps are recorded
 * rather than waited and the jitter comes from a fixed seed, so the retry
 * schedule is asserted, not sampled.
 */
class ModelDownloaderTest {

    private companion object {
        const val HOST = Catalog.MOZILLA_ATTACHMENT_HOST
        const val LEX = "lex.50.50.enzh.s2t.bin"
        const val MODEL = "model.enzh.intgemm.alphas.bin"
        const val VOCAB = "vocab.enzh.spm"
        val RANGE = Regex("bytes=(\\d+)-")

        /** What the CDN does: full body on a plain GET, 206 for a satisfiable range, 416 otherwise. */
        fun serve(exchange: HttpExchange, bytes: ByteArray, range: String?) {
            val offset = range?.let { RANGE.matchEntire(it)?.groupValues?.get(1)?.toInt() }
            when {
                offset == null -> {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
                offset >= bytes.size -> {
                    exchange.responseHeaders.add("Content-Range", "bytes */${bytes.size}")
                    exchange.sendResponseHeaders(416, -1)
                }
                else -> {
                    exchange.responseHeaders.add("Content-Range", "bytes $offset-${bytes.size - 1}/${bytes.size}")
                    exchange.sendResponseHeaders(206, (bytes.size - offset).toLong())
                    exchange.responseBody.write(bytes, offset, bytes.size - offset)
                }
            }
        }

        /** Answers with the whole file whatever the request said — a CDN that ignores `Range`. */
        val IGNORING_RANGE = Script { exchange, bytes, _ -> serve(exchange, bytes, null) }

        fun status(code: Int, vararg headers: Pair<String, String>) = Script { exchange, _, _ ->
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(code, -1)
        }

        /** Declares the full length, sends [count] bytes, then drops the connection. */
        fun dropAfter(count: Int) = Script { exchange, bytes, _ ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes, 0, count)
            exchange.responseBody.flush()
            // Propagating out of the handler makes the JDK server close the socket.
            throw IOException("connection dropped by test")
        }

        /** Serves [other] in place of the real file, honouring `Range` as usual. */
        fun content(other: ByteArray) = Script { exchange, _, range -> serve(exchange, other, range) }

        fun bytes(seed: Int, size: Int): ByteArray = Random(seed).nextBytes(size)
    }

    private fun interface Script {
        fun serve(exchange: HttpExchange, bytes: ByteArray, range: String?)
    }

    /** One loopback HTTP server: files by path, a request log, and per-path scripts consumed in order. */
    private class Cdn : AutoCloseable {
        data class Request(val path: String, val range: String?)

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val files = ConcurrentHashMap<String, ByteArray>()
        private val scripts = ConcurrentHashMap<String, ConcurrentLinkedDeque<Script>>()
        val requests = CopyOnWriteArrayList<Request>()

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        fun open(url: URL): HttpURLConnection =
            URL("http", "127.0.0.1", server.address.port, url.path).openConnection() as HttpURLConnection

        fun add(name: String, bytes: ByteArray) {
            files["/x/$name"] = bytes
        }

        fun script(name: String, vararg steps: Script) {
            scripts.getOrPut("/x/$name") { ConcurrentLinkedDeque() }.addAll(steps)
        }

        fun requestsFor(name: String): List<Request> = requests.filter { it.path == "/x/$name" }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val range = exchange.requestHeaders.getFirst("Range")
            requests += Request(path, range)
            try {
                val bytes = files[path]
                if (bytes == null) {
                    exchange.sendResponseHeaders(404, -1)
                    return
                }
                // The real CDN labels vocabularies text/plain; bytes must survive that.
                exchange.responseHeaders.add("Content-Type", if (path.endsWith(".spm")) "text/plain; charset=utf-8" else "application/octet-stream")
                val script = scripts[path]?.pollFirst() ?: Script { e, b, r -> serve(e, b, r) }
                script.serve(exchange, bytes, range)
            } finally {
                exchange.close()
            }
        }

        override fun close() = server.stop(0)
    }

    private val cdn = Cdn()
    private val root = createTempDirectory("foxlet-dl").toFile()
    private val sleeps = mutableListOf<Long>()
    private val events = mutableListOf<Catalog.DownloadProgress>()
    private val policy = Catalog.DownloadPolicy(
        connectTimeoutMillis = 2_000,
        readTimeoutMillis = 2_000,
        maxRetries = 3,
        initialBackoffMillis = 1_000,
        maxBackoffMillis = 30_000,
    )
    private val payloads = linkedMapOf(
        LEX to bytes(1, 40_000),
        MODEL to bytes(2, 150_000),
        VOCAB to bytes(3, 20_000),
    )

    @After
    fun tearDown() {
        cdn.close()
        root.deleteRecursively()
    }

    private fun downloader(policy: Catalog.DownloadPolicy = this.policy) =
        ModelDownloader(policy, cdn::open, { sleeps += it }, Random(42))

    private fun download(model: Catalog.Model, policy: Catalog.DownloadPolicy = this.policy): ModelFiles =
        runBlocking { downloader(policy).download(root, model) { events += it } }

    /** A three-file model served by [cdn]; URLs point at the production host unless a test says otherwise. */
    private fun model(host: String = HOST, scheme: String = "https", files: Map<String, ByteArray> = payloads): Catalog.Model {
        files.forEach { (name, data) -> cdn.add(name, data) }
        val assets = files.map { (name, data) ->
            Catalog.Asset(name, data.size.toLong(), Catalog.digest(data), "$scheme://$host/x/$name")
        }
        return Catalog.Model("en", "zh-Hans", "2.2", assets.sortedBy { it.name })
    }

    private fun temp(model: Catalog.Model) = File(root, ".download-${model.directoryName}")
    private fun destination(model: Catalog.Model) = File(root, model.directoryName)

    // ---------------------------------------------------------------- happy path and reuse

    @Test
    fun `a fresh download publishes a verified directory with a manifest`() {
        val model = model()
        val files = download(model)

        val dir = checkNotNull(files.model.parentFile)
        assertEquals(model.directoryName, dir.name)
        assertEquals(root, dir.parentFile)
        files.verify()
        assertEquals(model.assets.associate { it.name to it.sha256 }, files.expectedSha256)
        assertArrayEquals(payloads[MODEL], files.model.readBytes())
        assertArrayEquals(payloads[VOCAB], files.srcVocab.readBytes())
        assertArrayEquals(payloads[LEX], files.shortlist.readBytes())
        assertEquals(setOf(LEX, MODEL, VOCAB, ModelManifest.FILE_NAME), dir.list()!!.toSet())
        assertEquals(model, ModelManifest.read(dir))
        assertFalse(temp(model).exists())

        assertEquals(3, cdn.requests.size)
        assertTrue(cdn.requests.none { it.range != null })
        assertTrue(sleeps.isEmpty())

        val last = events.last()
        assertEquals(model.sizeBytes, last.downloaded)
        assertEquals(model.sizeBytes, last.total)
        assertEquals(VOCAB, last.assetName)
        assertEquals(2, last.assetIndex)
        assertTrue(events.all { it.attempt == 1 && it.assetCount == 3 && it.total == model.sizeBytes })
        assertTrue(events.zipWithNext().all { (a, b) -> b.downloaded >= a.downloaded })
        // Assets run in file-name order, each announced before its first byte and after its last.
        assertEquals(listOf(LEX, MODEL, VOCAB), events.map { it.assetName }.distinct())
        assertEquals(listOf(0, 1, 2), events.map { it.assetIndex }.distinct())
        for (asset in model.assets) {
            assertTrue(events.any { it.assetName == asset.name && it.assetDownloaded == 0L })
            assertTrue(events.any { it.assetName == asset.name && it.assetDownloaded == asset.size })
        }
    }

    @Test
    fun `an installed directory that still verifies is reused without a request`() {
        val model = model()
        val first = download(model)
        val requests = cdn.requests.size
        events.clear()

        val again = download(model)

        assertEquals(first, again)
        assertEquals(requests, cdn.requests.size)
        assertEquals(1, events.size)
        assertEquals(model.sizeBytes, events.single().downloaded)
        assertEquals(model.sizeBytes, events.single().total)
        assertEquals(2, events.single().assetIndex)
    }

    // ---------------------------------------------------------------- resume

    @Test
    fun `a dropped connection resumes with a range request for the missing tail`() {
        val model = model()
        cdn.script(MODEL, dropAfter(70_000))

        download(model).verify()

        val requests = cdn.requestsFor(MODEL)
        assertEquals(2, requests.size)
        assertNull(requests[0].range)
        assertEquals("bytes=70000-", requests[1].range)
        assertEquals(1, cdn.requests.count { it.range != null })
        assertEquals(1, sleeps.size)
        assertTrue(sleeps.single() in 0L..1_000L)
        // The retry announces itself and counts the prefix on disk, for the model as a whole too.
        val resumed = events.first { it.assetName == MODEL && it.attempt == 2 }
        assertEquals(70_000L, resumed.assetDownloaded)
        assertEquals(40_000L + 70_000L, resumed.downloaded)
        assertFalse(temp(model).exists())
    }

    @Test
    fun `a server that ignores the range restarts the asset from zero`() {
        val model = model()
        cdn.script(MODEL, dropAfter(70_000), IGNORING_RANGE)

        val files = download(model)

        files.verify()
        assertArrayEquals(payloads[MODEL], files.model.readBytes())
        val requests = cdn.requestsFor(MODEL)
        assertEquals(2, requests.size)
        assertEquals("bytes=70000-", requests[1].range)
        // After the 200 the counter drops below the prefix: the body replaced it.
        assertTrue(events.any { it.assetName == MODEL && it.attempt == 2 && it.assetDownloaded in 1L until 70_000L })
    }

    @Test
    fun `a 416 discards the partial file and asks again from zero`() {
        val model = model()
        val temp = temp(model).also { it.mkdir() }
        File(temp, "$MODEL.part").writeBytes(payloads.getValue(MODEL).copyOf(30_000))
        cdn.script(MODEL, status(416, "Content-Range" to "bytes */150000"))

        download(model).verify()

        val requests = cdn.requestsFor(MODEL)
        assertEquals(listOf("bytes=30000-", null), requests.map { it.range })
        assertTrue("a rejected resume is not a retry", sleeps.isEmpty())
    }

    @Test
    fun `a partial file that was a lie fails verification once and is thrown away`() {
        val model = model()
        val temp = temp(model).also { it.mkdir() }
        val part = File(temp, "$MODEL.part")
        part.writeBytes(bytes(99, 30_000))

        val error = assertThrows(ModelIntegrityException::class.java) { download(model) }

        assertEquals(MODEL, error.assetName)
        assertEquals(listOf("bytes=30000-"), cdn.requestsFor(MODEL).map { it.range })
        assertFalse(part.exists())
        assertTrue("the temp dir survives for the next call", temp.isDirectory)
        assertTrue(File(temp, LEX).isFile)

        download(model).verify()
        assertEquals(listOf("bytes=30000-", null), cdn.requestsFor(MODEL).map { it.range })
    }

    @Test
    fun `without resume an existing partial file is ignored`() {
        val model = model()
        val temp = temp(model).also { it.mkdir() }
        File(temp, "$MODEL.part").writeBytes(payloads.getValue(MODEL).copyOf(30_000))

        download(model, policy.copy(resume = false)).verify()

        assertEquals(listOf(null), cdn.requestsFor(MODEL).map { it.range })
    }

    @Test
    fun `a complete asset already in the temp dir is reused and a stale one replaced`() {
        val model = model()
        val temp = temp(model).also { it.mkdir() }
        File(temp, MODEL).writeBytes(payloads.getValue(MODEL))
        File(temp, VOCAB).writeBytes(bytes(7, 20_000))

        download(model).verify()

        assertTrue(cdn.requestsFor(MODEL).isEmpty())
        assertEquals(1, cdn.requestsFor(VOCAB).size)
        assertEquals(2, cdn.requests.size)
        val skipped = events.filter { it.assetName == MODEL }
        assertEquals(1, skipped.size)
        assertEquals(150_000L, skipped.single().assetDownloaded)
        assertEquals(40_000L + 150_000L, skipped.single().downloaded)
    }

    // ---------------------------------------------------------------- retry

    @Test
    fun `a 503 is retried after a jittered pause and Retry-After is a floor`() {
        val model = model()
        cdn.script(LEX, status(503))
        cdn.script(MODEL, status(503, "Retry-After" to "2"))

        download(model).verify()

        assertEquals(2, cdn.requestsFor(LEX).size)
        assertEquals(2, cdn.requestsFor(MODEL).size)
        assertEquals(2, sleeps.size)
        assertTrue("first retry pauses within [0, initial]", sleeps[0] in 0L..1_000L)
        assertEquals("Retry-After: 2 beats the jitter", 2_000L, sleeps[1])
        assertTrue(events.any { it.assetName == LEX && it.attempt == 2 })
    }

    @Test
    fun `exhausted retries rethrow the last HTTP failure`() {
        val model = model()
        cdn.script(MODEL, status(503), status(503), status(503), status(503))

        val error = assertThrows(NetworkException::class.java) {
            download(model, policy.copy(maxBackoffMillis = 2_500))
        }

        assertEquals(503, error.httpStatus)
        assertEquals(4, error.attempt)
        assertEquals(MODEL, error.assetName)
        assertEquals(policy.maxRetries + 1, cdn.requestsFor(MODEL).size)
        assertEquals(policy.maxRetries, sleeps.size)
        assertTrue(sleeps[0] in 0L..1_000L)
        assertTrue(sleeps[1] in 0L..2_000L)
        assertTrue("capped by maxBackoffMillis", sleeps[2] in 0L..2_500L)
        assertTrue(temp(model).isDirectory)
        assertFalse(destination(model).exists())
    }

    @Test
    fun `exhausted retries retain the last transport failure as cause`() {
        val model = model()
        cdn.script(MODEL, dropAfter(0), dropAfter(0), dropAfter(0), dropAfter(0))

        // The JDK client returns EOF on a short fixed-length body, so the downloader's own
        // EOFException is what surfaces here; another client may throw its own IOException,
        // the structured public failure retains it after every attempt.
        val error = assertThrows(NetworkException::class.java) { download(model) }
        assertTrue(error.cause is IOException)
        assertEquals(4, error.attempt)

        assertEquals(4, cdn.requestsFor(MODEL).size)
        assertEquals(3, sleeps.size)
    }

    @Test
    fun `a refused connection is retried and then propagates with network details`() {
        val model = model()
        val unreachable = ModelDownloader(
            policy,
            { url -> URL("http", "127.0.0.1", 1, url.path).openConnection() as HttpURLConnection },
            { sleeps += it },
            Random(42),
        )

        assertThrows(NetworkException::class.java) { runBlocking { unreachable.download(root, model) {} } }

        assertEquals(3, sleeps.size)
        assertTrue(cdn.requests.isEmpty())
    }

    @Test
    fun `a 404 fails at once without retry or sleep`() {
        val model = model()
        cdn.script(LEX, status(404))

        val error = assertThrows(NetworkException::class.java) { download(model) }

        assertEquals("Model download returned HTTP 404", error.message)
        assertEquals(1, cdn.requests.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `redirects are not followed`() {
        val model = model()
        cdn.script(LEX, status(302, "Location" to "https://$HOST/x/$MODEL"))

        val error = assertThrows(NetworkException::class.java) { download(model) }

        assertEquals("Model download returned HTTP 302", error.message)
        assertEquals(listOf("/x/$LEX"), cdn.requests.map { it.path })
    }

    // ---------------------------------------------------------------- verification

    @Test
    fun `a checksum mismatch fails at once and removes the bad file but keeps the temp dir`() {
        val model = model()
        cdn.script(MODEL, content(bytes(99, 150_000)))

        val error = assertThrows(ModelIntegrityException::class.java) { download(model) }

        assertEquals(MODEL, error.assetName)
        assertEquals(1, cdn.requestsFor(MODEL).size)
        assertTrue(sleeps.isEmpty())
        val temp = temp(model)
        assertTrue(temp.isDirectory)
        assertTrue("the asset before it stays finished", File(temp, LEX).isFile)
        assertFalse(File(temp, MODEL).exists())
        assertFalse(File(temp, "$MODEL.part").exists())
        assertFalse(destination(model).exists())
    }

    @Test
    fun `without resume a checksum mismatch removes the whole temp dir`() {
        val model = model()
        cdn.script(MODEL, content(bytes(99, 150_000)))

        assertThrows(ModelIntegrityException::class.java) { download(model, policy.copy(resume = false)) }

        assertFalse(temp(model).exists())
        assertFalse(destination(model).exists())
    }

    @Test
    fun `a body longer than the record says is rejected without retry`() {
        val model = model()
        cdn.script(VOCAB, content(bytes(3, 20_001)))

        val error = assertThrows(ModelIntegrityException::class.java) { download(model) }

        assertEquals(VOCAB, error.assetName)
        assertEquals(1, cdn.requestsFor(VOCAB).size)
        assertTrue(sleeps.isEmpty())
        assertFalse(File(temp(model), "$VOCAB.part").exists())
    }

    // ---------------------------------------------------------------- trust boundary and input

    @Test
    fun `asset URLs outside the trust boundary are rejected before any request`() {
        assertThrows(IllegalArgumentException::class.java) { download(model(host = "evil.example")) }
        assertThrows(IllegalArgumentException::class.java) { download(model(scheme = "http")) }
        assertTrue(cdn.requests.isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())

        // The allow-list is the policy's to extend.
        download(model(host = "cdn.example"), policy.copy(allowedHosts = setOf("cdn.example"))).verify()
        assertEquals(3, cdn.requests.size)
    }

    @Test
    fun `malformed models are rejected before any request`() {
        val model = model()
        val first = model.assets.first()
        val bad = listOf(
            model.copy(assets = emptyList()),
            model.copy(assets = model.assets.reversed()),
            model.copy(assets = model.assets + first),
            model.copy(assets = listOf(first.copy(name = "../$LEX"))),
            model.copy(assets = listOf(first.copy(name = ModelManifest.FILE_NAME))),
            model.copy(assets = listOf(first.copy(name = "$LEX.part"))),
            model.copy(assets = model.assets.map { it.copy(sha256 = it.sha256.uppercase()) }),
            model.copy(assets = listOf(first.copy(size = 0))),
            model.copy(assets = listOf(first.copy(url = "not a url"))),
            model.copy(version = "2.2/../x"),
        )
        for (candidate in bad) {
            assertThrows(candidate.toString(), IllegalArgumentException::class.java) { download(candidate) }
        }
        assertTrue(cdn.requests.isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    // ---------------------------------------------------------------- cancellation and publish

    @Test
    fun `incomplete or unresolvable asset sets fail before network or publication`() {
        val valid = model()
        for (bad in listOf(
            valid.copy(assets = valid.assets.filter { it.name != VOCAB }),
            valid.copy(assets = valid.assets.map { if (it.name == MODEL) it.copy(name = "weights.bin") else it }.sortedBy { it.name }),
            valid.copy(assets = (valid.assets + valid.assets.first().copy(name = "extra.bin")).sortedBy { it.name }),
        )) {
            assertThrows(IllegalArgumentException::class.java) { download(bad) }
            assertFalse(destination(bad).exists())
        }
        assertTrue(cdn.requests.isEmpty())
    }

    @Test
    fun `a repaired collision directory is reused on the next download`() {
        val model = model()
        destination(model).mkdir()
        val repaired = download(model)
        val requestCount = cdn.requests.size
        val reused = download(model)
        assertEquals(repaired.model.canonicalFile, reused.model.canonicalFile)
        assertEquals(requestCount, cdn.requests.size)
    }

    @Test
    fun `cancellation keeps the partial file and the next call resumes from it`() {
        val model = model()

        val error = runBlocking {
            val deferred = async {
                val self = coroutineContext.job
                downloader().download(root, model) { progress ->
                    if (progress.assetName == MODEL && progress.assetDownloaded > 0) self.cancel()
                }
            }
            runCatching { deferred.await() }.exceptionOrNull()
        }

        assertTrue("got $error", error is CancellationException)
        val temp = temp(model)
        assertTrue(File(temp, LEX).isFile)
        val part = File(temp, "$MODEL.part")
        assertTrue(part.isFile)
        val kept = part.length()
        assertTrue(kept > 0)
        assertFalse(destination(model).exists())

        download(model).verify()
        assertEquals("bytes=$kept-", cdn.requestsFor(MODEL).last().range)
        assertFalse(temp.exists())
    }

    @Test
    fun `without resume cancellation removes the temp dir`() {
        val model = model()

        runBlocking {
            val deferred = async {
                val self = coroutineContext.job
                downloader(policy.copy(resume = false)).download(root, model) { progress ->
                    if (progress.assetName == MODEL && progress.assetDownloaded > 0) self.cancel()
                }
            }
            runCatching { deferred.await() }
        }

        assertFalse(temp(model).exists())
        assertFalse(destination(model).exists())
    }

    @Test
    fun `a destination that exists but does not verify is left alone and the download published beside it`() {
        val model = model()
        val stale = destination(model).also { it.mkdir() }
        for (name in listOf(LEX, MODEL, VOCAB)) File(stale, name).writeText("stale")

        val files = download(model)

        val dir = checkNotNull(files.model.parentFile)
        assertNotEquals(stale, dir)
        assertEquals(root, dir.parentFile)
        assertTrue(dir.name, Regex(Regex.escape(model.directoryName) + "-[0-9a-f-]{36}").matches(dir.name))
        files.verify()
        assertEquals(model, ModelManifest.read(dir))
        assertEquals("stale", File(stale, MODEL).readText())
        assertFalse(temp(model).exists())
        assertEquals(3, cdn.requests.size)
    }

    @Test
    fun `a model whose directory name cannot be listed back is rejected before any request`() {
        // Such a model would download, verify and then be invisible to installed()
        // and cleanup(): a version outside major.minor, a hidden pair, a token
        // with whitespace.
        for (bad in listOf(model().copy(version = "v2"), model().copy(from = ".."), model().copy(to = "zh Hans"))) {
            assertThrows(IllegalArgumentException::class.java) { download(bad) }
        }
        assertTrue(cdn.requests.isEmpty())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".download-") })
    }
}
