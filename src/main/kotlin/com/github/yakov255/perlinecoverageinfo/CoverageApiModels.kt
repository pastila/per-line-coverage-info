package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.Serializable

@Serializable
data class BranchCommitsResponse(
    val commits: List<String>
)

@Serializable
data class IdeFileCoverageResponse(
    val lines: Map<String, Int>,       // line number -> testSet index (-1 = uncovered)
    val testSets: List<List<Int>>,     // each set is a list of indices into `tests`
    val tests: List<String>
) {
    /**
     * Resolves the compact representation into a map of line number to test names.
     */
    fun resolveLines(): Map<Int, List<String>> {
        val result = mutableMapOf<Int, List<String>>()
        val resolvedSets = mutableMapOf<Int, List<String>>()
        for ((lineStr, setIndex) in lines) {
            val lineNum = lineStr.toIntOrNull() ?: continue
            if (setIndex < 0 || setIndex >= testSets.size) {
                result[lineNum] = emptyList()
            } else {
                result[lineNum] = resolvedSets.getOrPut(setIndex) {
                    testSets[setIndex].mapNotNull { tests.getOrNull(it) }
                }
            }
        }
        return result
    }
}