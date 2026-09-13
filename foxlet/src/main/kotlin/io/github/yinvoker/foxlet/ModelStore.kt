package io.github.yinvoker.foxlet

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/**
 * Blocking implementation behind the listing, lookup, delete and cleanup
 * functions of [ModelManager]. The client runs these on `Dispatchers.IO`;
 * here everything is a plain function over the model root, so storage tests
 * can exercise filesystem behavior independently of the client lifecycle.
 *
 * The store trusts nothing about a directory but its name and, when present,
 * the manifest a download left in it. The name says which pair and version
 * the bytes claim to be, the manifest or the bundled catalog says which bytes
 * they should be, and hashing says whether they are. The three stay apart in
 * [Catalog.InstalledModel] so a host can list cheaply and pay for
 * verification only on the directory it is about to use.
 *
 * Downloads publish and removals unpublish with a rename. A listing tolerates
 * directories disappearing while it reads them. Removal holds [ActiveModels]'s
 * monitor across the in-use check and mutation, excluding new load reservations.
 */
internal object ModelStore {
    /** The parts of a published directory name, `<from>-<to>-<version>-<identity>[-<uuid>]`. */
    data class ParsedName(val from: String, val to: String, val version: String, val identity: String, val collisionSuffix: String?)

    /** Suffix [Catalog.download] appends when the exact directory name already exists. */
    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val NAME = Regex("(.+)-(\\d+\\.\\d+[a-z0-9]*)-([0-9a-f]{20})(?:-($UUID_PATTERN))?")
    private val LANGUAGE_TAG = Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?")

    /** Every `<from>-<to>` the bundled catalog knows, for splitting a pair without guessing. */
    private val catalogPairs: Map<String, List<Pair<String, String>>> by lazy {
        Catalog.models.map { it.from to it.to }.distinct().groupBy { (from, to) -> "$from-$to" }
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
    fun parseName(name: String, manifest: Catalog.Model? = null): ParsedName? {
        val match = NAME.matchEntire(name) ?: return null
        val (from, to) = splitPair(match.groupValues[1], manifest) ?: return null
        return ParsedName(from, to, match.groupValues[2], match.groupValues[3], match.groups[4]?.value)
    }

    private fun splitPair(pair: String, manifest: Catalog.Model?): Pair<String, String>? {
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
    private val newestFirst: Comparator<Catalog.InstalledModel> =
        compareBy<Catalog.InstalledModel> { it.from }
            .thenBy { it.to }
            .thenComparator { a, b -> compareVersions(b.version, a.version) }
            .thenBy { it.identity }
            .thenBy { isCollisionSuffixed(it) }
            .thenBy { it.directory.name }

    private fun isCollisionSuffixed(model: Catalog.InstalledModel): Boolean =
        model.directory.name != "${model.from}-${model.to}-${model.version}-${model.identity}"

    // ---------------------------------------------------------------- installed

    /**
     * Every published directory under [root], see [Catalog.installed].
     * A root that does not exist yet has nothing installed rather than being
     * an error: a fresh install asks before its first download. Hidden
     * entries (`.download-*`, `.trash-*`) and anything whose name does not
     * parse are not models and are left out; [Catalog.cleanup] is the
     * function that looks at those.
     */
    fun installed(root: File, verify: Boolean): List<Catalog.InstalledModel> {
        val children = children(root)
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
     * [Catalog.InstalledModel.sizeBytes] counts every file in the
     * directory, manifest included: it is what a delete gives back.
     */
    private fun describe(directory: File, verify: Boolean): Catalog.InstalledModel? {
        var manifest = try {
            ModelManifest.read(directory)
        } catch (_: IllegalArgumentException) {
            null // Malformed: nothing to verify against, same as no manifest.
        } catch (error: IOException) {
            if (File(directory, ModelManifest.FILE_NAME).exists()) throw error
            null // Disappeared while listing.
        }
        var parsed = parseName(directory.name, manifest) ?: return null
        if (manifest != null && (manifest.from != parsed.from || manifest.to != parsed.to ||
                manifest.version != parsed.version || manifest.identity != parsed.identity)) {
            manifest = null
            parsed = parseName(directory.name) ?: return null
        }
        val catalog = Catalog.models.firstOrNull { it.from == parsed.from && it.to == parsed.to && it.identity == parsed.identity }
        val model = manifest ?: catalog
        val verified = if (!verify || model == null) null else verifies(directory, model)
        return Catalog.InstalledModel(
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
    private fun verifies(directory: File, model: Catalog.Model): Boolean = try {
        filesOf(directory, model).verify()
        true
    } catch (_: IllegalArgumentException) {
        false // Missing, extra, ambiguous or altered files: whichever, not this model.
    } catch (_: IOException) {
        false // Vanished under a concurrent delete: not this model either.
    }

    private fun filesOf(directory: File, model: Catalog.Model): ModelFiles =
        ModelFiles.fromDirectory(directory).copy(expectedSha256 = model.assets.associate { it.name to it.sha256 })

    /**
     * See [Catalog.installedFor]. Walks the pair's directories newest
     * first and stops at the first that hashes clean, so the usual cost is
     * one model's worth of hashing — the same the reuse path of
     * [Catalog.download] pays. Directories with nothing to check against
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

    fun delete(root: File, model: Catalog.InstalledModel): Long = deleteDetailed(root, model).freedBytes

    fun delete(root: File, from: String, to: String): Long {
        val versions = installed(root, false).filter { it.from == from && it.to == to }
        return ActiveModels.ifInactive(versions.map { it.directory }) {
            versions.sumOf { deleteDetailed(root, it).freedBytes }
        } ?: throw ModelInUseException(pair = LanguagePair(from, to))
    }

    /** Re-check occupancy at the actual mutation, including nested prefix files. */
    fun deleteDetailed(root: File, model: Catalog.InstalledModel): DeleteResult {
        val directory = model.directory
        require(!isSymlink(directory) && directory.canonicalFile.parentFile == root.canonicalFile) { "Installation is outside this store" }
        val id = installationId(root, directory)
        return ActiveModels.ifInactive(listOf(directory)) { removeDetailed(root, directory, id) }
            ?: throw ModelInUseException(id)
    }

    private fun removeDetailed(root: File, directory: File, id: InstallationId): DeleteResult {
        if (!directory.exists()) return DeleteResult(id, directory, DeleteStatus.AlreadyAbsent, 0)
        val trash = File(root, ".trash-" + UUID.randomUUID())
        val renamed = try { directory.renameTo(trash) } catch (e: SecurityException) {
            return DeleteResult(id, directory, DeleteStatus.Failed, 0, ModelStorageException("Cannot unpublish model directory", directory, e))
        }
        if (!renamed) return DeleteResult(id, directory, DeleteStatus.Failed, 0,
            ModelStorageException("Cannot unpublish model directory", directory))
        val result = eraseDetailed(trash, id)
        return result.copy(directory = directory)
    }

    /** Counts bytes only for files actually deleted, even if another file cannot be removed. */
    private fun eraseDetailed(directory: File, id: InstallationId?): DeleteResult {
        var freed = 0L
        var failure: Throwable? = null
        fun erase(file: File) {
            try {
                if (file.isDirectory && !isSymlink(file)) {
                    val entries = file.listFiles()
                    if (entries == null) { failure = IOException("Cannot list " + file); return }
                    entries.forEach(::erase)
                }
                val bytes = if (file.isFile && !isSymlink(file)) file.length() else 0L
                if (file.delete()) freed += bytes
                else if (java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) failure = IOException("Cannot delete " + file)
            } catch (e: SecurityException) { failure = e }
        }
        erase(directory)
        val remains = java.nio.file.Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        return DeleteResult(id, directory, if (remains) DeleteStatus.PartiallyDeleted else DeleteStatus.Deleted, freed,
            if (remains) ModelStorageException("Some model files could not be deleted", directory, failure) else null)
    }

    fun cleanup(
        root: File,
        staleTempAgeMillis: Long,
        removeSuperseded: Boolean,
        keep: Set<File>,
        now: Long = System.currentTimeMillis(),
    ): Catalog.CleanupReport {
        require(staleTempAgeMillis >= 0)
        val kept = keep.map { it.canonicalFile }.toSet()
        val removedTempDirs = mutableListOf<File>()
        val removedSuperseded = mutableListOf<Catalog.InstalledModel>()
        val skippedInUse = mutableListOf<File>()
        val failures = mutableListOf<DeleteResult>()
        var bytesFreed = 0L
        fun account(result: DeleteResult) {
            bytesFreed += result.freedBytes
            if (result.status == DeleteStatus.PartiallyDeleted || result.status == DeleteStatus.Failed) failures += result
        }
        for (directory in children(root)) {
            if (!directory.isDirectory || isSymlink(directory) || directory.canonicalFile in kept) continue
            val trash = directory.name.startsWith(".trash-")
            if (!trash && !directory.name.startsWith(".download-")) continue
            if (!trash && now - newestWriteIn(directory) <= staleTempAgeMillis) continue
            val result = ActiveModels.ifInactive(listOf(directory)) { eraseDetailed(directory, null) }
            if (result == null) skippedInUse += directory else {
                account(result)
                if (result.status == DeleteStatus.Deleted) removedTempDirs += directory
            }
        }
        if (removeSuperseded) {
            for ((_, versions) in installed(root, false).groupBy { it.from to it.to }) {
                val best = versions.firstOrNull { it.model != null && verifies(it.directory, it.model) } ?: continue
                for (entry in versions) {
                    if (entry === best || entry.model == null || entry.directory.canonicalFile in kept) continue
                    val older = compareVersions(entry.version, best.version) < 0
                    val twin = entry.version == best.version && entry.identity == best.identity && isCollisionSuffixed(entry)
                    if (!older && !twin) continue
                    val result = ActiveModels.ifInactive(listOf(entry.directory)) {
                        removeDetailed(root, entry.directory, installationId(root, entry.directory))
                    }
                    if (result == null) skippedInUse += entry.directory else {
                        account(result)
                        if (result.status == DeleteStatus.Deleted || result.status == DeleteStatus.PartiallyDeleted) removedSuperseded += entry
                    }
                }
            }
        }
        return Catalog.CleanupReport(removedTempDirs, removedSuperseded, bytesFreed, skippedInUse, failures)
    }

    private fun children(root: File): Array<File> {
        if (!java.nio.file.Files.exists(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return emptyArray()
        if (!root.isDirectory) throw IOException("Model root is not a directory: " + root)
        return root.listFiles() ?: throw IOException("Cannot read model directory: " + root)
    }

    // ---------------------------------------------------------------- helpers

    private fun sizeOf(directory: File): Long = directory.walkTopDown()
        .onEnter { !isSymlink(it) }.onFail { _, error -> throw error }
        .filter { !isSymlink(it) && it.isFile }.sumOf { it.length() }

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
