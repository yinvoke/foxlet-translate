package io.github.yinvoker.foxlet

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Pinned Mozilla models. The index ships with the SDK; text is never sent to a server. */
object ModelCatalog {
    data class Asset(val name: String, val size: Long, val sha256: String, val url: String)
    data class Model(val from: String, val to: String, val version: String, val assets: List<Asset>) {
        val sizeBytes: Long get() = assets.sumOf { it.size }
        internal val identity: String get() = digest(assets.joinToString { it.sha256 }.toByteArray()).take(20)
    }
    val models: List<Model> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/io/github/yinvoker/foxlet/models.tsv")) { "Model catalog missing from AAR" }
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t') }.toList()
                .groupBy { it.take(3) }.map { (key, rows) ->
                    Model(key[0], key[1], key[2], rows.map {
                        Asset(it[3], it[4].toLong(), it[5], it[6])
                    })
                }
        }
    }
    fun find(from: String, to: String): Model = models.singleOrNull { it.from == from && it.to == to }
        ?: throw IllegalArgumentException("No direct model for $from → $to")

    private val downloadLock = Mutex()

    /**
     * Download to a fresh directory under [root], check size and SHA-256, then atomically
     * publish that directory. Never updates an active model in place. [onProgress] runs
     * on an IO thread. Cancellation/network failure removes only this download's temp files.
     * Use app-private storage; model bytes must not be modified while an engine uses them.
     */
    suspend fun download(
        root: File, from: String, to: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): ModelFiles = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            val model = find(from, to)
            require(root.isDirectory || root.mkdirs()) { "Cannot create model directory: $root" }
            val destination = File(root, "${model.from}-${model.to}-${model.version}-${model.identity}")
            val hashes = model.assets.associate { it.name to it.sha256 }
            if (destination.isDirectory) {
                try {
                    val existing = ModelFiles.fromDirectory(destination).copy(expectedSha256 = hashes)
                    existing.verify()
                    onProgress(model.sizeBytes, model.sizeBytes)
                    return@withLock existing
                } catch (_: IllegalArgumentException) { /* Download a fresh bundle; don't overwrite old files. */ }
            }
            val temporary = File(root, ".download-${UUID.randomUUID()}")
            check(temporary.mkdir()) { "Cannot create download directory" }
            var completed = 0L
            try {
                for (asset in model.assets) {
                    currentCoroutineContext().ensureActive()
                    require(asset.name == File(asset.name).name && asset.name != "." && asset.name != "..")
                    val url = URL(asset.url)
                    require(url.protocol == "https") { "Model downloads require HTTPS" }
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.instanceFollowRedirects = false
                    try {
                        check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Model download returned HTTP ${connection.responseCode}" }
                        val output = File(temporary, asset.name)
                        val sha = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        connection.inputStream.use { input ->
                            output.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    count += n
                                    require(count <= asset.size) { "Model download exceeds expected size" }
                                    sha.update(buffer, 0, n)
                                    out.write(buffer, 0, n)
                                    onProgress(completed + count, model.sizeBytes)
                                }
                                out.fd.sync()
                            }
                        }
                        require(count == asset.size && hex(sha.digest()) == asset.sha256) { "Model checksum mismatch: ${asset.name}" }
                        completed += count
                    } finally { connection.disconnect() }
                }
                currentCoroutineContext().ensureActive()
                val published = if (!destination.exists()) destination else File(root, "${destination.name}-${UUID.randomUUID()}")
                check(temporary.renameTo(published)) { "Cannot publish downloaded model directory" }
                ModelFiles.fromDirectory(published).copy(expectedSha256 = hashes)
            } finally { if (temporary.exists()) temporary.deleteRecursively() }
        }
    }

    internal fun verify(files: ModelFiles) {
        val paths = files.files()
        val hashes = paths.associate { file ->
            require(file.isFile && file.length() in 1..(1024L * 1024 * 1024)) { "Missing, empty or oversized file: $file" }
            file.name to sha256(file)
        }
        require(hashes.size == paths.size) { "Bundle has duplicate file names" }
        val expected = files.expectedSha256
        if (expected.isNotEmpty()) {
            require(expected.keys == hashes.keys && expected.all { (name, sha) -> sha.matches(Regex("[0-9a-fA-F]{64}")) && sha.equals(hashes[name], ignoreCase = true) }) {
                "Model SHA-256 mismatch; use hashes from a trusted publisher, not the downloaded file"
            }
        } else {
            require(models.any { model ->
                model.assets.associate { it.name to it.sha256 } == hashes
            }) { "Files do not match a bundled Mozilla model; provide a complete trusted expectedSha256 manifest for a custom model" }
        }
    }
    internal fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
        }
        return hex(sha.digest())
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
