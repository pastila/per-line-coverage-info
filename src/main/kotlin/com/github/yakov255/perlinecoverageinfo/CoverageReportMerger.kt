package com.github.yakov255.perlinecoverageinfo

/** Incrementally consumes reports without retaining shards or copying the completed map. */
internal class CoverageReportMerger {
    private val files = LinkedHashMap<String, MutableMap<Int, List<String>>>()

    val coverage: Map<String, Map<Int, List<String>>> get() = files

    fun add(report: Map<String, Map<Int, List<String>>>) {
        for ((path, lines) in report) {
            val target = files.getOrPut(path) { LinkedHashMap() }
            // A report uses the same list object for many lines. Preserve that sharing when
            // combining reports too, without retaining an interning table between shards.
            val distinct = java.util.IdentityHashMap<List<String>, List<String>>()
            val unions = HashMap<Pair<List<String>, List<String>>, List<String>>()
            for ((line, rawTests) in lines) {
                val tests = distinct.getOrPut(rawTests) { rawTests.distinct() }
                val previous = target[line]
                target[line] = when {
                    previous == null -> tests
                    tests.isEmpty() || previous == tests -> previous
                    previous.isEmpty() -> tests
                    else -> unions.getOrPut(previous to tests) { CoverageDiff.union(previous, tests) }
                }
            }
        }
    }
}
