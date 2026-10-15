package com.jarves.mh.runtime

import com.jarves.mh.model.DiffLineType
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class LineDiffTest {
    /** The full O(N·M) table the Changes view used before; the reference for exactness. */
    private fun referenceLcs(before: List<String>, after: List<String>): Int {
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) previous[index] + 1 else maxOf(previous[index + 1], current[index])
            }
            previous = current
        }
        return previous[after.size]
    }

    private fun randomEdit(random: Random, lines: List<String>): List<String> {
        val edited = lines.toMutableList()
        repeat(random.nextInt(0, 6)) {
            when (random.nextInt(3)) {
                0 -> edited.add(random.nextInt(edited.size + 1), "new ${random.nextInt(4)}")
                1 -> if (edited.isNotEmpty()) edited.removeAt(random.nextInt(edited.size))
                else -> if (edited.isNotEmpty()) edited[random.nextInt(edited.size)] = "changed ${random.nextInt(4)}"
            }
        }
        return edited
    }

    @Test fun lcsMatchesTheFullTableOnRandomEdits() {
        val random = Random(42)
        repeat(3000) { round ->
            // A small alphabet makes repeated lines, where alignments are ambiguous.
            val before = List(random.nextInt(0, 40)) { "line ${random.nextInt(6)}" }
            val after = if (round % 7 == 0) List(random.nextInt(0, 40)) { "line ${random.nextInt(6)}" } else randomEdit(random, before)
            assertEquals("round $round", referenceLcs(before, after), LineDiff.longestCommonSubsequence(before, after))
        }
    }

    @Test fun prefixAndSuffixNeverOverlap() {
        val lines = listOf("a", "a", "a")
        val prefix = LineDiff.commonPrefix(lines, listOf("a", "a"))
        assertEquals(2, prefix)
        assertEquals(0, LineDiff.commonSuffix(lines, listOf("a", "a"), prefix))
        assertEquals(2, LineDiff.longestCommonSubsequence(lines, listOf("a", "a")))
    }

    @Test fun diffLinesStayMinimalAndCountsAgree() {
        val random = Random(7)
        val files = Files.createTempDirectory("mh-line-diff").toFile()
        try {
            val checkpoints = WorkspaceCheckpoints(files)
            repeat(500) { round ->
                val before = List(random.nextInt(0, 60)) { "row ${random.nextInt(8)}" }
                val after = randomEdit(random, before)
                val beforeBytes = before.joinToString("\n", postfix = if (before.isEmpty()) "" else "\n").toByteArray()
                val afterBytes = after.joinToString("\n", postfix = if (after.isEmpty()) "" else "\n").toByteArray()
                val lines = checkpoints.buildDiffLines(beforeBytes, afterBytes)
                val additions = lines.count { it.type == DiffLineType.ADDITION }
                val deletions = lines.count { it.type == DiffLineType.DELETION }
                val common = referenceLcs(before, after)
                assertEquals("round $round additions", after.size - common, additions)
                assertEquals("round $round deletions", before.size - common, deletions)
                // Line numbers walk both files in order.
                var lastOld = 0
                var lastNew = 0
                lines.filter { it.type != DiffLineType.INFO }.forEach { line ->
                    line.oldLine?.let { check(it > lastOld); lastOld = it }
                    line.newLine?.let { check(it > lastNew); lastNew = it }
                }
            }
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun changeDetailsOfAOneLineEditInALargeFile() {
        val files = Files.createTempDirectory("mh-line-diff-large").toFile()
        try {
            val checkpoints = WorkspaceCheckpoints(files)
            val workspace = checkpoints.ensureWorkspace("p")
            val target = File(workspace, "Big.kt")
            target.writeText((1..1500).joinToString("\n") { "val v$it = $it" } + "\n")
            checkpoints.createCheckpoint("p", workspace)
            target.writeText(target.readText().replace("val v700 = 700\n", "val v700 = -700\nval extra = 1\n"))
            val change = checkpoints.buildChangeDetails("p", workspace, listOf("Big.kt")).single()
            assertEquals(2, change.additions)
            assertEquals(1, change.deletions)
        } finally {
            files.deleteRecursively()
        }
    }
}
