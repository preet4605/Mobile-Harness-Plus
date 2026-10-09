package com.jarves.mh.ui

import java.io.File
import java.io.RandomAccessFile

/** Reads a bounded tail only from the selected workspace or this engine's transcript directory. */
internal object TranscriptTailReader {
    const val MAX_BYTES = 64 * 1024
    fun read(file: File, allowedRoots: List<File>): List<String> {
        val target = file.canonicalFile
        if (allowedRoots.none { target.toPath().startsWith(it.canonicalFile.toPath()) } || !target.isFile) return emptyList()
        return RandomAccessFile(target, "r").use { input ->
            val start = (input.length() - MAX_BYTES).coerceAtLeast(0)
            input.seek(start)
            val bytes = ByteArray(minOf(input.length(), MAX_BYTES.toLong()).toInt())
            input.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            (if (start > 0) text.substringAfter('\n', "") else text).lines().takeLast(500)
        }
    }

    fun resolve(path: String, filesDir: File, workspace: File?, projectSlug: String?, engineHome: String): Pair<File, List<File>>? {
        val rootfs = File(filesDir, "runtime/ubuntu")
        val guestWorkspace = projectSlug?.let { "/workspace/$it" }
        val file = when {
            workspace != null && guestWorkspace != null && path.startsWith("$guestWorkspace/") -> File(workspace, path.removePrefix("$guestWorkspace/"))
            workspace != null && path.startsWith("/workspace/") && guestWorkspace == null -> File(workspace, path.removePrefix("/workspace/"))
            path.startsWith("$engineHome/") -> File(rootfs, path.removePrefix("/"))
            else -> File(path)
        }
        val roots = listOfNotNull(workspace, File(rootfs, engineHome.removePrefix("/") + "/projects"),
            File(rootfs, engineHome.removePrefix("/") + "/sessions"))
        return file to roots
    }
}
