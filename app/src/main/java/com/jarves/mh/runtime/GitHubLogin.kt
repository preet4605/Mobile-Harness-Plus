package com.jarves.mh.runtime

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** The official CLI owns auth; this function owns its bounded wait, output and child cleanup. */
internal suspend fun completeGitHubLogin(
    process: Process,
    output: File,
    onCode: (String) -> Unit,
    timeoutMs: Long = 16 * 60_000L,
) {
    var offset = 0L
    val captured = StringBuilder()
    var codePublished = false
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    try {
        while (process.isAlive || output.length() > offset) {
            currentCoroutineContext().ensureActive()
            check(System.nanoTime() < deadline) { "GitHub sign-in timed out. Request a new code." }
            val available = output.length() - offset
            if (available > 0) {
                val bytes = ByteArray(minOf(available, 16 * 1024L).toInt())
                val count = RandomAccessFile(output, "r").use { file -> file.seek(offset); file.read(bytes) }
                if (count > 0) {
                    offset += count
                    check(captured.length + count <= 64 * 1024) { "GitHub sign-in produced too much output. Try again." }
                    captured.append(bytes.decodeToString(0, count))
                    if (!codePublished) {
                        Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b").find(CodexLoginOutput.clean(captured.toString()))?.value?.let {
                            currentCoroutineContext().ensureActive()
                            codePublished = true
                            onCode(it)
                        }
                    }
                }
            } else delay(100)
        }
        currentCoroutineContext().ensureActive()
        check(process.waitFor() == 0) { "GitHub sign-in did not complete. Request a new code." }
    } finally {
        val closed = closeAuthProcess(process)
        if (closed) output.delete()
        check(closed) { "GitHub sign-in is still closing. Try again later." }
    }
}
