package io.github.yinvoker.bergamot

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilesTest {

    private fun dir(vararg names: String): File {
        val d = createTempDirectory("mf").toFile()
        names.forEach { File(d, it).writeText("x") }
        return d
    }

    @Test
    fun `mozilla dual-vocab layout resolves all four files`() {
        val d = dir(
            "model.enzh.intgemm.alphas.bin",
            "srcvocab.enzh.spm",
            "trgvocab.enzh.spm",
            "lex.50.50.enzh.s2t.bin",
        )
        val m = ModelFiles.fromDirectory(d)
        assertEquals("model.enzh.intgemm.alphas.bin", m.model.name)
        assertEquals("srcvocab.enzh.spm", m.srcVocab.name)
        assertEquals("trgvocab.enzh.spm", m.trgVocab.name)
        assertEquals("lex.50.50.enzh.s2t.bin", m.shortlist.name)
        assertEquals("en", m.sourceLanguage)
    }

    @Test
    fun `single shared vocab is used for both sides`() {
        val d = dir(
            "model.jaen.intgemm.alphas.bin",
            "vocab.jaen.spm",
            "lex.50.50.jaen.s2t.bin",
        )
        val m = ModelFiles.fromDirectory(d)
        assertEquals("vocab.jaen.spm", m.srcVocab.name)
        assertEquals(m.srcVocab, m.trgVocab)
        assertEquals("ja", m.sourceLanguage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing model file fails loudly`() {
        ModelFiles.fromDirectory(dir("vocab.x.spm", "lex.x.s2t.bin"))
    }

    @Test
    fun `config yaml carries absolute paths and no dead keys`() {
        val d = dir(
            "model.enzh.intgemm.alphas.bin",
            "srcvocab.enzh.spm",
            "trgvocab.enzh.spm",
            "lex.50.50.enzh.s2t.bin",
        )
        val yaml = ModelFiles.fromDirectory(d).toConfigYaml(workspaceMb = 96)
        assertTrue(yaml.contains(File(d, "model.enzh.intgemm.alphas.bin").absolutePath))
        assertTrue(yaml.contains("gemm-precision: int8shiftAlphaAll"))
        assertTrue(yaml.lines().none { it.startsWith(" ") && it.contains("\t") })
        // Keys nothing on this link path reads; see toConfigYaml's doc comment.
        assertFalse(yaml.contains("workspace"))
        assertFalse(yaml.contains("cpu-threads"))
        assertFalse(yaml.contains("quiet"))
        // The engine aborts without this one -- marian defines no default.
        assertTrue(yaml.contains("mini-batch-words: 512"))
        // shortlist[1] reaches std::stoi, so it has to parse as an int.
        assertTrue(yaml.contains("\n  - 0"))
    }

    @Test
    fun `config yaml defaults to mini-batch-words 512`() {
        val d = dir(
            "model.enzh.intgemm.alphas.bin",
            "srcvocab.enzh.spm",
            "trgvocab.enzh.spm",
            "lex.50.50.enzh.s2t.bin",
        )
        val files = ModelFiles.fromDirectory(d)
        assertTrue(files.toConfigYaml(workspaceMb = 128).contains("mini-batch-words: 512"))
        assertTrue(files.toConfigYaml(workspaceMb = 128, miniBatchWords = 1024).contains("mini-batch-words: 1024"))
    }

    @Test
    fun `engine config rejects mini-batch-words below 2x max-length-break`() {
        // 2 * MAX_LENGTH_BREAK is the documented floor: below it the engine
        // SIGABRTs the whole process instead of throwing.
        EngineConfig(miniBatchWords = 2 * ModelFiles.MAX_LENGTH_BREAK) // boundary OK
        assertThrows(IllegalArgumentException::class.java) {
            EngineConfig(miniBatchWords = 2 * ModelFiles.MAX_LENGTH_BREAK - 1)
        }
    }

    @Test
    fun `engine config rejects negative cache size`() {
        EngineConfig(cacheSize = 0)
        EngineConfig(cacheSize = 4096)
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(cacheSize = -1) }
    }

    @Test
    fun `source language comes off the mozilla model file name`() {
        assertEquals("en", ModelFiles.sourceLanguageOf("model.enzh.intgemm.alphas.bin"))
        assertEquals("ja", ModelFiles.sourceLanguageOf("model.jaen.intgemm.alphas.bin"))
        assertEquals("de", ModelFiles.sourceLanguageOf("model.deen.intgemm.alphas.bin"))
        // Both zh-Hans and zh-Hant ship as `zh` in the file name, and both want
        // the same prefix table.
        assertEquals("zh", ModelFiles.sourceLanguageOf("model.zhen.intgemm.alphas.bin"))
    }

    @Test
    fun `unrecognised model file name infers no source language`() {
        assertNull(ModelFiles.sourceLanguageOf("model.bin"))
        assertNull(ModelFiles.sourceLanguageOf("model.enzh.bin")) // no middle segment
        assertNull(ModelFiles.sourceLanguageOf("model.en.intgemm.alphas.bin")) // direction too short
        assertNull(ModelFiles.sourceLanguageOf("model.ENZH.intgemm.alphas.bin")) // upper case
        assertNull(ModelFiles.sourceLanguageOf("student.enzh.intgemm.alphas.bin"))
        assertNull(ModelFiles.sourceLanguageOf("model.enzh.intgemm.alphas.npz"))
    }

    @Test
    fun `a directory whose model name says nothing gets a null source language`() {
        val d = dir("model.bin", "vocab.spm", "lex.bin")
        assertNull(ModelFiles.fromDirectory(d).sourceLanguage)
    }

    @Test
    fun `every advertised prefix table is on the classpath and parses as one`() {
        assertEquals(25, NonbreakingPrefixes.languages.size)
        for (language in NonbreakingPrefixes.languages) {
            val bytes = NonbreakingPrefixes.bytesFor(language)
            assertNotNull("no table for $language", bytes)
            assertTrue("empty table for $language", bytes!!.isNotEmpty())
            // A prefix table is a line-oriented text file; anything else means we
            // packaged the wrong bytes.
            val text = String(bytes, Charsets.UTF_8)
            assertTrue("$language has no prefix lines", text.lines().any { it.isNotBlank() && !it.startsWith("#") })
        }
    }

    @Test
    fun `english table protects the abbreviations that break sentence splitting`() {
        val text = String(NonbreakingPrefixes.bytesFor("en")!!, Charsets.UTF_8).lines().map { it.trim() }
        for (prefix in listOf("Dr", "Mr", "Mrs", "Prof", "St", "e.g", "i.e")) {
            assertTrue("en table is missing $prefix", text.any { it == prefix || it.startsWith("$prefix ") })
        }
        // NUMERIC_ONLY entries only suppress the break in front of a digit.
        assertTrue(text.any { it.startsWith("No ") && it.contains("NUMERIC_ONLY") })
    }

    @Test
    fun `language tags resolve on their primary subtag`() {
        val zh = NonbreakingPrefixes.bytesFor("zh")!!
        assertArrayEquals(zh, NonbreakingPrefixes.bytesFor("zh-Hans"))
        assertArrayEquals(zh, NonbreakingPrefixes.bytesFor("zh-Hant"))
        assertArrayEquals(zh, NonbreakingPrefixes.bytesFor("ZH"))
        assertArrayEquals(NonbreakingPrefixes.bytesFor("en"), NonbreakingPrefixes.bytesFor("en_GB"))
    }

    @Test
    fun `a language without a table is not an error`() {
        // Japanese ends sentences with a character the regex already handles, so
        // ssplit-cpp ships no ja table; nor one for ko, th or ar.
        for (language in listOf("ja", "ko", "th", "ar", "xx", "", "  ", null)) {
            assertNull("unexpected table for $language", NonbreakingPrefixes.bytesFor(language))
        }
    }

    @Test
    fun `nonbreaking prefixes are on by default and can be turned off`() {
        assertTrue(EngineConfig().nonbreakingPrefixes)
        assertFalse(EngineConfig(nonbreakingPrefixes = false).nonbreakingPrefixes)
    }
}
