package com.github.yakov255.perlinecoverageinfo

data class NewCoverageLine(
    val lineNumber: Int,
    val testNames: List<String>,
)

data class NewCoverageFile(
    val gitRelativePath: String,
    val featureOnlyLines: List<NewCoverageLine>,
) {
    val displayName: String get() = gitRelativePath.substringAfterLast("/")
    val firstFeatureOnlyLine: Int get() = featureOnlyLines.first().lineNumber
    val count: Int get() = featureOnlyLines.size
}

data class NewCoverageModel(
    val files: List<NewCoverageFile>,
    val totalFiles: Int,
    val totalLines: Int,
) {
    companion object {
        val EMPTY = NewCoverageModel(emptyList(), 0, 0)
    }
}
