package io.github.yinvoker.foxlet

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/**
 * Blocking implementation behind the listing, lookup, delete and cleanup
 * functions of [ModelCatalog]. The facade runs these on `Dispatchers.IO`;
 * here everything is a plain function over the model root, so tests drive it
 * without a coroutine and the integrator wires it with one `withContext`.
 *
 * The store trusts nothing about a directory but its name and, when present,
 * the manifest a download left in it. The name says which pair and version
 * the bytes claim to be, the manifest or the bundled catalog says which bytes
 * they should be, and hashing says whether they are. The three stay apart in
 * [ModelCatalog.InstalledModel] so a host can list cheaply and pay for
 * verification only on the directory it is about to use.
 *
 * Downloads publish and removals unpublish with a rename. A listing tolerates
 * directories disappearing while it reads them. Removal holds [ActiveModels]'s
 * monitor across the in-use check and mutation, excluding new load reservations.
 */
internal object ModelStore {
    /** The parts of a published directory name, `<from>-<to>-<version>-<identity>[-<uuid>]`. */
    data class ParsedName(val from: String, val to: String, val version: String, val identity: String, val collisionSuffix: String?)

    /** Suffix [ModelCatalog.download] appends when the exact directory name already exists. */
    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val NAME = Regex("(.+)-(\\d+\\.\\d+[a-z0-9]*)-([0-9a-f]{20})(?:-($UUID_PATTERN))?")
    private val LANGUAGE_TAG = Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?")

    /** Every `<from>-<to>` the bundled catalog knows, for splitting a pair without guessing. */
    private val catalogPairs: Map<String, List<Pair<String, String>>> by lazy {
        ModelCatalog.models.map { it.from to it.to }.distinct().groupBy { (from, to) -> "$from-$to" }
    }

    /**
     * Parse a directory name, or null when it is not one this SDK publishes.
     *
     * Language tags carry dashes (`zh-Hans`), so the name is read from the
     * right, where every field has a fixed shape: an optional collision uuid,
     * the 20-hex identity, a `major.minor[tag]` version. What is left is
     * `<from>-<to>`, split by whoever knows best: [manifest] when its pair
     * spells that string, else the one bundled catalog pair that does, else
     * the leftmost dash whose two sides both look like a language tag. The
     * heuristic reads `zh-Hant-en` right because `Hant-en` is not a tag.
     */
    fun parseName(name: String, manifest: ModelCatalog.Model? = null): ParsedName? {
        val match = NAME.matchEntire(name) ?: return null
        val (from, to) = splitPair(match.groupValues[1], manifest) ?: return null
        return ParsedName(from, to, match.groupValues[2], match.groupValues[3], match.groups[4]?.value)
    }

    private fun splitPair(pair: String, manifest: ModelCatalog.Model?): Pair<String, String>? {
        if (manifest != null && "${manifest.from}-${manifest.to}" == pair) return manifest.from to manifest.to
        catalogPairs[pair]?.singleOrNull()?.let { return it }
        var dash = pair.indexOf('-')
        while (dash >= 0) {
            val from = pair.substring(0, dash)
            val to = pair.substring(dash + 1)
            if (LANGUAGE_TAG.matches(from) && LANGUAGE_TAG.matches(to)) return from to to
            dash = pair.indexOf('-', dash + 1)
        }
        return null
    }

    // ---------------------------------------------------------------- versions

    /**
     * Mozilla toolkit order for the version shapes the model index uses
     * (`1.0a < 1.0a1 < 1.0 < 1.1 < 2.0`, see [MozillaVersion]). Anything that
     * does not parse sorts below everything that does, and lexically among
     * its own kind, so a newest-first listing puts it last.
     */
    internal fun compareVersions(a: String, b: String): Int = MozillaVersion.compare(a, b)

    /**
     * Listing order: by pair, newest version first, then identity, with a
     * collision-suffixed twin after its unsuffixed original. The download
     * only ever suffixes a directory whose exact name is taken, so twins
     * share an identity and this tie-break is the only one that matters.
     */
    private val newestFirst: Comparator<ModelCatalog.InstalledModel> =
        compareBy<ModelCatalog.InstalledModel> { it.from }
            .thenBy { it.to }
            .thenComparator { a, b -> compareVersions(b.version, a.version) }
            .thenBy { it.identity }
            .thenBy { isCollisionSuffixed(it) }
            .thenBy { it.directory.name }

    private fun isCollisionSuffixed(model: ModelCatalog.InstalledModel): Boolean =
        model.directory.name != "${model.from}-${model.to}-${model.version}-${model.identity}"

    // ---------------------------------------------------------------- installed

    /**
     * Every published directory under [root], see [ModelCatalog.installed].
     * A root that does not exist yet has nothing installed rather than being
     * an error: a fresh install asks before its first download. Hidden
     * entries (`.download-*`, `.trash-*`) and anything whose name does not
     * parse are not models and are left out; [ModelCatalog.cleanup] is the
     * function that looks at those.
     */
    fun installed(root: File, verify: Boolean): List<ModelCatalog.InstalledModel> {
        val children = root.listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory && !it.name.startsWith('.') && !isSymlink(it) }
            .mapNotNull { describe(it, verify) }
            .sortedWith(newestFirst)
    }

    /**
     * What one directory is, or null when it is not a model directory.
     *
     * The manifest is believed only when it agrees with the name on pair,
     * version and identity. One that does not is as good as absent — as is a
     * malformed one — and then it does not get to say how the pair splits
     * either, so the name is parsed again without it. A directory without a
     * usable manifest is identified through the bundled catalog by identity,
     * which is how directories from older SDKs (no manifest yet) keep working.
     * [ModelCatalog.InstalledModel.sizeBytes] counts every file in the
     * directory, manifest included: it is what a delete gives back.
     */
    private fun describe(directory: File, verify: Boolean): ModelCatalog.InstalledModel? {
        var manifest = try {
            ModelManifest.read(directory)
        } catch (_: IllegalArgumentException) {
            null // Malformed: nothing to verify against, same as no manifest.
        } catch (_: IOException) {
            null // Removed while listing, or an unreadable manifest.
        }
        var parsed = parseName(directory.name, manifest) ?: return null
        if (manifest != null && (manifest.from != parsed.from || manifest.to != parsed.to ||
                manifest.version != parsed.version || manifest.identity != parsed.identity)) {
            manifest = null
            parsed = parseName(directory.name) ?: return null
        }
        val catalog = ModelCatalog.models.firstOrNull { it.from == parsed.from && it.to == parsed.to && it.identity == parsed.identity }
        val model = manifest ?: catalog
        val verified = if (!verify || model == null) null else verifies(directory, model)
        return ModelCatalog.InstalledModel(
            from = parsed.from,
            to = parsed.to,
            version = parsed.version,
            identity = parsed.identity,
            directory = directory,
            sizeBytes = sizeOf(directory),
            model = model,
            isCurrentCatalogVersion = catalog != null,
            verified = verified,
        )
    }

    /** True when the directory holds exactly the bytes [model] describes; hashes every file. */
    private fun verifies(directory: File, model: ModelCatalog.Model): Boolean = try {
        filesOf(directory, model).verify()
        true
    } catch (_: IllegalArgumentException) {
        false // Missing, extra, ambiguous or altered files: whichever, not this model.
    } catch (_: IOException) {
        false // Vanished under a concurrent delete: not this model either.
    }

    private fun filesOf(directory: File, model: ModelCatalog.Model): ModelFiles =
        ModelFiles.fromDirectory(directory).copy(expectedSha256 = model.assets.associate { it.name to it.sha256 })

    /**
     * See [ModelCatalog.installedFor]. Walks the pair's directories newest
     * first and stops at the first that hashes clean, so the usual cost is
     * one model's worth of hashing — the same the reuse path of
     * [ModelCatalog.download] pays. Directories with nothing to check against
     * are never returned: unverifiable is not the same as usable.
     */
    fun installedFor(root: File, from: String, to: String): ModelFiles? {
        for (candidate in installed(root, verify = false)) {
            if (candidate.from != from || candidate.to != to) continue
            val model = candidate.model ?: continue
            try {
                return filesOf(candidate.directory, model).also { it.verify() }
            } catch (_: IllegalArgumentException) {
                continue // Corrupt or incomplete: the next version down may still be whole.
            } catch (_: IOException) {
                continue // Deleted while being hashed.
            }
        }
        return null
    }

    // ---------------------------------------------------------------- delete / cleanup

    /** See [ModelCatalog.delete]. */
    fun delete(root: File, model: ModelCatalog.InstalledModel): Long {
        val directory = model.directory
        require(canonical(directory).parentFile == canonical(root)) { "not a model directory directly under $root: $directory" }
        if (!directory.isDirectory) return 0
        return checkNotNull(ActiveModels.ifInactive(listOf(directory)) {
            if (directory.isDirectory) remove(root, directory) else 0L
        }) { "model directory is in use by a FoxletEngine; call releaseAllModels() first: $directory" }
    }

    /** See [ModelCatalog.delete]; refuses the whole pair when any version is held. */
    fun delete(root: File, from: String, to: String): Long {
        val versions = installed(root, verify = false).filter { it.from == from && it.to == to }
        return checkNotNull(ActiveModels.ifInactive(versions.map { it.directory }) {
            versions.sumOf { delete(root, it) }
        }) { "model directory is in use by a FoxletEngine; call releaseAllModels() first: $from → $to" }
    }

    /**
     * Unpublish [directory] with a rename, then delete it. The rename is the
     * atomic step: a concurrent listing sees the model or nothing, never a
     * directory with half its files. If the delete stops part way, what is
     * left is a `.trash-*` that the next [cleanup] finishes, and the bytes
     * reported are only the ones actually gone.
     */
    private fun remove(root: File, directory: File): Long {
        // Belt to delete()'s braces: nothing outside the root is ever unpublished.
        require(!isSymlink(directory) && canonical(directory).parentFile == canonical(root)) { "not a model directory directly under $root: $directory" }
        val bytes = sizeOf(directory)
        val trash = File(root, ".trash-${UUID.randomUUID()}")
        check(directory.renameTo(trash)) { "cannot unpublish model directory: $directory" }
        deleteTree(trash)
        return if (trash.exists()) bytes - sizeOf(trash) else bytes
    }

    /**
     * See [ModelCatalog.cleanup]. [now] is the clock for the temp-age rule,
     * injectable so tests need not wait a week.
     *
     * A `.download-*` directory is a paused download until it has gone
     * [staleTempAgeMillis] without a write — its freshest timestamp counts,
     * the directory's or any file's — because a resumable download is worth
     * keeping and a forgotten one is not. A `.trash-*` is an interrupted
     * delete and goes regardless. Both are skipped when a live engine reads
     * from them or when the host lists them in [keep].
     *
     * Supersession is decided per pair from the newest directory that hashes
     * clean; only a proven-good newer version makes an older one redundant.
     * Everything strictly older than it goes, as does a collision-suffixed
     * twin of it, and nothing else: a rebuild at the same version with other
     * bytes may be the one the host wants, and a directory nothing can verify
     * is not this SDK's to judge.
     */
    fun cleanup(
        root: File,
        staleTempAgeMillis: Long,
        removeSuperseded: Boolean,
        keep: Set<File>,
        now: Long = System.currentTimeMillis(),
    ): ModelCatalog.CleanupReport {
        require(staleTempAgeMillis >= 0) { "staleTempAgeMillis must not be negative" }
        val kept = keep.map(::canonical).toSet()
        val removedTempDirs = mutableListOf<File>()
        val removedSuperseded = mutableListOf<ModelCatalog.InstalledModel>()
        val skippedInUse = mutableListOf<File>()
        var bytesFreed = 0L

        for (directory in root.listFiles().orEmpty()) {
            if (!directory.isDirectory || isSymlink(directory)) continue
            val trash = directory.name.startsWith(".trash-")
            if (!trash && !directory.name.startsWith(".download-")) continue
            if (canonical(directory) in kept) continue
            val freed = ActiveModels.ifInactive(listOf(directory)) {
                if (!trash && now - newestWriteIn(directory) <= staleTempAgeMillis) return@ifInactive 0L
                val bytes = sizeOf(directory)
                deleteTree(directory)
                if (!directory.exists()) removedTempDirs += directory
                bytes - sizeOf(directory)
            }
            if (freed == null) skippedInUse += directory else bytesFreed += freed
        }

        if (removeSuperseded) {
            for ((_, versions) in installed(root, verify = false).groupBy { it.from to it.to }) {
                // Newest first, so the first that verifies is the best there is.
                val best = versions.firstOrNull { it.model != null && verifies(it.directory, it.model) } ?: continue
                for (entry in versions) {
                    if (entry === best || entry.model == null) continue
                    val older = compareVersions(entry.version, best.version) < 0
                    val twin = entry.version == best.version && entry.identity == best.identity && isCollisionSuffixed(entry)
                    if (!older && !twin) continue
                    if (canonical(entry.directory) in kept) continue
                    val freed = ActiveModels.ifInactive(listOf(entry.directory)) {
                        if (!entry.directory.isDirectory) return@ifInactive 0L
                        remove(root, entry.directory).also { removedSuperseded += entry }
                    }
                    if (freed == null) skippedInUse += entry.directory else bytesFreed += freed
                }
            }
        }
        return ModelCatalog.CleanupReport(removedTempDirs, removedSuperseded, bytesFreed, skippedInUse)
    }

    // ---------------------------------------------------------------- helpers

    private fun sizeOf(directory: File): Long = directory.walkTopDown()
        .onEnter { !isSymlink(it) }.filter { !isSymlink(it) && it.isFile }.sumOf { it.length() }

    private fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    /**
     * `deleteRecursively` without following links: a symbolic link inside a
     * model directory is removed as a link, never descended into, so a stray
     * link can at worst leave its own entry behind, not empty its target.
     */
    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory && !isSymlink(file)) file.listFiles()?.forEach { deleteTree(it) }
        return file.delete() || !file.exists()
    }

    /** Latest modification anywhere in [directory], the directory itself included. */
    private fun newestWriteIn(directory: File): Long = directory.walkTopDown()
        .onEnter { !isSymlink(it) }.filter { !isSymlink(it) }.maxOfOrNull { it.lastModified() } ?: 0L

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile // Unresolvable path: compare it as spelled rather than fail the listing.
    }
}
