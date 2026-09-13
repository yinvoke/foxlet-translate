package io.github.yinvoker.foxlet

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIndexTest {

    private val index = RemoteIndex()
    private val mozilla = ModelCatalog.UpdateSource.MOZILLA
    private val enZh = "en" to "zh-Hans"
    private val enRu = "en" to "ru"
    private val slEn = "sl" to "en"
    private val caEn = "ca" to "en"

    /** Trimmed live capture: real records for a few pairs, one tombstone, one synthetic 3.0 set. */
    private val sampleJson: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/io/github/yinvoker/foxlet/changeset-sample.json")) { "fixture missing" }
            .bufferedReader().use { it.readText() }
    }
    private val sample: RemoteIndex.Changeset by lazy { index.parse(sampleJson) }
    private val availability by lazy { index.availability(sample, mozilla) }

    // ---------------------------------------------------------------- MozillaVersion

    @Test fun `version order matches the mozilla toolkit table`() {
        val ascending = listOf("1.0a", "1.0a1", "1.0a2", "1.0", "1.1a1", "1.1", "2.0a1", "2.0", "2.1", "2.2", "2.3", "3.0", "10.0")
        for (i in ascending.indices) for (j in ascending.indices) {
            val expected = i.compareTo(j)
            val actual = MozillaVersion.compare(ascending[i], ascending[j])
            assertEquals("${ascending[i]} vs ${ascending[j]}", expected, Integer.signum(actual))
        }
        assertEquals(0, MozillaVersion.compare("2.10", "2.10"))
        assertTrue(MozillaVersion.compare("2.10", "2.9") > 0) // numeric, not lexical
    }

    @Test fun `version parse yields tiers and rejects other shapes`() {
        assertEquals(MozillaVersion.Parsed(2, 2, 1, 0), MozillaVersion.parse("2.2"))
        assertEquals(MozillaVersion.Parsed(1, 0, 0, 0), MozillaVersion.parse("1.0a"))
        assertEquals(MozillaVersion.Parsed(1, 0, 0, 3), MozillaVersion.parse("1.0a3"))
        for (bad in listOf("", "2", "2.", ".2", "2.2.1", "2.2b1", "v2.2", " 2.2", "2.2 ", "2.2-beta", "99999999999.0")) {
            assertNull(bad, MozillaVersion.parse(bad))
        }
    }

    @Test fun `unparsable versions sort below parsable ones and lexically among themselves`() {
        assertTrue(MozillaVersion.compare("garbage", "1.0a1") < 0)
        assertTrue(MozillaVersion.compare("1.0a1", "garbage") > 0)
        assertTrue(MozillaVersion.compare("abc", "abd") < 0)
        assertEquals(0, MozillaVersion.compare("abc", "abc"))
    }

    @Test fun `only releases are not prereleases`() {
        assertFalse(MozillaVersion.isPrerelease("2.2"))
        assertTrue(MozillaVersion.isPrerelease("2.2a"))
        assertTrue(MozillaVersion.isPrerelease("2.2a1"))
        assertTrue(MozillaVersion.isPrerelease("nonsense"))
    }

    // ---------------------------------------------------------------- parse

    @Test fun `parse keeps the timestamp and drops tombstones and unknown fields`() {
        assertEquals(1788296824409L, sample.timestamp)
        // 57 entries in the file: one tombstone is dropped, the synthetic records with their extra field stay.
        assertEquals(56, sample.records.size)
        assertTrue(sample.records.none { it.id.isBlank() })
        assertEquals(4, sample.records.count { it.version == "3.0" })
        val model = sample.records.single { it.from == "en" && it.to == "zh-Hans" && it.version == "2.2" && it.fileType == "model" }
        assertEquals("model.enzh.intgemm.alphas.bin", model.name)
        assertEquals(43849787L, model.size)
        assertEquals("4e5accc141373565ddc8fa1565bceaa8d0c3482a82cab8131c719ebcc6c2157c", model.sha256)
        assertEquals("main-workspace/translations-models/a7ff7d5e-e67e-406c-a34b-a7edea35b10e.bin", model.location)
        assertEquals("", model.filterExpression)
        assertEquals(1758219707463L, model.lastModified)
        // Upstream's Android-only expression carries a trailing space; parse must not trim it away (eligibility does).
        val android = sample.records.first { it.from == "en" && it.to == "ru" && it.version == "2.1" }
        assertEquals("env.appinfo.OS == 'Android' ", android.filterExpression)
        // JSON null (old ca->en 1.0 records) becomes a Kotlin null, not the text "null".
        assertTrue(sample.records.filter { it.from == "ca" && it.version == "1.0" }.all { it.filterExpression == null })
    }

    @Test fun `parse fails without a timestamp or a changes array and on non-json`() {
        assertThrows(IllegalStateException::class.java) { index.parse("""{"changes":[]}""") }
        assertThrows(IllegalStateException::class.java) { index.parse("""{"timestamp":null,"changes":[]}""") }
        assertThrows(IllegalStateException::class.java) { index.parse("""{"timestamp":1}""") }
        assertThrows(IllegalStateException::class.java) { index.parse("not json") }
        assertThrows(IllegalStateException::class.java) { index.parse("[]") }
        assertEquals(RemoteIndex.Changeset(5, emptyList()), index.parse("""{"timestamp":5,"changes":[]}"""))
    }

    @Test fun `parse skips records missing required fields and normalises the hash`() {
        val hash = "A".repeat(64)
        val json = """{"timestamp":7,"changes":[
            {"id":"a","name":"model.x.bin","fromLang":"xx","toLang":"yy","version":"1.0","fileType":"model",
             "attachment":{"hash":"$hash","size":10,"location":"l/a.bin"}},
            {"id":"b","name":"lex.x.bin","fromLang":"xx","toLang":"yy","version":"1.0","fileType":"lex"},
            {"id":"c","name":"vocab.x.spm","fromLang":"xx","toLang":"yy","version":"1.0","fileType":"vocab",
             "attachment":{"hash":"short","size":10,"location":"l/c.spm"}},
            {"id":"d","name":"vocab.x.spm","fromLang":"xx","toLang":"yy","fileType":"vocab",
             "attachment":{"hash":"$hash","size":10,"location":"l/d.spm"}},
            {"id":"e","name":"vocab.x.spm","fromLang":"xx","toLang":"yy","version":"1.0","fileType":"vocab",
             "attachment":{"hash":"$hash","size":0,"location":"l/e.spm"}},
            {"id":"f","deleted":true,"last_modified":1},
            "not an object",
            {"id":"g","name":"vocab.x.spm","fromLang":"xx","toLang":"yy","version":"1.0","fileType":"vocab","filter_expression":42,
             "attachment":{"hash":"$hash","size":10,"location":"l/g.spm"}}
        ]}"""
        val parsed = index.parse(json)
        assertEquals(listOf("a", "g"), parsed.records.map { it.id })
        assertEquals(hash.lowercase(), parsed.records[0].sha256)
        assertNull(parsed.records[0].filterExpression)
        assertEquals(0L, parsed.records[0].lastModified)
        assertEquals("42", parsed.records[1].filterExpression)
    }

    // ---------------------------------------------------------------- availability

    @Test fun `en to zh-Hans selects 2 2 and reproduces the bundled catalog entry exactly`() {
        val catalog = ModelCatalog.find("en", "zh-Hans")
        val remote = availability.getValue(enZh).eligible.first()
        assertEquals("2.2", remote.version)
        assertEquals(4, remote.assets.size)
        assertEquals(remote.assets.map { it.name }.sorted(), remote.assets.map { it.name })
        assertEquals(
            listOf("lex.50.50.enzh.s2t.bin", "model.enzh.intgemm.alphas.bin", "srcvocab.enzh.spm", "trgvocab.enzh.spm"),
            remote.assets.map { it.name },
        )
        assertEquals(catalog.identity, remote.identity)
        // Names, sizes, hashes and URLs all line up with the pinned registry: the same bytes from the same place.
        assertEquals(catalog, remote)
        assertEquals(catalog.directoryName, remote.directoryName)
    }

    @Test fun `android-only and shared-vocab pairs also match the catalog`() {
        // en->ru's only release in range is published with the Android-only expression (trailing space upstream).
        assertEquals(ModelCatalog.find("en", "ru"), availability.getValue(enRu).eligible.first())
        assertEquals(ModelCatalog.find("sl", "en"), availability.getValue(slEn).eligible.first())
        // ca->en 1.0 has filter_expression null; both 1.0 and 2.0 are eligible, 2.0 (the catalog one) first.
        val ca = availability.getValue(caEn).eligible
        assertEquals(listOf("2.0", "1.0"), ca.map { it.version })
        assertEquals(ModelCatalog.find("ca", "en"), ca.first())
    }

    @Test fun `alphas and foreign filter expressions are never eligible`() {
        // en->ru carries 1.0a1, 1.0a2, 2.0a1, 2.1a1 (nightly) and 2.0 (desktop-only): only 2.1 survives.
        assertEquals(listOf("2.1"), availability.getValue(enRu).eligible.map { it.version })
        // en->zh-Hans: 2.2 (everyone), 2.1 (Android), not 2.0 (desktop-only) nor 2.0a2 (nightly).
        assertEquals(listOf("2.2", "2.1"), availability.getValue(enZh).eligible.map { it.version })
        // A pair that exists upstream only as a nightly alpha is not offered at all.
        assertFalse(("nn" to "en") in availability)
        assertTrue(availability.values.flatMap { it.eligible }.none { MozillaVersion.isPrerelease(it.version) })
        // The map is sorted by pair, so hosts get a stable listing.
        assertEquals(listOf(caEn, enRu, enZh, slEn), availability.keys.toList())
    }

    @Test fun `a desktop-only version is skipped in favour of the next lower eligible one`() {
        // sl->en: 2.1 (everyone) > 2.0 (desktop-only) > 1.1 (everyone). Without 2.1, 1.1 wins, not 2.0.
        assertEquals(listOf("2.1", "1.1"), availability.getValue(slEn).eligible.map { it.version })
        val without21 = sample.copy(records = sample.records.filterNot { it.from == "sl" && it.version == "2.1" })
        assertEquals("1.1", index.availability(without21, mozilla).getValue(slEn).eligible.first().version)
    }

    @Test fun `an incomplete or ambiguous version is skipped in favour of the next lower complete one`() {
        val isTop = { r: RemoteIndex.Record -> r.from == "en" && r.to == "zh-Hans" && r.version == "2.2" }
        // Missing lex.
        val missingLex = sample.copy(records = sample.records.filterNot { isTop(it) && it.fileType == "lex" })
        assertEquals("2.1", index.availability(missingLex, mozilla).getValue(enZh).eligible.first().version)
        // Duplicated model record (same version, second id).
        val duplicate = sample.records.first { isTop(it) && it.fileType == "model" }.copy(id = "dup")
        val duplicated = sample.copy(records = sample.records + duplicate)
        assertEquals("2.1", index.availability(duplicated, mozilla).getValue(enZh).eligible.first().version)
        // Shared vocab next to the split pair: the engine could not resolve such a directory.
        val extraVocab = sample.records.first { isTop(it) && it.fileType == "srcvocab" }.copy(id = "v", fileType = "vocab", name = "vocab.enzh.spm")
        val mixed = sample.copy(records = sample.records + extraVocab)
        assertEquals("2.1", index.availability(mixed, mozilla).getValue(enZh).eligible.first().version)
        // Split vocab with one half missing.
        val halfSplit = sample.copy(records = sample.records.filterNot { isTop(it) && it.fileType == "trgvocab" })
        assertEquals("2.1", index.availability(halfSplit, mozilla).getValue(enZh).eligible.first().version)
        // A pair whose every version is incomplete disappears.
        val gone = sample.copy(records = sample.records.filterNot { it.from == "sl" && it.fileType == "model" })
        assertFalse(slEn in index.availability(gone, mozilla))
    }

    @Test fun `quality models and unknown file types do not count`() {
        val top = sample.records.first { it.from == "en" && it.to == "zh-Hans" && it.version == "2.2" && it.fileType == "model" }
        val extras = listOf(
            top.copy(id = "q", fileType = "qualityModel", name = "qualityModel.enzh.bin"),
            top.copy(id = "u", fileType = "unknown", name = "unknown.enzh.bin"),
        )
        val with = index.availability(sample.copy(records = sample.records + extras), mozilla)
        assertEquals(availability, with)
    }

    @Test fun `an unresolvable asset name falls back to the next complete version`() {
        val broken = sample.copy(records = sample.records.map {
            if (it.from == "en" && it.to == "zh-Hans" && it.version == "2.2" && it.fileType == "model")
                it.copy(name = "weights.bin") else it
        })
        assertEquals("2.1", index.availability(broken, mozilla).getValue(enZh).eligible.first().version)
    }

    @Test fun `newer major versions are reported but never selected`() {
        val zh = availability.getValue(enZh)
        assertEquals("3.0", zh.newerMajorVersion)
        assertTrue(zh.eligible.none { it.version == "3.0" })
        assertNull(availability.getValue(enRu).newerMajorVersion)
        // Widen the range and the same set becomes selectable.
        val wide = index.availability(sample, mozilla, supportedMajors = 1..3).getValue(enZh)
        assertEquals("3.0", wide.eligible.first().version)
        assertNull(wide.newerMajorVersion)
        // Narrow it to 1.x and en->zh-Hans (2.x only) is offered solely as a hint.
        val narrow = index.availability(sample, mozilla, supportedMajors = 1..1)
        assertEquals(RemoteIndex.PairAvailability("en", "zh-Hans", emptyList(), "3.0"), narrow.getValue(enZh))
    }

    @Test fun `a version above the range is the newer-major hint even without an eligible one`() {
        val narrow = index.availability(sample, mozilla, supportedMajors = 1..1)
        assertEquals("2.1", narrow.getValue(enRu).newerMajorVersion)
        assertTrue(narrow.getValue(enRu).eligible.isEmpty())
    }

    @Test fun `asset urls join the base url with one slash regardless of how it is written`() {
        val expected = ModelCatalog.find("en", "zh-Hans").assets.map { it.url }
        for (base in listOf("https://firefox-settings-attachments.cdn.mozilla.net/", "https://firefox-settings-attachments.cdn.mozilla.net", "https://firefox-settings-attachments.cdn.mozilla.net//")) {
            val source = mozilla.copy(attachmentBaseUrl = base)
            assertEquals(base, expected, index.availability(sample, source).getValue(enZh).eligible.first().assets.map { it.url })
        }
        val custom = mozilla.copy(attachmentBaseUrl = "https://mirror.example/models")
        val url = index.availability(sample, custom).getValue(enZh).eligible.first().assets.first().url
        assertTrue(url, url.startsWith("https://mirror.example/models/main-workspace/translations-models/"))
    }

    // ---------------------------------------------------------------- report

    private val root = createTempDirectory("ri").toFile()

    private fun installed(
        model: ModelCatalog.Model,
        version: String = model.version,
        identity: String = model.identity,
        suffix: String? = null,
    ) = ModelCatalog.InstalledModel(
        from = model.from,
        to = model.to,
        version = version,
        identity = identity,
        directory = File(root, "${model.from}-${model.to}-$version-$identity" + (suffix?.let { "-$it" } ?: "")),
        sizeBytes = model.sizeBytes,
        model = model,
        isCurrentCatalogVersion = identity == model.identity,
        verified = null,
    )

    private fun candidate(report: ModelCatalog.UpdateReport, pair: Pair<String, String>) =
        report.candidates.single { it.from == pair.first && it.to == pair.second }

    @Test fun `report lists offered pairs that are not installed`() {
        val report = index.report(emptyList(), availability, mozilla, sample.timestamp, pairs = null, now = 42L)
        assertEquals(mozilla, report.source)
        assertEquals(42L, report.checkedAtEpochMillis)
        assertEquals(sample.timestamp, report.indexTimestamp)
        assertEquals(listOf(caEn, enRu, enZh, slEn), report.candidates.map { it.from to it.to })
        val zh = candidate(report, enZh)
        assertNull(zh.installed)
        assertEquals(ModelCatalog.find("en", "zh-Hans"), zh.available)
        assertFalse(zh.updateAvailable)
        assertEquals(ModelCatalog.find("en", "zh-Hans").sizeBytes, zh.downloadSizeBytes)
        assertTrue(zh.installedStillListed)
        assertEquals("3.0", zh.newerMajorVersion)
        assertNull(candidate(report, enRu).newerMajorVersion)
        assertTrue(report.updates.isEmpty())
        assertEquals(report.candidates, report.notInstalled)
    }

    @Test fun `installed equals available means no update and still listed`() {
        val current = installed(ModelCatalog.find("en", "zh-Hans"))
        val report = index.report(listOf(current), availability, mozilla, sample.timestamp, pairs = null)
        val zh = candidate(report, enZh)
        assertEquals(current, zh.installed)
        assertFalse(zh.updateAvailable)
        assertTrue(zh.installedStillListed)
        assertEquals("3.0", zh.newerMajorVersion)
        assertTrue(report.updates.isEmpty())
        assertEquals(listOf(caEn, enRu, slEn), report.notInstalled.map { it.from to it.to })
    }

    @Test fun `an older installed version is an update whether or not upstream still lists it`() {
        val catalog = ModelCatalog.find("en", "zh-Hans")
        val previous = availability.getValue(enZh).eligible[1] // 2.1, still published for Android
        val stillListed = installed(catalog, version = previous.version, identity = previous.identity)
        val vanished = installed(catalog, version = "1.0", identity = "0123456789abcdef0123")
        for ((model, listed) in listOf(stillListed to true, vanished to false)) {
            val zh = candidate(index.report(listOf(model), availability, mozilla, sample.timestamp, pairs = null), enZh)
            assertTrue(zh.updateAvailable)
            assertEquals(listed, zh.installedStillListed)
            assertEquals(catalog, zh.available)
            assertEquals(catalog.sizeBytes, zh.downloadSizeBytes)
        }
    }

    @Test fun `same version with different bytes is an update and not listed`() {
        val catalog = ModelCatalog.find("en", "zh-Hans")
        val reupload = installed(catalog, identity = "ffffffffffffffffffff")
        val zh = candidate(index.report(listOf(reupload), availability, mozilla, sample.timestamp, pairs = null), enZh)
        assertTrue(zh.updateAvailable)
        assertFalse(zh.installedStillListed)
    }

    @Test fun `an installed version newer than upstream is not an update and not listed`() {
        val catalog = ModelCatalog.find("en", "zh-Hans")
        val ahead = installed(catalog, version = "2.9", identity = "eeeeeeeeeeeeeeeeeeee")
        val report = index.report(listOf(ahead), availability, mozilla, sample.timestamp, pairs = null)
        val zh = candidate(report, enZh)
        assertFalse(zh.updateAvailable)
        assertFalse(zh.installedStillListed)
        assertEquals(catalog, zh.available)
        assertTrue(report.updates.isEmpty())
    }

    @Test fun `an installed pair upstream does not offer is reported with nothing available`() {
        val orphan = installed(ModelCatalog.find("en", "zh-Hans").copy(from = "xx", to = "yy"))
        val report = index.report(listOf(orphan), availability, mozilla, sample.timestamp, pairs = null)
        val xy = candidate(report, "xx" to "yy")
        assertEquals(orphan, xy.installed)
        assertNull(xy.available)
        assertFalse(xy.updateAvailable)
        assertEquals(0L, xy.downloadSizeBytes)
        assertFalse(xy.installedStillListed)
        assertNull(xy.newerMajorVersion)
        assertEquals(listOf(caEn, enRu, enZh, slEn, "xx" to "yy"), report.candidates.map { it.from to it.to })
    }

    @Test fun `the highest installed version is compared, an unsuffixed directory first`() {
        val catalog = ModelCatalog.find("en", "zh-Hans")
        val older = installed(catalog, version = "2.1", identity = "1111111111111111111a")
        val olderSuffixed = installed(catalog, version = "2.1", identity = "1111111111111111111a", suffix = "uuid")
        val current = installed(catalog)
        val currentSuffixed = installed(catalog, suffix = "uuid")
        val zh = candidate(index.report(listOf(olderSuffixed, currentSuffixed, older, current), availability, mozilla, 1L, null), enZh)
        assertEquals(current, zh.installed)
        assertFalse(zh.updateAvailable)
        // Only the suffixed twin left: it is still the newest and still listed.
        val twin = candidate(index.report(listOf(olderSuffixed, currentSuffixed, older), availability, mozilla, 1L, null), enZh)
        assertEquals(currentSuffixed, twin.installed)
        assertTrue(twin.installedStillListed)
    }

    @Test fun `pairs restricts the report to those pairs`() {
        val current = installed(ModelCatalog.find("en", "zh-Hans"))
        val report = index.report(listOf(current), availability, mozilla, sample.timestamp, pairs = setOf(enRu, "xx" to "yy"))
        assertEquals(listOf(enRu), report.candidates.map { it.from to it.to })
        assertEquals(listOf(enRu), report.notInstalled.map { it.from to it.to })
        val empty = index.report(listOf(current), availability, mozilla, sample.timestamp, pairs = emptySet())
        assertTrue(empty.candidates.isEmpty())
    }

    // ---------------------------------------------------------------- fetch

    private class Served(val server: HttpServer, val index: RemoteIndex, val requests: MutableList<HttpExchange>) : AutoCloseable {
        override fun close() = server.stop(0)
    }

    private fun serve(handler: (HttpExchange) -> Unit): Served {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<HttpExchange>()
        server.createContext("/") { exchange ->
            requests += exchange
            try { handler(exchange) } finally { exchange.close() }
        }
        server.start()
        val port = server.address.port
        // The production code still sees and validates the HTTPS URL; only the socket goes to the local server.
        val index = RemoteIndex(open = { url -> URL("http://127.0.0.1:$port${url.file}").openConnection() as HttpURLConnection })
        return Served(server, index, requests)
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    @Test fun `fetch reads a gzip body and sends the client-spec headers`() {
        serve { exchange ->
            val body = gzip(sampleJson)
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }.use { served ->
            val changeset = served.index.fetch(mozilla)
            assertEquals(sample, changeset)
            val request = served.requests.single()
            assertEquals("GET", request.requestMethod)
            assertEquals("/v1/buckets/main/collections/translations-models/changeset", request.requestURI.path)
            assertEquals("_expected=0", request.requestURI.query)
            assertEquals(mozilla.userAgent, request.requestHeaders.getFirst("User-Agent"))
            assertEquals("gzip", request.requestHeaders.getFirst("Accept-Encoding"))
            assertEquals("application/json", request.requestHeaders.getFirst("Accept"))
        }
    }

    @Test fun `fetch reads a plain body and a custom user agent`() {
        serve { exchange ->
            val body = sampleJson.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }.use { served ->
            val source = mozilla.copy(userAgent = "TestApp/1.0")
            assertEquals(sample, served.index.fetch(source))
            assertEquals("TestApp/1.0", served.requests.single().requestHeaders.getFirst("User-Agent"))
        }
    }

    @Test fun `fetch appends the cache-buster to a url that already has a query`() {
        serve { exchange ->
            val body = """{"timestamp":1,"changes":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }.use { served ->
            val source = mozilla.copy(changesetUrl = mozilla.changesetUrl + "?_since=%221%22")
            assertEquals(RemoteIndex.Changeset(1, emptyList()), served.index.fetch(source))
            assertEquals("_since=%221%22&_expected=0", served.requests.single().requestURI.rawQuery)
        }
    }

    @Test fun `a 503 with retry-after is an io exception naming both`() {
        serve { exchange ->
            exchange.responseHeaders.add("Retry-After", "7")
            exchange.sendResponseHeaders(503, -1)
        }.use { served ->
            val e = assertThrows(IOException::class.java) { served.index.fetch(mozilla) }
            assertTrue(e.message, e.message!!.contains("503"))
            assertTrue(e.message, e.message!!.contains("7"))
        }
    }

    @Test fun `a backoff header is reported the same way and a plain 404 carries only the code`() {
        serve { exchange ->
            exchange.responseHeaders.add("Backoff", "3600")
            exchange.sendResponseHeaders(429, -1)
        }.use { served ->
            val e = assertThrows(IOException::class.java) { served.index.fetch(mozilla) }
            assertTrue(e.message, e.message!!.contains("429") && e.message!!.contains("3600"))
        }
        serve { exchange -> exchange.sendResponseHeaders(404, -1) }.use { served ->
            val e = assertThrows(IOException::class.java) { served.index.fetch(mozilla) }
            assertEquals("Model index returned HTTP 404", e.message)
        }
    }

    @Test fun `redirects are not followed`() {
        serve { exchange ->
            exchange.responseHeaders.add("Location", "https://example.invalid/elsewhere")
            exchange.sendResponseHeaders(302, -1)
        }.use { served ->
            val e = assertThrows(IOException::class.java) { served.index.fetch(mozilla) }
            assertTrue(e.message, e.message!!.contains("302"))
            assertEquals(1, served.requests.size)
        }
    }

    @Test fun `a malformed body from the server is a state error not a transport one`() {
        serve { exchange ->
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }.use { served ->
            assertThrows(IllegalStateException::class.java) { served.index.fetch(mozilla) }
        }
    }

    @Test fun `a body over the cap is refused`() {
        serve { exchange ->
            // Chunked, so the client cannot know the size up front; pad past the cap with whitespace.
            exchange.sendResponseHeaders(200, 0)
            val chunk = ByteArray(1024 * 1024) { ' '.code.toByte() }
            exchange.responseBody.write("""{"timestamp":1,"changes":[]""".toByteArray())
            repeat(RemoteIndex.MAX_BODY_BYTES / chunk.size + 1) { exchange.responseBody.write(chunk) }
            exchange.responseBody.write("}".toByteArray())
        }.use { served ->
            assertThrows(IllegalStateException::class.java) { served.index.fetch(mozilla) }
        }
    }

    @Test fun `a connection failure propagates as an io exception`() {
        val served = serve { exchange -> exchange.sendResponseHeaders(204, -1) }
        served.close()
        assertThrows(IOException::class.java) { served.index.fetch(mozilla) }
    }
}
