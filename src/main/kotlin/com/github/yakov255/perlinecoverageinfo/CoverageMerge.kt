package com.github.yakov255.perlinecoverageinfo

/**
 * Merges per-artifact coverage reports into one map.
 *
 * CI publishes one report per behat shard; every shard contains the full file list, so the
 * same line is often present in several reports with different covering tests. The merged
 * value is the union of those tests, in first-seen order (first report first).
 *
 * The COVT parser shares one `List<String>` per test set, so many lines of the same file
 * point at the same list instances. The union result is therefore cached by the identity
 * of the contributing lists: all lines covered by the same combination of sets share one
 * merged list.
 */
object CoverageMerge {

    data class Stats(
        val lines: Int,
        val refsIn: Long,
        val refsOut: Long,
        val unions: Int,
    )

    data class Merged(
        val coverage: Map<String, Map<Int, List<String>>>,
        val stats: Stats,
    )

    fun merge(reports: List<Map<String, Map<Int, List<String>>>>): Merged {
        if (reports.isEmpty()) {
            return Merged(emptyMap(), Stats(0, 0, 0, 0))
        }
        if (reports.size == 1) {
            // Parser output is already deduplicated per line; nothing to merge.
            var refs = 0L
            var lines = 0
            for (lineMap in reports[0].values) {
                lines += lineMap.size
                for (tests in lineMap.values) refs += tests.size
            }
            return Merged(reports[0], Stats(lines, refs, refs, 0))
        }

        val perFile = HashMap<String, HashMap<Int, Array<List<String>?>>>()
        for ((reportIndex, report) in reports.withIndex()) {
            for ((filePath, lineMap) in report) {
                val targetFile = perFile.getOrPut(filePath) { HashMap(lineMap.size * 2) }
                for ((lineNumber, testNames) in lineMap) {
                    val combo = targetFile.getOrPut(lineNumber) {
                        arrayOfNulls<List<String>>(reports.size)
                    }
                    if (testNames.isNotEmpty()) {
                        combo[reportIndex] = testNames
                    }
                }
            }
        }

        val unions = HashMap<UnionKey, List<String>>()
        var refsIn = 0L
        var refsOut = 0L
        var lineCount = 0

        val result = HashMap<String, Map<Int, List<String>>>(perFile.size)
        for ((filePath, lineMap) in perFile) {
            val lines = HashMap<Int, List<String>>(lineMap.size)
            for ((lineNumber, combo) in lineMap) {
                for (tests in combo) {
                    if (tests != null) refsIn += tests.size
                }
                val union = unions.getOrPut(UnionKey(combo)) { unionOf(combo) }
                refsOut += union.size
                lineCount++
                lines[lineNumber] = union
            }
            result[filePath] = lines
        }

        return Merged(result, Stats(lineCount, refsIn, refsOut, unions.size))
    }

    private fun unionOf(combo: Array<List<String>?>): List<String> {
        var total = 0
        for (tests in combo) {
            if (tests != null) total += tests.size
        }
        if (total == 0) return emptyList()

        val union = ArrayList<String>(total)
        val seen = HashSet<String>(total * 2)
        for (tests in combo) {
            if (tests == null) continue
            for (test in tests) {
                if (seen.add(test)) union.add(test)
            }
        }
        union.trimToSize()
        return union
    }

    /** Identity key for a combination of contributing lists. */
    private class UnionKey(private val combo: Array<List<String>?>) {
        private val hash: Int = run {
            var h = 1
            for (tests in combo) {
                h = 31 * h + System.identityHashCode(tests)
            }
            h
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is UnionKey) return false
            val that = other.combo
            if (that.size != combo.size) return false
            for (i in combo.indices) {
                if (combo[i] !== that[i]) return false
            }
            return true
        }
    }
}
