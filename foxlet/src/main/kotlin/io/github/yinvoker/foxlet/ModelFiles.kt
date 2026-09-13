package io.github.yinvoker.foxlet

import java.io.File

/** Local files of one translation direction (Mozilla student model layout). */
data class ModelFiles(
    val model: File,
    val srcVocab: File,
    val trgVocab: File,
    val shortlist: File,
    /**
     * Language the source text is in, used to pick the sentence-splitter prefix
     * table ([PrefixTables]). [fromDirectory] reads it off the model file
     * name; set it by hand to override that, or to null to split with the bare
     * regex. A language with no table behaves like null.
     *
     * It says nothing about what the model can translate -- the engine never
     * sees this value, only the bytes it selects.
     */
    val sourceLanguage: String? = null,
    /** Trusted, out-of-band SHA-256 by file name. Empty uses the bundled Mozilla catalog. */
    val expectedSha256: Map<String, String> = emptyMap(),
    /** Optional app-provided UTF-8 prefix table (also works with the no-prefix AAR). */
    val nonbreakingPrefixFile: File? = null,
) {
    companion object {
        /**
         * Hard-wired in the config YAML below. The engine SIGABRTs (no Java
         * exception) when mini-batch-words < 2x this value, so [EngineOptions]
         * validates against it.
         */
        const val MAX_LENGTH_BREAK = 128

        /** Directory holding model.*.bin, *vocab*.spm, lex.*.bin for one direction. */
        fun fromDirectory(dir: File): ModelFiles {
            require(dir.isDirectory) { "not a model directory: $dir" }
            val files = requireNotNull(dir.listFiles()) { "cannot read $dir" }
            return fromNames(dir, files.map { it.name }).also { resolved ->
                require(resolved.files().all { it.isFile }) { "expected regular model files in $dir" }
            }
        }

        /** Resolve the layout before downloading; the same rules as fromDirectory. */
        internal fun fromNames(dir: File, names: List<String>): ModelFiles {
            fun pick(what: String, predicate: (String) -> Boolean): File {
                val matches = names.filter(predicate)
                require(matches.size == 1) { "expected one $what file in $dir" }
                return File(dir, matches.single())
            }
            val model = pick("model") { it.startsWith("model.") && it.endsWith(".bin") }
            val shortlist = pick("shortlist") { it.startsWith("lex.") && it.endsWith(".bin") }
            val srcVocab = pick("vocab") { it.contains("vocab") && it.endsWith(".spm") && !it.startsWith("trg") }
            val targets = names.filter { it.startsWith("trgvocab") && it.endsWith(".spm") }
            val trgVocab = if (targets.isEmpty()) srcVocab else pick("target vocab") { it.startsWith("trgvocab") && it.endsWith(".spm") }
            return ModelFiles(model, srcVocab, trgVocab, shortlist, sourceLanguageOf(model.name))
        }

        /**
         * Source language of a Mozilla model file name, or null when the name
         * does not follow the convention.
         *
         * Mozilla names every direction `model.<src><trg>.<...>.bin` with two
         * ISO 639-1 letters a side: `model.enzh.intgemm.alphas.bin` is en->zh,
         * `model.jaen.intgemm.alphas.bin` is ja->en. Script variants collapse in
         * the file name (zh-Hans and zh-Hant are both `zh`), which is what the
         * prefix tables want anyway.
         *
         * Four lowercase letters after `model.` is all this has to go on, so a
         * name that merely looks like the convention yields a two-letter tag that
         * is not a real language. Harmless: an unknown tag has no table and the
         * splitter falls back to the regex.
         */
        fun sourceLanguageOf(modelFileName: String): String? =
            MODEL_NAME.matchEntire(modelFileName)?.groupValues?.get(1)

        private val MODEL_NAME = Regex("""model\.([a-z]{2})([a-z]{2})\..+\.bin""")
    }

    /**
     * Engine configuration for Marian. mini-batch-words must be at least twice
     * MAX_LENGTH_BREAK; worker count is supplied separately through JNI.
     * The shortlist flag is the integer string `1` for std::stoi. Native bundle
     * loading validates shortlist bounds regardless of caller-provided flags.
     */
    internal fun toConfigYaml(miniBatchWords: Int = 512): String = """
        models:
          - ${yamlString(model.absolutePath)}
        vocabs:
          - ${yamlString(srcVocab.absolutePath)}
          - ${yamlString(trgVocab.absolutePath)}
        shortlist:
          - ${yamlString(shortlist.absolutePath)}
          - 1
        beam-size: 1
        normalize: 1.0
        word-penalty: 0
        max-length-break: $MAX_LENGTH_BREAK
        mini-batch-words: $miniBatchWords
        max-length-factor: 2.0
        skip-cost: true
        gemm-precision: int8shiftAlphaAll
        alignment: soft
        check-bytearray: true
    """.trimIndent()
    internal fun files(): List<File> = listOf(model, srcVocab, trgVocab, shortlist).distinct()

    /** Files must stay immutable while in use; downloads install complete versioned directories. */
    internal fun cacheKey(): String = (files() + listOfNotNull(nonbreakingPrefixFile)).joinToString("\u0000") {
        require(it.isFile && it.length() > 0) { "missing or empty model file: $it" }
        "${it.canonicalPath}:${it.length()}:${it.lastModified()}"
    } + "\u0000${sourceLanguage}\u0000" + expectedSha256.toSortedMap().toString()

    internal fun verify() = Catalog.verify(this)

    private fun yamlString(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (c.code < 32 || c.code == 127) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

}
