package io.github.yinvoker.foxlet

import java.io.File

/**
 * Asset manifest a download leaves next to the model files, in the column
 * layout of the bundled `models.tsv`. It is what lets [ModelCatalog.installed]
 * identify and later re-verify a directory whose model came from a remote
 * index rather than the bundled catalog. The name matches none of the
 * patterns [ModelFiles.fromDirectory] resolves, so the engine never sees it.
 * The manifest is as trusted as the files beside it: both live in app-private
 * storage and both were written from the same record.
 */
internal object ModelManifest {
    const val FILE_NAME = "foxlet.manifest.tsv"
    private const val HEADER = "# from\tto\tversion\tname\tsize\tsha256\turl"
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun write(directory: File, model: ModelCatalog.Model) {
        require(directory.isDirectory) { "not a directory: $directory" }
        val text = buildString {
            appendLine(HEADER)
            for (asset in model.assets.sortedBy { it.name }) {
                val row = listOf(model.from, model.to, model.version, asset.name, asset.size.toString(), asset.sha256.lowercase(), asset.url)
                require(row.all { it.isNotBlank() && it.none { c -> c == '\t' || c == '\n' || c == '\r' } }) { "manifest field must be a single non-empty line" }
                appendLine(row.joinToString("\t"))
            }
        }
        // Synced like the assets: the publishing rename is metadata only, so an
        // unsynced manifest could outlive a power cut as zero bytes.
        java.io.FileOutputStream(File(directory, FILE_NAME)).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** Null when the directory has no manifest; [IllegalArgumentException] when it has a malformed one. */
    fun read(directory: File): ModelCatalog.Model? {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return null
        require(file.length() in 1..(64L * 1024)) { "manifest has an implausible size: $file" }
        val rows = file.readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t') }
        require(rows.isNotEmpty() && rows.all { it.size == 7 && it.all(String::isNotBlank) }) { "malformed manifest: $file" }
        val key = rows.first().take(3)
        require(rows.all { it.take(3) == key }) { "manifest mixes models: $file" }
        val assets = rows.map { ModelCatalog.Asset(it[3], it[4].toLong(), it[5].lowercase(), it[6]) }.sortedBy { it.name }
        require(assets.map { it.name }.toSet().size == assets.size) { "manifest repeats a file name: $file" }
        require(assets.all { it.size > 0 && SHA256.matches(it.sha256) && it.url.startsWith("https://") }) { "malformed manifest row: $file" }
        return ModelCatalog.Model(key[0], key[1], key[2], assets)
    }
}
