package com.github.yakov255.perlinecoverageinfo

/**
 * Merges per-artifact coverage reports into one map.
 *
 * CI publishes one report per behat shard; every shard contains the full file list, so the
 * same line is often present in several reports with different covering tests. The merged
 * value is the union of those tests, in first-seen order (primary artifact first).
 *
 * Implemented as concatenate-then-deduplicate: rebuilding a set and a list per line per
 * artifact was the dominant cost of loading large reports.
 */
object CoverageMerge {

    fun merge(reports: List<Map<String, Map<Int, List<String>>>>): Map<String, Map<Int, List<String>>> {
        if (reports.isEmpty()) return emptyMap()
        if (reports.size == 1) return reports[0]

        val merged = HashMap<String, HashMap<Int, MutableList<String>>>(reports[0].size * 2)
        for (report in reports) {
            for ((filePath, lineMap) in report) {
                val targetFile = merged.getOrPut(filePath) { HashMap(lineMap.size * 2) }
                for ((lineNumber, testNames) in lineMap) {
                    val target = targetFile.getOrPut(lineNumber) { ArrayList(testNames.size) }
                    if (testNames.isNotEmpty()) {
                        target.addAll(testNames)
                    }
                }
            }
        }

        // One reusable set: deduplicating a line allocates nothing per line.
        val seen = HashSet<String>()
        val result = HashMap<String, Map<Int, List<String>>>(merged.size)
        for ((filePath, lineMap) in merged) {
            val lines = HashMap<Int, List<String>>(lineMap.size)
            for ((lineNumber, tests) in lineMap) {
                if (tests.size > 1) {
                    seen.clear()
                    var writeIndex = 0
                    for (test in tests) {
                        if (seen.add(test)) {
                            tests[writeIndex++] = test
                        }
                    }
                    if (writeIndex < tests.size) {
                        tests.subList(writeIndex, tests.size).clear()
                    }
                }
                lines[lineNumber] = tests
            }
            result[filePath] = lines
        }
        return result
    }
}
