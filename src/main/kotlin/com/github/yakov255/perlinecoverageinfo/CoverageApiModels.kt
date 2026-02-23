package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.Serializable

@Serializable
data class ApiCoverageResponse(
    val files: Map<String, ApiFileCoverage>
)

@Serializable
data class ApiLineCoverage(
    val covered: Boolean,
    val hits: Int
)

@Serializable
data class ApiFileCoverage(
    val lines: Map<Int, ApiLineCoverage>
)