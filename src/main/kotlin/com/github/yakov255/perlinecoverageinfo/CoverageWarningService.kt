package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

data class WarningDetail(
    val expected: String,
    val actual: String,
    val behindBy: Int?,
) {
    private fun formatBehind(): String = behindBy?.takeIf { it > 0 }?.let { " ($it commit(s) behind)" } ?: ""

    fun formatPrimaryText(): String =
        "Feature coverage is from $actual, not HEAD ($expected)${formatBehind()}"

    fun formatBaselineText(): String =
        "Baseline coverage is from $actual, not merge-base ($expected)${formatBehind()}"

    fun formatPrimaryHtml(): String =
        "Feature coverage is from <b>$actual</b>, not HEAD (<b>$expected</b>)${formatBehind()}"

    fun formatBaselineHtml(): String =
        "Baseline coverage is from <b>$actual</b>, not merge-base (<b>$expected</b>)${formatBehind()}"
}

data class CoverageWarnings(
    val primaryMismatch: WarningDetail?,
    val baselineMismatch: WarningDetail?,
) {
    val hasAny: Boolean get() = primaryMismatch != null || baselineMismatch != null

    fun formatPlain(): String {
        val lines = mutableListOf<String>()
        primaryMismatch?.let { lines.add(it.formatPrimaryText()) }
        baselineMismatch?.let { lines.add(it.formatBaselineText()) }
        return lines.joinToString("\n")
    }

    fun formatHtml(): String {
        val parts = mutableListOf<String>()
        primaryMismatch?.let { parts.add(it.formatPrimaryHtml()) }
        baselineMismatch?.let { parts.add(it.formatBaselineHtml()) }
        return parts.joinToString("<br>")
    }
}

@Service(Service.Level.PROJECT)
class CoverageWarningService(private val project: Project) {

    private data class CacheEntry(val generation: Long, val warnings: CoverageWarnings)

    private val cache = AtomicReference<CacheEntry?>(null)

    init {
        project.messageBus.connect().subscribe(
            CoverageDataService.COVERAGE_CHANGED_TOPIC,
            CoverageChangeListener { cache.set(null) },
        )
    }

    fun getWarnings(): CoverageWarnings {
        val dataService = CoverageDataService.getInstance(project)
        val currentGeneration = dataService.coverageGeneration
        val cached = cache.get()
        if (cached != null && cached.generation == currentGeneration) {
            return cached.warnings
        }

        val warnings = computeWarnings(dataService)
        cache.set(CacheEntry(currentGeneration, warnings))
        return warnings
    }

    private fun computeWarnings(dataService: CoverageDataService): CoverageWarnings {
        if (!dataService.hasData()) {
            return CoverageWarnings(null, null)
        }

        val primaryMismatch = checkPrimary(dataService)
        val baselineMismatch = checkBaseline(dataService)
        return CoverageWarnings(primaryMismatch, baselineMismatch)
    }

    private fun checkPrimary(dataService: CoverageDataService): WarningDetail? {
        val coverageCommit = dataService.coverageCommitHash ?: return null
        val head = dataService.headHash ?: return null
        if (coverageCommit == head) return null

        val behindBy = dataService.primaryBehindBy
        if (behindBy != null && behindBy <= 0) return null

        return WarningDetail(
            expected = head.take(8),
            actual = coverageCommit.take(8),
            behindBy = behindBy,
        )
    }

    private fun checkBaseline(dataService: CoverageDataService): WarningDetail? {
        if (!dataService.hasBaseline()) return null
        val baselineCommit = dataService.baselineCommitHash ?: return null
        val mergeBase = dataService.mergeBaseHash ?: return null
        if (baselineCommit == mergeBase) return null

        val behindBy = dataService.baselineBehindBy
        if (behindBy != null && behindBy <= 0) return null

        return WarningDetail(
            expected = mergeBase.take(8),
            actual = baselineCommit.take(8),
            behindBy = behindBy,
        )
    }

    companion object {
        fun getInstance(project: Project): CoverageWarningService = project.service()
    }
}