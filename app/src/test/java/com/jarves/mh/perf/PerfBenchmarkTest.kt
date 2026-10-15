package com.jarves.mh.perf

import com.jarves.mh.runtime.WorkspaceCheckpoints
import com.jarves.mh.ui.TerminalOutputTail
import com.jarves.mh.ui.parseMarkdown
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * Desktop timings for the hot paths of a prompt, so each performance change has a before and an
 * after. Opt-in: `./gradlew :app:testOnlineDebugUnitTest -Pbenchmarks --tests '*PerfBenchmarkTest*'`.
 * Prints `PERF <name>: <ms> ms`; nothing is asserted, because desktop timings vary.
 */
class PerfBenchmarkTest {
    @Test
    fun promptCheckpointsOnThreeThousandFiles() {
        val files = Files.createTempDirectory("mh-perf-checkpoint").toFile()
        try {
            val checkpoints = WorkspaceCheckpoints(files)
            val workspace = checkpoints.ensureWorkspace("perf")
            repeat(60) { dir ->
                repeat(50) { file ->
                    File(workspace, "src/module$dir/File$file.kt").apply {
                        parentFile.mkdirs()
                        writeText("package module$dir\n\n" + "fun f$file() = \"${"x".repeat(80)}\"\n".repeat(40))
                    }
                }
            }
            // Warm the JIT and the page cache once, as a phone would after the first prompt.
            checkpoints.snapshot(workspace)
            val ms = time {
                // The same calls one prompt makes: task baseline, step and bridge checkpoints, then a
                // snapshot before and after the turn.
                checkpoints.createCheckpoint("perf", workspace, WorkspaceCheckpoints.TASK_BASELINE_TAG)
                checkpoints.createCheckpoint("perf", workspace, "step-1")
                checkpoints.createCheckpoint("perf", workspace, WorkspaceCheckpoints.DEFAULT_CHECKPOINT_TAG)
                val before = checkpoints.snapshot(workspace)
                File(workspace, "src/module3/File7.kt").appendText("// edit\n")
                checkpoints.changedFiles(workspace, before)
            }
            report("prompt checkpoints, 3000 files", ms)
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun changeDetailsForOneLineEditInTwoThousandLines() {
        val files = Files.createTempDirectory("mh-perf-diff").toFile()
        try {
            val checkpoints = WorkspaceCheckpoints(files)
            val workspace = checkpoints.ensureWorkspace("perf")
            val target = File(workspace, "Big.kt")
            target.writeText((1..2000).joinToString("\n") { "val line$it = $it // ${"y".repeat(40)}" } + "\n")
            checkpoints.createCheckpoint("perf", workspace)
            target.writeText(target.readText().replace("val line1000 = 1000", "val line1000 = -1"))
            checkpoints.buildChangeDetails("perf", workspace, listOf("Big.kt"))
            val ms = time { repeat(10) { checkpoints.buildChangeDetails("perf", workspace, listOf("Big.kt")) } } / 10
            report("change details, 1 edit in 2000 lines", ms)
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun markdownWhileAReplyStreams() {
        val reply = buildString {
            repeat(60) { section ->
                append("## Section $section\n\nSome **bold** text with `code` and a [link](https://example.com).\n\n")
                append("- item one\n- item two\n\n```kotlin\nval x = $section\n```\n\n")
            }
        }
        val ms = time {
            var end = 50
            while (end < reply.length) {
                parseMarkdown(reply.substring(0, end))
                end += 50
            }
            parseMarkdown(reply)
        }
        report("markdown, ${reply.length / 1024} KB reply in 50-char steps", ms)
    }

    @Test
    fun terminalTailWithTwoHundredKilobytes() {
        val chunk = (1..300).joinToString("\n") { "\u001b[32mline $it\u001b[0m compiling module $it" }
        val ms = time {
            val tail = TerminalOutputTail(200_000)
            repeat(64) {
                tail.append(chunk)
                tail.text()
            }
            tail.finish()
        }
        report("terminal tail, 64 chunks with a publish each", ms)
    }

    private inline fun time(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun report(name: String, ms: Long) = println("PERF $name: $ms ms")
}
