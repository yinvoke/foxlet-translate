package io.github.yinvoker.foxlet

import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createTempDirectory
import kotlin.math.sign
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ModelStore] and [ActiveModels] are plain functions over a directory, so
 * the whole listing, lookup, delete and cleanup policy runs here on temp
 * roots holding byte-sized fake models. The hashes are real — the store
 * hashes whatever it verifies — only the bytes are tiny.
 *
 * [FoxletEngine] cannot load a model on the JVM, so the "in use" guard is
 * driven through [ActiveModels] directly, the same calls the engine makes
 * around a native load and release.
 */
class ModelStoreTest {

    private companion object {
        const val HOST = "https://firefox-settings-attachments.cdn.mozilla.net/x/"
        const val ID = "0123456789abcdef0123"
        const val DAY = 24L * 60 * 60 * 1000
        val FILE_NAMES = listOf("model.enzh.intgemm.alphas.bin", "srcvocab.enzh.spm", "trgvocab.enzh.spm", "lex.50.50.enzh.s2t.bin")
    }

    private class Fake(val dir: File, val model: ModelCatalog.Model)

    private lateinit var root: File

    @Before
    fun setUp() {
        root = createTempDirectory("store").toFile()
        ActiveModels.clearForTests()
    }

    @After
    fun tearDown() {
        ActiveModels.clearForTests()
        root.deleteRecursively()
    }

    /**
     * Publish a fake model the way a download does: files first, then the
     * directory name derived from their hashes, then the manifest. [seed]
     * varies the bytes so two versions never share an identity; the same seed
     * twice is how a collision-suffixed twin comes about.
     */
    private fun install(
        from: String, to: String, version: String,
        seed: String = version,
        manifest: Boolean = true,
        suffix: String? = null,
    ): Fake {
        val staging = File(root, "staging-${UUID.randomUUID()}").apply { check(mkdir()) }
        for (name in FILE_NAMES) File(staging, name).writeText("$name:$from-$to:$seed")
        val model = modelOf(staging, from, to, version)
        val dir = File(root, model.directoryName + if (suffix == null) "" else "-$suffix")
        check(staging.renameTo(dir))
        if (manifest) ModelManifest.write(dir, model)
        return Fake(dir, model)
    }

    private fun modelOf(dir: File, from: String, to: String, version: String) = ModelCatalog.Model(
        from, to, version,
        dir.listFiles()!!.filter { it.isFile && it.name != ModelManifest.FILE_NAME }
            .map { ModelCatalog.Asset(it.name, it.length(), ModelCatalog.sha256(it), HOST + it.name) }
            .sortedBy { it.name },
    )

    /** A directory with dummy files and no manifest, for names the catalog does not know. */
    private fun unknownDir(name: String): File {
        val dir = File(root, name).apply { check(mkdir()) }
        for (file in FILE_NAMES) File(dir, file).writeText("?")
        return dir
    }

    /** A `.download-*` / `.trash-*` leftover holding [bytes] bytes, last written [ageMillis] ago. */
    private fun tempDir(name: String, ageMillis: Long, bytes: Int = 10): File {
        val dir = File(root, name).apply { check(mkdir()) }
        val file = File(dir, "model.enzh.intgemm.alphas.bin.part").apply { writeText("x".repeat(bytes)) }
        val stamp = System.currentTimeMillis() - ageMillis
        // The file first: writing it is what bumped the directory's own time.
        check(file.setLastModified(stamp) && dir.setLastModified(stamp))
        return dir
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The same directory spelled through the root's parent, to exercise canonical comparison. */
    private fun aliased(dir: File): File = File(root, "../${root.name}/${dir.name}")

    private fun cleanup(keep: Set<File> = emptySet(), removeSuperseded: Boolean = true) =
        ModelStore.cleanup(root, staleTempAgeMillis = 7 * DAY, removeSuperseded = removeSuperseded, keep = keep)

    // ---------------------------------------------------------------- parseName

    @Test
    fun `directory names parse from the right so language tags may carry dashes`() {
        assertEquals(ModelStore.ParsedName("en", "zh-Hans", "2.2", ID, null), ModelStore.parseName("en-zh-Hans-2.2-$ID"))
        assertEquals(ModelStore.ParsedName("zh-Hant", "en", "2.0", ID, null), ModelStore.parseName("zh-Hant-en-2.0-$ID"))
        assertEquals(ModelStore.ParsedName("zh-Hans", "zh-Hant", "1.0", ID, null), ModelStore.parseName("zh-Hans-zh-Hant-1.0-$ID"))
        assertEquals(ModelStore.ParsedName("en", "ru", "1.0a1", ID, null), ModelStore.parseName("en-ru-1.0a1-$ID"))
        val uuid = UUID.randomUUID().toString()
        assertEquals(ModelStore.ParsedName("ja", "en", "1.0", ID, uuid), ModelStore.parseName("ja-en-1.0-$ID-$uuid"))
        assertEquals(ModelStore.ParsedName("zh-Hant", "en", "2.0", ID, uuid), ModelStore.parseName("zh-Hant-en-2.0-$ID-$uuid"))
    }

    @Test
    fun `every bundled catalog directory name parses back to its model`() {
        for (model in ModelCatalog.models) {
            assertEquals(
                model.directoryName,
                ModelStore.ParsedName(model.from, model.to, model.version, model.identity, null),
                ModelStore.parseName(model.directoryName),
            )
        }
    }

    @Test
    fun `names that are not published models do not parse`() {
        val garbage = listOf(
            "", "README", "random-folder", ".download-en-zh-Hans-2.2-$ID", ".trash-${UUID.randomUUID()}",
            "en-zh", "en-zh-Hans", "en-zh-2.2", "en-zh-2.2-0123", "en-zh-2.2-${ID}0", "en-zh-2.2-${ID.uppercase()}",
            "-2.2-$ID", "EN-ZH-2.2-$ID", "en-zh-v2-$ID", "en-zh-2-$ID", "en-zh-2.2-$ID-notauuid", "en-zh-2.2-$ID-",
        )
        for (name in garbage) assertNull(name, ModelStore.parseName(name))
    }

    @Test
    fun `a manifest decides the pair split ahead of the heuristic, but only for its own pair`() {
        // Both halves pass the tag shape at the first dash, so the heuristic alone reads (foo, bar-baz).
        assertEquals("foo" to "bar-baz", ModelStore.parseName("foo-bar-baz-1.0-$ID")!!.let { it.from to it.to })
        val manifest = ModelCatalog.Model("foo-bar", "baz", "1.0", emptyList())
        assertEquals("foo-bar" to "baz", ModelStore.parseName("foo-bar-baz-1.0-$ID", manifest)!!.let { it.from to it.to })
        // A manifest for some other pair is not trusted over the name.
        val other = ModelCatalog.Model("de", "en", "1.0", emptyList())
        assertEquals("foo" to "bar-baz", ModelStore.parseName("foo-bar-baz-1.0-$ID", other)!!.let { it.from to it.to })
    }

    // ---------------------------------------------------------------- versions

    @Test
    fun `versions follow the mozilla order with alphas below releases`() {
        val ascending = listOf("1.0a", "1.0a1", "1.0a2", "1.0a10", "1.0", "1.1a1", "1.1", "2.0", "2.1", "2.2", "2.3", "10.0")
        for (i in ascending.indices) for (j in ascending.indices) {
            assertEquals("${ascending[i]} vs ${ascending[j]}", i.compareTo(j).sign, ModelStore.compareVersions(ascending[i], ascending[j]).sign)
        }
        // Unparsable sorts below everything parsable and lexically among its own kind.
        assertTrue(ModelStore.compareVersions("beta", "1.0a1") < 0)
        assertTrue(ModelStore.compareVersions("1.0", "1.0b1") > 0)
        assertTrue(ModelStore.compareVersions("aaa", "bbb") < 0)
        assertEquals(0, ModelStore.compareVersions("2.2", "2.2"))
        assertEquals(0, ModelStore.compareVersions("x", "x"))
    }

    // ---------------------------------------------------------------- installed

    @Test
    fun `a downloaded directory is identified by its manifest`() {
        val fake = install("de", "en", "1.0")
        val listed = ModelStore.installed(root, verify = false).single()
        assertEquals("de", listed.from)
        assertEquals("en", listed.to)
        assertEquals("1.0", listed.version)
        assertEquals(fake.model.identity, listed.identity)
        assertEquals(fake.dir, listed.directory)
        assertEquals(fake.model, listed.model)
        assertFalse("fake bytes are not the bundled de->en", listed.isCurrentCatalogVersion)
        assertNull("not asked to hash", listed.verified)
        assertEquals("manifest included", sizeOf(fake.dir), listed.sizeBytes)
        assertEquals(fake.model.assets.associate { it.name to it.sha256 }, listed.files().expectedSha256)
        assertEquals(true, ModelStore.installed(root, verify = true).single().verified)
    }

    @Test
    fun `a directory without a manifest resolves through the bundled catalog by identity`() {
        val model = ModelCatalog.find("en", "zh-Hans")
        val dir = File(root, model.directoryName).apply { check(mkdir()) }
        for (asset in model.assets) File(dir, asset.name).writeText("not the real bytes")
        val listed = ModelStore.installed(root, verify = false).single()
        assertEquals(model, listed.model)
        assertTrue(listed.isCurrentCatalogVersion)
        assertNull(listed.verified)
        assertEquals("2.2", listed.version)
        assertEquals(false, ModelStore.installed(root, verify = true).single().verified)
    }

    @Test
    fun `a directory this SDK cannot account for is listed but never verified`() {
        unknownDir("xx-yy-1.0-$ID")
        val listed = ModelStore.installed(root, verify = true).single()
        assertEquals("xx", listed.from)
        assertEquals("yy", listed.to)
        assertEquals(ID, listed.identity)
        assertNull(listed.model)
        assertFalse(listed.isCurrentCatalogVersion)
        assertNull("nothing to check against", listed.verified)
    }

    @Test
    fun `a manifest that contradicts the directory name is ignored, as is a malformed one`() {
        val fake = install("de", "en", "1.0")
        val renamed = File(root, fake.dir.name.replace("-1.0-", "-2.0-"))
        check(fake.dir.renameTo(renamed))
        val listed = ModelStore.installed(root, verify = true).single()
        assertEquals("2.0", listed.version)
        assertNull("manifest says 1.0, the name 2.0: neither is trusted", listed.model)
        assertNull(listed.verified)

        File(renamed, ModelManifest.FILE_NAME).writeText("garbage\n")
        assertNull(ModelStore.installed(root, verify = false).single().model)
    }

    @Test
    fun `listing skips temp directories, files and unparsable names, and sorts newest first per pair`() {
        install("de", "en", "1.0", seed = "a")
        install("de", "en", "2.0", seed = "b")
        install("de", "en", "1.0a1", seed = "c")
        install("en", "zh-Hans", "2.2", seed = "d")
        install("zh-Hant", "en", "2.0", seed = "e", manifest = false)
        tempDir(".download-de-en-3.0-$ID", ageMillis = 0)
        tempDir(".trash-${UUID.randomUUID()}", ageMillis = 0)
        File(root, "notes.txt").writeText("x")
        File(root, "de-en-3.0-$ID").writeText("a file, not a directory")
        File(root, "random-folder").mkdir()

        val listed = ModelStore.installed(root, verify = false)
        assertEquals(
            listOf("de->en 2.0", "de->en 1.0", "de->en 1.0a1", "en->zh-Hans 2.2", "zh-Hant->en 2.0"),
            listed.map { "${it.from}->${it.to} ${it.version}" },
        )
    }

    @Test
    fun `a root that does not exist yet has nothing installed`() {
        assertEquals(emptyList<ModelCatalog.InstalledModel>(), ModelStore.installed(File(root, "missing"), verify = true))
    }

    // ---------------------------------------------------------------- installedFor

    @Test
    fun `installedFor returns the newest version that verifies, with hashes pinned`() {
        val old = install("de", "en", "1.0", seed = "a")
        val newest = install("de", "en", "2.0", seed = "b")
        File(newest.dir, "lex.50.50.enzh.s2t.bin").appendText("corrupt")

        val files = ModelStore.installedFor(root, "de", "en")!!
        assertEquals(old.dir, files.model.parentFile)
        assertEquals(old.model.assets.associate { it.name to it.sha256 }, files.expectedSha256)
        assertNull("other direction", ModelStore.installedFor(root, "en", "de"))

        File(old.dir, "model.enzh.intgemm.alphas.bin").writeText("also corrupt")
        assertNull(ModelStore.installedFor(root, "de", "en"))
    }

    @Test
    fun `installedFor never returns a directory it cannot verify against anything`() {
        unknownDir("de-en-9.0-$ID")
        val known = install("de", "en", "1.0")
        assertEquals(known.dir, ModelStore.installedFor(root, "de", "en")!!.model.parentFile)
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun `delete refuses a directory an engine holds and removes it once released`() {
        val fake = install("de", "en", "1.0")
        val listed = ModelStore.installed(root, verify = false).single()
        val files = ModelFiles.fromDirectory(fake.dir)

        ActiveModels.acquire(files)
        val error = assertThrows(IllegalStateException::class.java) { ModelStore.delete(root, listed) }
        assertTrue(error.message!!.contains("releaseAllModels"))
        assertTrue(fake.dir.isDirectory)

        ActiveModels.release(files)
        assertEquals(listed.sizeBytes, ModelStore.delete(root, listed))
        assertFalse(fake.dir.exists())
        assertEquals("no trash left behind", emptyList<String>(), root.list()!!.filter { it.startsWith(".trash-") })
        assertEquals("already gone", 0L, ModelStore.delete(root, listed))
    }

    @Test
    fun `delete only touches directories directly under the root, compared canonically`() {
        val fake = install("de", "en", "1.0")
        val listed = ModelStore.installed(root, verify = false).single()
        val elsewhere = createTempDirectory("other").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { ModelStore.delete(elsewhere, listed) }
            assertThrows(IllegalArgumentException::class.java) { ModelStore.delete(root, listed.copy(directory = root)) }
            assertThrows(IllegalArgumentException::class.java) { ModelStore.delete(root, listed.copy(directory = File(fake.dir, "nested"))) }
        } finally {
            elsewhere.deleteRecursively()
        }
        assertTrue(fake.dir.isDirectory)
        // A roundabout spelling of the same directory is still directly under the root.
        assertEquals(listed.sizeBytes, ModelStore.delete(root, listed.copy(directory = aliased(fake.dir))))
        assertFalse(fake.dir.exists())
    }

    @Test
    fun `deleting a pair is all or nothing with respect to the in-use check`() {
        val a = install("de", "en", "1.0", seed = "a")
        val b = install("de", "en", "2.0", seed = "b")
        install("en", "de", "1.0", seed = "c")
        val files = ModelFiles.fromDirectory(a.dir)

        ActiveModels.acquire(files)
        assertThrows(IllegalStateException::class.java) { ModelStore.delete(root, "de", "en") }
        assertTrue(a.dir.isDirectory && b.dir.isDirectory)

        ActiveModels.release(files)
        val expected = sizeOf(a.dir) + sizeOf(b.dir)
        assertEquals(expected, ModelStore.delete(root, "de", "en"))
        assertFalse(a.dir.exists() || b.dir.exists())
        assertEquals(listOf("en" to "de"), ModelStore.installed(root, verify = false).map { it.from to it.to })
        assertEquals(0L, ModelStore.delete(root, "de", "en"))
    }

    // ---------------------------------------------------------------- cleanup

    @Test
    fun `cleanup removes stale download directories, keeps fresh ones and always removes trash`() {
        val stale = tempDir(".download-de-en-1.0-$ID", ageMillis = 8 * DAY)
        val fresh = tempDir(".download-en-de-1.0-$ID", ageMillis = 6 * DAY)
        val trash = tempDir(".trash-${UUID.randomUUID()}", ageMillis = 0)

        val report = cleanup()
        assertEquals(setOf(stale, trash), report.removedTempDirs.toSet())
        assertEquals(20L, report.bytesFreed)
        assertTrue(report.removedSuperseded.isEmpty() && report.skippedInUse.isEmpty())
        assertTrue("a paused download survives", fresh.isDirectory)
        assertFalse(stale.exists() || trash.exists())

        // The clock is injectable: two days later the paused download is stale too.
        val later = ModelStore.cleanup(root, 7 * DAY, removeSuperseded = true, keep = emptySet(), now = System.currentTimeMillis() + 2 * DAY)
        assertEquals(listOf(fresh), later.removedTempDirs)
        assertFalse(fresh.exists())
    }

    @Test
    fun `a stale download directory is kept while an engine reads from it or the host says so`() {
        val held = tempDir(".download-de-en-1.0-$ID", ageMillis = 8 * DAY)
        val kept = tempDir(".download-en-de-1.0-$ID", ageMillis = 8 * DAY)
        val files = ModelFiles(File(held, "model.enzh.intgemm.alphas.bin.part"), File(held, "v.spm"), File(held, "v.spm"), File(held, "lex.bin"))

        ActiveModels.acquire(files)
        val report = cleanup(keep = setOf(aliased(kept)))
        assertEquals(listOf(held), report.skippedInUse)
        assertTrue(report.removedTempDirs.isEmpty())
        assertTrue(held.isDirectory && kept.isDirectory)
    }

    @Test
    fun `cleanup removes superseded versions only once the newest one verifies`() {
        val old = install("de", "en", "1.0", seed = "a")
        val corrupt = install("de", "en", "2.0", seed = "b")
        File(corrupt.dir, "srcvocab.enzh.spm").appendText("corrupt")

        var report = cleanup()
        assertTrue("1.0 is the best that verifies; 2.0 is not older than it", report.removedSuperseded.isEmpty())
        assertEquals(0L, report.bytesFreed)
        assertTrue(old.dir.isDirectory && corrupt.dir.isDirectory)

        val newest = install("de", "en", "3.0", seed = "c")
        val expected = sizeOf(old.dir) + sizeOf(corrupt.dir)
        report = cleanup()
        assertEquals(setOf(old.dir, corrupt.dir), report.removedSuperseded.map { it.directory }.toSet())
        assertEquals(expected, report.bytesFreed)
        assertTrue(newest.dir.isDirectory)
        assertEquals(listOf(newest.dir), ModelStore.installed(root, verify = false).map { it.directory })
        assertEquals("nothing left to trash", emptyList<String>(), root.list()!!.filter { it.startsWith('.') })
    }

    @Test
    fun `cleanup skips directories in use or in keep and leaves same-version rebuilds and strangers alone`() {
        val kept = install("de", "en", "1.0", seed = "a")
        val held = install("de", "en", "1.1", seed = "b")
        val rebuild = install("de", "en", "2.0", seed = "c")
        val best = install("de", "en", "2.0", seed = "d")
        val stranger = unknownDir("de-en-0.5-$ID")
        val files = ModelFiles.fromDirectory(held.dir)

        ActiveModels.acquire(files)
        val report = cleanup(keep = setOf(aliased(kept.dir)))
        assertEquals(listOf(held.dir), report.skippedInUse)
        assertTrue(report.removedSuperseded.isEmpty())
        assertEquals(0L, report.bytesFreed)
        assertTrue(listOf(kept.dir, held.dir, rebuild.dir, best.dir, stranger).all { it.isDirectory })

        ActiveModels.release(files)
        val again = cleanup(keep = setOf(kept.dir))
        assertEquals(listOf(held.dir), again.removedSuperseded.map { it.directory })
        assertTrue(again.skippedInUse.isEmpty())
        assertFalse(held.dir.exists())
        assertTrue("same version, other bytes: the host's call", rebuild.dir.isDirectory && best.dir.isDirectory)
        assertTrue("kept by name", kept.dir.isDirectory)
        assertTrue("nothing to verify against, nothing to judge", stranger.isDirectory)
    }

    @Test
    fun `cleanup removes a collision-suffixed twin and does nothing to models with removeSuperseded off`() {
        val primary = install("de", "en", "1.0", seed = "a")
        val twin = install("de", "en", "1.0", seed = "a", suffix = UUID.randomUUID().toString())
        val older = install("de", "en", "0.9", seed = "b")
        assertEquals(primary.model.identity, twin.model.identity)

        var report = cleanup(removeSuperseded = false)
        assertTrue(report.removedSuperseded.isEmpty() && report.removedTempDirs.isEmpty())
        assertEquals(0L, report.bytesFreed)
        assertEquals(3, ModelStore.installed(root, verify = false).size)

        report = cleanup()
        assertEquals(setOf(twin.dir, older.dir), report.removedSuperseded.map { it.directory }.toSet())
        assertTrue(primary.dir.isDirectory)
        assertEquals(listOf(primary.dir), ModelStore.installed(root, verify = false).map { it.directory })
    }

    @Test
    fun `cleanup rejects a negative age`() {
        assertThrows(IllegalArgumentException::class.java) { ModelStore.cleanup(root, -1, removeSuperseded = true, keep = emptySet()) }
    }

    // ---------------------------------------------------------------- ActiveModels

    @Test
    fun `active directories are reference counted and compared canonically`() {
        val fake = install("de", "en", "1.0")
        val files = ModelFiles.fromDirectory(fake.dir)
        val again = ModelFiles.fromDirectory(aliased(fake.dir))
        assertFalse(ActiveModels.isActive(fake.dir))

        ActiveModels.acquire(files)
        ActiveModels.acquire(again)
        assertTrue(ActiveModels.isActive(fake.dir))
        assertTrue(ActiveModels.isActive(aliased(fake.dir)))
        assertEquals(setOf(fake.dir.canonicalFile), ActiveModels.activeDirectories())

        ActiveModels.release(files)
        assertTrue("still held by the second handle", ActiveModels.isActive(fake.dir))
        ActiveModels.release(again)
        assertFalse(ActiveModels.isActive(fake.dir))
        assertTrue(ActiveModels.activeDirectories().isEmpty())

        ActiveModels.release(files) // unmatched: a no-op, not a negative count
        assertFalse(ActiveModels.isActive(fake.dir))
        ActiveModels.acquire(files)
        assertTrue(ActiveModels.isActive(fake.dir))
    }

    @Test
    fun `a custom prefix table marks its own directory active as well`() {
        val fake = install("de", "en", "1.0")
        val tables = File(root, "prefixes").apply { check(mkdir()) }
        val table = File(tables, "nonbreaking_prefix.de").apply { writeText("Dr\n") }
        val files = ModelFiles.fromDirectory(fake.dir).copy(nonbreakingPrefixFile = table)

        ActiveModels.acquire(files)
        assertTrue(ActiveModels.isActive(fake.dir))
        assertTrue(ActiveModels.isActive(tables))
        ActiveModels.release(files)
        assertFalse(ActiveModels.isActive(fake.dir))
        assertFalse(ActiveModels.isActive(tables))
    }

    @Test
    fun `symbolic links under the root are neither listed nor followed by cleanup`() {
        // A link that looks like a stale temp dir and one that looks like an old
        // model both point outside the root; the root is the boundary, so neither
        // may be listed as a model or have its target emptied.
        val outside = createTempDirectory("outside").toFile()
        val hostage = File(outside, "model.deen.intgemm.alphas.bin").apply { writeText("keep me") }
        java.nio.file.Files.createSymbolicLink(File(root, ".download-old").toPath(), outside.toPath())
        java.nio.file.Files.createSymbolicLink(File(root, "de-en-1.0-" + "a".repeat(20)).toPath(), outside.toPath())
        try {
            assertTrue(ModelStore.installed(root, verify = false).isEmpty())
            val report = ModelStore.cleanup(root, staleTempAgeMillis = 0, removeSuperseded = true, keep = emptySet())
            assertTrue(report.removedSuperseded.isEmpty())
            assertEquals("keep me", hostage.readText())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `an active nested prefix table protects its containing model directory`() {
        val old = install("de", "en", "1.0")
        val current = install("de", "en", "2.0")
        val table = File(old.dir, "prefixes/custom.txt").apply { parentFile!!.mkdir(); writeText("Dr\n") }
        val files = ModelFiles.fromDirectory(current.dir).copy(nonbreakingPrefixFile = table)
        ActiveModels.acquire(files)
        try {
            val entry = ModelStore.installed(root, false).single { it.directory == old.dir }
            assertThrows(IllegalStateException::class.java) { ModelStore.delete(root, entry) }
            assertTrue(cleanup().skippedInUse.contains(old.dir))
            assertTrue(table.isFile)
        } finally {
            ActiveModels.release(files)
        }
    }

    @Test
    fun `loading protects files before native work and a failed load frees the reservation`() {
        val fake = install("de", "en", "1.0")
        val files = ModelFiles.fromDirectory(fake.dir)
        val entry = ModelStore.installed(root, false).single()
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ActiveModels.load(files) {
                    pool.submit {
                        assertThrows(IllegalStateException::class.java) { ModelStore.delete(root, entry) }
                    }.get(5, TimeUnit.SECONDS)
                    throw IllegalArgumentException("native load failed")
                }
            }
            assertTrue(ActiveModels.activeDirectories().isEmpty())
            assertTrue(ModelStore.delete(root, entry) > 0)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a removal that starts first finishes before a new load can access the files`() {
        val fake = install("de", "en", "1.0")
        val files = ModelFiles.fromDirectory(fake.dir)
        val checked = CountDownLatch(1)
        val finishRemoval = CountDownLatch(1)
        val loading = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val removal = pool.submit {
                ActiveModels.ifInactive(listOf(fake.dir)) {
                    checked.countDown()
                    check(finishRemoval.await(5, TimeUnit.SECONDS))
                    fake.dir.deleteRecursively()
                }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            val load = pool.submit {
                loading.countDown()
                assertThrows(IllegalArgumentException::class.java) {
                    ActiveModels.load(files) { files.verify() }
                }
            }
            assertTrue(loading.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { load.get(100, TimeUnit.MILLISECONDS) }
            finishRemoval.countDown()
            removal.get(5, TimeUnit.SECONDS)
            load.get(5, TimeUnit.SECONDS)
            assertTrue(ActiveModels.activeDirectories().isEmpty())
        } finally {
            finishRemoval.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `nested symbolic links are excluded from reclaimed bytes and temp age`() {
        val outside = createTempDirectory("outside-size").toFile()
        val temp = tempDir(".download-old", 8 * DAY, bytes = 10)
        File(outside, "keep.txt").writeText("outside".repeat(100))
        val link = File(temp, "external").toPath()
        java.nio.file.Files.createSymbolicLink(link, outside.toPath())
        java.nio.file.Files.setAttribute(link, "basic:lastModifiedTime",
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 8 * DAY),
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        temp.setLastModified(System.currentTimeMillis() - 8 * DAY)
        try {
            val report = cleanup()
            assertEquals(listOf(temp), report.removedTempDirs)
            assertEquals(10L, report.bytesFreed)
            assertTrue(File(outside, "keep.txt").isFile)
        } finally {
            java.nio.file.Files.deleteIfExists(link)
            outside.deleteRecursively()
        }
    }
}
