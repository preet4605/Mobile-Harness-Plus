package com.jarves.mh.runtime

/** Line-diff helpers for the Changes view: exact, but proportional to the size of the edit. */
internal object LineDiff {
    fun commonPrefix(before: List<String>, after: List<String>): Int {
        val limit = minOf(before.size, after.size)
        var prefix = 0
        while (prefix < limit && before[prefix] == after[prefix]) prefix++
        return prefix
    }

    /** Common trailing lines, never overlapping the first [prefix] lines. */
    fun commonSuffix(before: List<String>, after: List<String>, prefix: Int): Int {
        val limit = minOf(before.size, after.size) - prefix
        var suffix = 0
        while (suffix < limit && before[before.size - 1 - suffix] == after[after.size - 1 - suffix]) suffix++
        return suffix
    }

    /**
     * Length of the longest common subsequence of lines. Trims the common top and bottom, then runs
     * Myers' O((N+M)·D) greedy search in O(N+M) memory, where D is the number of added plus removed
     * lines: a one-line edit in a 2,000-line file takes a few thousand steps instead of four million.
     */
    fun longestCommonSubsequence(before: List<String>, after: List<String>): Int {
        val prefix = commonPrefix(before, after)
        val suffix = commonSuffix(before, after, prefix)
        val n = before.size - prefix - suffix
        val m = after.size - prefix - suffix
        if (n == 0 || m == 0) return prefix + suffix
        val max = n + m
        val offset = max + 1
        // v[k + offset]: furthest x reached on diagonal k = x - y.
        val v = IntArray(2 * max + 3)
        for (d in 0..max) {
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                    v[k + 1 + offset]
                } else {
                    v[k - 1 + offset] + 1
                }
                var y = x - k
                while (x < n && y < m && before[prefix + x] == after[prefix + y]) {
                    x++
                    y++
                }
                v[k + offset] = x
                if (x >= n && y >= m) return prefix + suffix + (n + m - d) / 2
                k += 2
            }
        }
        return prefix + suffix
    }
}
