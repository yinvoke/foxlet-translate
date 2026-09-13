package io.github.yinvoker.foxlet

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONException
import org.json.JSONObject

/**
 * Version order for the strings Mozilla's `translations-models` collection
 * uses: `major.minor`, `major.minor` + `a` (bare alpha) and `major.minor` +
 * `aN` (numbered alpha). Firefox compares them with the toolkit comparator
 * (`Services.vc`, nsVersionComparator); for these shapes the order is
 * `1.0a < 1.0a1 < 1.0a2 < 1.0 < 1.1a1 < 1.1 < 2.0 < 2.1 < 2.2 < 2.3`:
 * every alpha sorts below the release of the same number, and the bare `a`
 * is the alpha whose number the comparator reads as 0, so it sorts below
 * `a1`. Only the "alpha below release" half ever decides anything here —
 * alphas are never selected — but the full order is what [ModelStore] sorts
 * installed directories by, so it delegates here rather than keep a twin.
 * Anything that does not parse sorts below everything that does.
 */
internal object MozillaVersion {
    /** [tier]: 0 = alpha (`a` is alpha 0, `aN` alpha N), 1 = release. [alpha] is 0 at tier 1. */
    data class Parsed(val major: Int, val minor: Int, val tier: Int, val alpha: Int)

    private val SHAPE = Regex("^(\\d+)\\.(\\d+)(a(\\d*))?$")
    private val ORDER = compareBy<Parsed>({ it.major }, { it.minor }, { it.tier }, { it.alpha })

    /** `^(\d+)\.(\d+)(a(\d*))?$`, or null; a number too large for an Int also yields null. */
    fun parse(version: String): Parsed? {
        val match = SHAPE.matchEntire(version) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val alpha = match.groups[4]?.value ?: return Parsed(major, minor, 1, 0)
        if (alpha.isEmpty()) return Parsed(major, minor, 0, 0)
        return Parsed(major, minor, 0, alpha.toIntOrNull() ?: return null)
    }

    /** Negative when [a] is older than [b]. Unparsable strings compare below parsable ones and lexically among themselves. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        return when {
            pa != null && pb != null -> ORDER.compare(pa, pb)
            pa != null -> 1
            pb != null -> -1
            else -> a.compareTo(b)
        }
    }

    /** True for alphas and for anything that does not parse; only releases are false. */
    fun isPrerelease(version: String): Boolean = parse(version)?.tier != 1
}

/**
 * The remote half of [Catalog.checkForUpdates]: one GET of a Remote
 * Settings changeset, the record parse, and Firefox's selection rule turned
 * into [Catalog.Model]s the downloader can take as they are.
 *
 * The selection mirrors `TranslationsParent.sys.mjs`
 * (`getMaxSupportedVersionRecords` + `#filterByModelVersion`): records are
 * filtered by their `filter_expression`, the highest version per pair wins,
 * and a pair's files must all share the model's version. Two things differ on
 * purpose. Firefox evaluates JEXL expressions against its runtime; this SDK
 * cannot, so a record is eligible only when its expression is empty or is
 * exactly the one Mozilla uses to publish Android-only files — every other
 * expression (nightly-only, desktop-only) counts as "not for us". And Firefox
 * picks per file and then reconciles; here a version counts only when it
 * ships a complete set, which is what the engine can load and what
 * [Catalog.Model.identity] is defined over.
 *
 * Nothing here retries, sleeps or caches. [open] is the test seam that lets a
 * local HTTP server stand in for the HTTPS endpoint.
 */
internal class RemoteIndex(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    /** One live (non-tombstone) record, reduced to the fields selection and download need. [sha256] is lower-case. */
    data class Record(
        val id: String,
        val name: String,
        val from: String,
        val to: String,
        val version: String,
        val fileType: String,
        val filterExpression: String?,
        val size: Long,
        val sha256: String,
        val location: String,
        val lastModified: Long,
    )

    /** [timestamp] is the collection data timestamp (ms epoch, the newest record's `last_modified`). */
    data class Changeset(val timestamp: Long, val records: List<Record>)

    /**
     * What upstream offers for one pair. [eligible] holds every complete,
     * Android-eligible release whose major is in range, highest version first,
     * so `eligible.first()` is what Firefox would load. [newerMajorVersion] is
     * the highest such release above the range, or null: a model this engine
     * build cannot load, reported so a host can suggest an SDK upgrade.
     */
    data class PairAvailability(
        val from: String,
        val to: String,
        val eligible: List<Catalog.Model>,
        val newerMajorVersion: String?,
    )

    /**
     * GET `<changesetUrl>?_expected=0` and parse it. `_expected=0` is the
     * documented "rely on the CDN TTL" form; the alternative (a fresh
     * timestamp) would only bust the cache, which one explicit check does not
     * need. Sends the three headers the client specification asks for and
     * nothing that identifies the device. A non-200 status is an [IOException]
     * naming the code and any `Retry-After`/`Backoff` value so the host can
     * back off; the body is not read. A body over [MAX_BODY_BYTES] (the real
     * one is ~380 KB) is an [IllegalStateException], like any other malformed
     * index.
     */
    fun fetch(source: Catalog.UpdateSource, network: NetworkOptions = NetworkOptions()): Changeset {
        val separator = if ('?' in source.changesetUrl) '&' else '?'
        val url = URL("${source.changesetUrl}${separator}_expected=0")
        require(url.protocol == "https") { "Model index requires HTTPS" }
        val connection = open(url)
        try {
            connection.connectTimeout = network.connectTimeout.timeoutMillis()
            connection.readTimeout = network.readTimeout.timeoutMillis()
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", source.userAgent)
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val seconds = listOf("Retry-After", "Backoff").firstNotNullOfOrNull {
                    connection.getHeaderField(it)?.trim()?.toLongOrNull()
                }
                throw HttpStatusException(url.toString(), code, seconds?.coerceIn(0, 120)?.times(1000))
            }
            // We asked for gzip explicitly, so no HTTP stack decodes it for us.
            val gzip = connection.contentEncoding?.trim().equals("gzip", ignoreCase = true)
            val body = connection.inputStream.let { if (gzip) GZIPInputStream(it) else it }.use { readCapped(it) }
            return parse(String(body, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse a changeset body. Unknown fields are ignored (the schema has grown
     * before: `hash` and `schema` are recent additions), tombstones
     * (`deleted: true`) are dropped, and a record missing anything selection
     * needs is skipped rather than failing the whole index — one bad record
     * upstream should not hide every other pair. `timestamp` and `changes` are
     * the two things without which the body is not a changeset at all.
     */
    fun parse(json: String): Changeset {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalStateException("Model index is not a JSON object", e)
        }
        val timestamp = checkNotNull(root.long("timestamp")) { "Model index has no timestamp" }
        val changes = checkNotNull(root.optJSONArray("changes")) { "Model index has no changes array" }
        val records = ArrayList<Record>(changes.length())
        for (i in 0 until changes.length()) {
            records += record(changes.optJSONObject(i) ?: continue) ?: continue
        }
        return Changeset(timestamp, records)
    }

    private fun record(item: JSONObject): Record? {
        if (item.optBoolean("deleted", false)) return null
        val attachment = item.optJSONObject("attachment") ?: return null
        val sha256 = attachment.string("hash")?.lowercase() ?: return null
        val size = attachment.long("size") ?: return null
        val name = item.string("name") ?: return null
        if (size !in 1..(1024L * 1024 * 1024) || !SHA256.matches(sha256)) return null
        return Record(
            id = item.string("id") ?: return null,
            name = name,
            from = item.string("fromLang") ?: return null,
            to = item.string("toLang") ?: return null,
            version = item.string("version") ?: return null,
            fileType = item.string("fileType") ?: return null,
            // A non-string expression is kept as text: it will not equal the Android one, so the record is ineligible.
            filterExpression = if (item.isNull("filter_expression")) null else item.opt("filter_expression")?.toString(),
            size = size,
            sha256 = sha256,
            location = attachment.string("location") ?: return null,
            lastModified = item.long("last_modified") ?: 0L,
        )
    }

    /**
     * Firefox's selection over [changeset], keyed by (from, to) and sorted by
     * it. Pairs with nothing eligible in any major are absent. Asset URLs are
     * [Catalog.UpdateSource.attachmentBaseUrl] (one trailing slash) plus
     * the record's location — the mapping the bundled catalog was built with,
     * so a remote model that matches a catalog entry matches it byte for byte,
     * URL included.
     */
    fun availability(
        changeset: Changeset,
        source: Catalog.UpdateSource,
        supportedMajors: IntRange = Catalog.supportedMajorVersions,
    ): Map<Pair<String, String>, PairAvailability> {
        val base = source.attachmentBaseUrl.trimEnd('/') + "/"
        val result = LinkedHashMap<Pair<String, String>, PairAvailability>()
        val byPair = changeset.records.filter(::isEligible).groupBy { it.from to it.to }
        for (pair in byPair.keys.sortedWith(compareBy({ it.first }, { it.second }))) {
            val complete = byPair.getValue(pair).groupBy { it.version }
                .mapNotNull { (version, files) -> completeSet(pair.first, pair.second, version, files, base) }
                .sortedWith { a, b -> MozillaVersion.compare(b.version, a.version) }
            val eligible = complete.filter { major(it.version) in supportedMajors }
            val newer = complete.firstOrNull { major(it.version) > supportedMajors.last }?.version
            if (eligible.isNotEmpty() || newer != null) {
                result[pair] = PairAvailability(pair.first, pair.second, eligible, newer)
            }
        }
        return result
    }

    private fun isEligible(record: Record): Boolean {
        if (record.fileType !in ASSET_TYPES) return false
        val expression = record.filterExpression
        if (!expression.isNullOrBlank() && expression.trim() != ANDROID_FILTER) return false
        return !MozillaVersion.isPrerelease(record.version)
    }

    /**
     * A version is usable only as `model` + `lex` + (`vocab` | `srcvocab` +
     * `trgvocab`), one record each. A duplicated file type, a missing one, or
     * a shared vocab next to a split one makes the version ambiguous — the
     * engine's own directory resolver would refuse the result — so it is
     * skipped in favour of the next lower complete one.
     */
    private fun completeSet(from: String, to: String, version: String, files: List<Record>, base: String): Catalog.Model? {
        val byType = files.groupBy { it.fileType }
        if (byType.values.any { it.size != 1 }) return null
        val vocabulary = if ("vocab" in byType) setOf("vocab") else setOf("srcvocab", "trgvocab")
        if (byType.keys != setOf("model", "lex") + vocabulary) return null
        val assets = files.map { Catalog.Asset(it.name, it.size, it.sha256, base + it.location) }.sortedBy { it.name }
        if (assets.map { it.name }.toSet().size != assets.size) return null
        if (assets.any { it.name != File(it.name).name || it.name.any { c -> c <= ' ' } }) return null
        val resolved = try {
            ModelFiles.fromNames(File("."), assets.map { it.name })
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (resolved.files().map { it.name }.toSet() != assets.map { it.name }.toSet()) return null
        return Catalog.Model(from, to, version, assets)
    }

    private fun major(version: String): Int = checkNotNull(MozillaVersion.parse(version)) { "unparsable version survived eligibility: $version" }.major

    /**
     * Compare what is installed with what is available, one
     * [Catalog.UpdateCandidate] per pair that is installed or offered
     * (restricted to [pairs] when given), sorted by (from, to). The installed
     * side is the highest version on disk for the pair — an unsuffixed
     * directory before a collision-suffixed twin — without hashing anything:
     * this is a listing, verification is [Catalog.installedFor]'s job.
     * An update is a different identity at the same or a higher version; an
     * installed model that upstream no longer lists at all is reported through
     * `installedStillListed`, never acted on.
     */
    fun report(
        installed: List<Catalog.InstalledModel>,
        availability: Map<Pair<String, String>, PairAvailability>,
        source: Catalog.UpdateSource,
        indexTimestamp: Long,
        pairs: Set<Pair<String, String>>?,
        now: Long = System.currentTimeMillis(),
    ): Catalog.UpdateReport {
        val candidatePairs = (installed.map { it.from to it.to } + availability.keys).toSet()
            .filter { pairs == null || it in pairs }
            .sortedWith(compareBy({ it.first }, { it.second }))
        val candidates = candidatePairs.map { pair ->
            val best = installed.filter { it.from == pair.first && it.to == pair.second }.sortedWith(INSTALLED_ORDER).firstOrNull()
            val offered = availability[pair]
            val eligible = offered?.eligible.orEmpty()
            val available = eligible.firstOrNull()
            Catalog.UpdateCandidate(
                from = pair.first,
                to = pair.second,
                installed = best,
                available = available,
                updateAvailable = best != null && available != null &&
                    available.identity != best.identity && MozillaVersion.compare(available.version, best.version) >= 0,
                downloadSizeBytes = available?.sizeBytes ?: 0L,
                installedStillListed = best == null || eligible.any { it.identity == best.identity },
                newerMajorVersion = offered?.newerMajorVersion,
            )
        }
        return Catalog.UpdateReport(source, now, indexTimestamp, candidates)
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            check(out.size() + n <= MAX_BODY_BYTES) { "Model index exceeds ${MAX_BODY_BYTES / (1024 * 1024)} MiB" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // org.json's optString turns a JSON null into the text "null" on Android; go through opt() and type-check instead.
    private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
    private fun JSONObject.long(key: String): Long? {
        val value: Any? = opt(key)
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            else -> null
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000
        /** Ten times today's ~380 KB index; the body is held as a String and a JSON tree, so the cap bounds heap, not just I/O. */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        /** The one JEXL expression Mozilla uses to publish Android-only files; compared after trimming (upstream has a trailing space). */
        const val ANDROID_FILTER = "env.appinfo.OS == 'Android'"
        private val ASSET_TYPES = setOf("model", "lex", "vocab", "srcvocab", "trgvocab")
        private val SHA256 = Regex("[0-9a-f]{64}")
        /** Highest version first, then an unsuffixed directory before a collision-suffixed one, then by name for determinism. */
        private val INSTALLED_ORDER = Comparator<Catalog.InstalledModel> { a, b -> MozillaVersion.compare(b.version, a.version) }
            .thenBy { it.directory.name != "${it.from}-${it.to}-${it.version}-${it.identity}" }
            .thenBy { it.directory.name }
    }
}
