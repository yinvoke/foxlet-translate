package io.github.yinvoker.foxlet

/** Languages with native, compiled-in abbreviation rules. No data serialization or resource loading. */
internal object PrefixTables {
    // zh/ja/ko reuse Latin abbreviations in mixed text; punctuation has native locale rules.
    // Checked against native/sentence/rules.h by the sentence test tooling.
    val languages: Set<String> = setOf("en", "de", "fr", "es", "pt", "it", "ru", "tr", "zh", "ja", "ko")
}

/** Keep locale punctuation active even when abbreviation rules are disabled. */
internal fun ModelFiles.toNativeConfigYaml(miniBatchWords: Int = 512, nonbreakingPrefixes: Boolean = true): String =
    toConfigYaml(miniBatchWords) + "\nssplit-builtin: ${nonbreakingPrefixes && nonbreakingPrefixFile == null}"
