package io.github.yinvoker.bergamot

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
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
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing model file fails loudly`() {
        ModelFiles.fromDirectory(dir("vocab.x.spm", "lex.x.s2t.bin"))
    }

    @Test
    fun `config yaml carries absolute paths and workspace`() {
        val d = dir(
            "model.enzh.intgemm.alphas.bin",
            "srcvocab.enzh.spm",
            "trgvocab.enzh.spm",
            "lex.50.50.enzh.s2t.bin",
        )
        val yaml = ModelFiles.fromDirectory(d).toConfigYaml(workspaceMb = 96)
        assertTrue(yaml.contains(File(d, "model.enzh.intgemm.alphas.bin").absolutePath))
        assertTrue(yaml.contains("workspace: 96"))
        assertTrue(yaml.contains("gemm-precision: int8shiftAlphaAll"))
        assertTrue(yaml.lines().none { it.startsWith(" ") && it.contains("\t") })
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
}
