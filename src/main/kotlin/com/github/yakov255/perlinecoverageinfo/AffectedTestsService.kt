package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Computes the set of tests affected by current code changes by joining
 * [ChangedLinesAnalyzer]'s diff with [CoverageDataService]'s line→tests map.
 */
@Service(Service.Level.PROJECT)
class AffectedTestsService(private val project: Project) {

    private val log = CoverageLog.get(AffectedTestsService::class.java)

    data class AffectedTests(
        /** Union of all tests covering any affected old line. */
        val tests: Set<String>,
        /** Per-file (display path) → tests covering affected lines in that file. */
        val perFile: Map<String, Set<String>>,
        /** New files (no coverage to look up). */
        val newFiles: List<String>,
        /** Deleted files (nothing to run). */
        val deletedFiles: List<String>,
        /** Files that were touched but had no coverage entry at all. */
        val filesWithoutCoverage: List<String>,
        /** Mode the result was computed for, for status display. */
        val mode: ChangedLinesAnalyzer.DiffMode,
    )

    /**
     * Resolves coverage context, runs the diff, and joins lines with tests.
     * Throws [CoverageApiException] if coverage isn't loaded.
     */
    fun compute(mode: ChangedLinesAnalyzer.DiffMode): AffectedTests {
        val data = CoverageDataService.getInstance(project)
        val commit = data.coverageCommitHash
            ?: throw CoverageApiException(
                "No coverage loaded — load coverage first",
                kind = CoverageErrorKind.NO_DATA,
            )
        val gitRoot = data.gitRoot
            ?: throw CoverageApiException(
                "Coverage data has no git root context",
                kind = CoverageErrorKind.GIT,
            )

        val changes = ChangedLinesAnalyzer.analyze(gitRoot, commit, mode)
        log.info("AffectedTests: ${changes.size} changed files vs $commit (mode=$mode)")

        val perFile = linkedMapOf<String, Set<String>>()
        val newFiles = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()
        val noCoverage = mutableListOf<String>()
        val unionTests = linkedSetOf<String>()

        for (change in changes) {
            val displayPath = change.newPath ?: change.oldPath ?: continue
            if (change.isNew) {
                newFiles += displayPath
                continue
            }
            if (change.isDeleted) {
                deletedFiles += displayPath
                continue
            }
            if (change.affectedOldLines.isEmpty()) continue

            val candidates = listOfNotNull(change.oldPath, change.newPath).distinct()
            val coverage = CoveragePathResolver.resolve(data, candidates)
            if (coverage == null) {
                noCoverage += displayPath
                continue
            }

            val testsForFile = linkedSetOf<String>()
            for (line in change.affectedOldLines) {
                val tests = coverage[line] ?: continue
                testsForFile.addAll(tests)
            }
            if (testsForFile.isEmpty()) {
                noCoverage += displayPath
            } else {
                perFile[displayPath] = testsForFile
                unionTests.addAll(testsForFile)
            }
        }

        log.info("AffectedTests: ${unionTests.size} tests across ${perFile.size} files (new=${newFiles.size}, deleted=${deletedFiles.size}, noCoverage=${noCoverage.size})")
        return AffectedTests(
            tests = unionTests,
            perFile = perFile,
            newFiles = newFiles,
            deletedFiles = deletedFiles,
            filesWithoutCoverage = noCoverage,
            mode = mode,
        )
    }

    companion object {
        fun getInstance(project: Project): AffectedTestsService = project.service()
    }
}
