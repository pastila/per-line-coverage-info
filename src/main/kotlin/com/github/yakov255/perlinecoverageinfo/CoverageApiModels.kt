package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.Serializable

@Serializable
data class ApiCoverageResponse(
    val files: List<ApiFileCoverage>
)

@Serializable
data class ApiFileCoverage(
    val filePath: String,
    val lines: Map<String, Int>,  // line number to testSet index or -1
    val testSets: List<List<Int>>,  // each list is indices into tests
    val tests: List<String>
)