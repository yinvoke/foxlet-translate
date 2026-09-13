package io.github.yinvoker.foxlet

/**
 * Moses-style "nonbreaking prefix" tables, one per language, packaged in the AAR.
 *
 * The engine splits a paragraph into sentences with a regex that ends a sentence
 * at `.`, `?` or `!`. Without a prefix table it has no way to know that the dot
 * in `Dr. Smith`, `e.g.`, `U.S.` or `No. 5` is not one, so it cuts there and
 * translates the halves independently — which loses the context the model needs
 * and, for a trailing abbreviation, produces a sentence that is one word long.
 * A prefix table lists the tokens whose following period never ends a sentence
 * (`# NUMERIC_ONLY #` marks the ones that only count in front of a digit).
 *
 * The tables are the ones vendored with ssplit-cpp under
 * `engine/3rd_party/ssplit-cpp/nonbreaking_prefixes/` and are copied verbatim.
 * They cover 25 languages and add ~120 KB to the AAR. Japanese, Korean, Thai and
 * the other languages without a table are unaffected: they either end sentences
 * with a non-Latin terminator or simply keep today's regex-only behaviour.
 *
 * Loaded as a Java resource, so no `Context` is needed and the lookup works
 * identically in JVM unit tests and on device.
 */
internal object PrefixTables {

    /** Language tags that have a table. Two-letter ISO 639-1, plus `yue`. */
    val languages: Set<String> = setOf(
        "ca", "cs", "de", "el", "en", "es", "fi", "fr", "ga", "hu", "is", "it", "lt",
        "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "ta", "yue", "zh",
    )

    /**
     * Table bytes for [language], or null when there is none — including for a
     * null or unrecognised tag, which is the "just use the regex" case and never
     * an error.
     *
     * The tag is matched on its primary subtag, case-insensitively, so `zh-Hans`,
     * `zh-Hant` and `zh` all resolve to the same Chinese table and `en-GB` to the
     * English one.
     */
    fun bytesFor(language: String?): ByteArray? {
        val tag = primarySubtag(language) ?: return null
        if (tag !in languages) return null
        return javaClass.getResourceAsStream("$RESOURCE_DIR/nonbreaking_prefix.$tag")?.use { it.readBytes() }
    }

    private fun primarySubtag(language: String?): String? {
        val trimmed = language?.trim()?.lowercase() ?: return null
        if (trimmed.isEmpty()) return null
        return trimmed.split('-', '_').first().ifEmpty { null }
    }

    /** Absolute resource path; `getResourceAsStream` on a class resolves it from the jar root. */
    private const val RESOURCE_DIR = "/io/github/yinvoker/foxlet/nonbreaking_prefixes"
}
