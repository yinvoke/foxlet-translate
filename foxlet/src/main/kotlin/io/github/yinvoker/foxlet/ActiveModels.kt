package io.github.yinvoker.foxlet

import java.io.File
import java.io.IOException

/**
 * Directories a live [NativeEngine] has loaded models from, so [ModelStore]
 * refuses to delete them. The engine keeps no copy of the files: an idle
 * unload drops the model and the next translation reads the same paths
 * again, so a directory removed in between fails that translation with a
 * missing-file error instead of the clean "in use" a host can act on.
 *
 * Process-wide like the engine lease itself, and synchronized because the
 * engine registers from its own thread while [ModelStore] asks from IO
 * threads. Counting rather than flagging: two [ModelFiles] can name the same
 * directory (a host that built two handles, or a custom prefix table kept
 * next to the model), and the directory is busy until the last of them is
 * released. Paths are compared canonically so a symlinked root and its
 * target count as one directory.
 *
 * Loading reserves the paths before verification or native I/O. Store removal
 * checks and mutates under the same monitor, so a removal either finishes
 * before a load starts or sees its reservation. An unresolvable path is
 * compared as spelled.
 */
internal object ActiveModels {
    /** Canonical directory -> number of resident models reading from it. */
    private val counts = HashMap<File, Int>()

    /** Register every directory [files] reads from; pair each call with one [release]. */
    @Synchronized
    fun acquire(files: ModelFiles) {
        for (directory in directoriesOf(files)) counts[directory] = (counts[directory] ?: 0) + 1
    }

    /** Undo one [acquire] of the same [files]. A release with no matching acquire is ignored. */
    @Synchronized
    fun release(files: ModelFiles) {
        for (directory in directoriesOf(files)) {
            val count = counts[directory] ?: continue
            if (count > 1) counts[directory] = count - 1 else counts.remove(directory)
        }
    }

    /** Keep a successful load reserved until release; undo a failed load. */
    fun <T> load(files: ModelFiles, action: () -> T): T {
        acquire(files)
        try {
            return action()
        } catch (e: Throwable) {
            release(files)
            throw e
        }
    }

    /** Check and remove atomically with respect to new load reservations. */
    @Synchronized
    fun <T : Any> ifInactive(directories: List<File>, action: () -> T): T? =
        if (directories.any(::isActive)) null else action()

    @Synchronized
    fun isActive(directory: File): Boolean {
        val path = canonical(directory).toPath()
        return counts.keys.any { it.toPath().startsWith(path) }
    }

    @Synchronized
    fun activeDirectories(): Set<File> = counts.keys.toSet()

    @Synchronized
    fun clearForTests() = counts.clear()

    /** Each distinct parent directory once, so acquire and release stay symmetric per call. */
    private fun directoriesOf(files: ModelFiles): Set<File> =
        (files.files() + listOfNotNull(files.nonbreakingPrefixFile))
            .flatMap { listOf(canonical(it.absoluteFile.parentFile ?: it.absoluteFile), canonical(it).parentFile ?: canonical(it)) }
            .toSet()

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile
    }
}
