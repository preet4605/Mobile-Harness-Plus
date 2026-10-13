package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Reap a short-lived auth/catalog child before removing its output or credentials. */
internal fun closeAuthProcess(process: Process): Boolean {
    runCatching { process.outputStream.close() }
    if (process.isAlive) runCatching { process.destroyForcibly() }
    val deadline = System.nanoTime() + 3_000_000_000L
    while (process.isAlive && System.nanoTime() < deadline) {
        try { Thread.sleep(25) } catch (_: InterruptedException) { /* Finish bounded child cleanup. */ }
    }
    return !process.isAlive
}

/** Delete an owned auth directory without following guest-created links into project data. */
internal fun deleteAuthDirectory(directory: File) {
    val path = directory.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
