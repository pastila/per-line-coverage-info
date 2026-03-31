package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-level service holding per-line coverage data in memory.
 * Maps normalized file paths to per-line test lists.
 */
@Service(Service.Level.PROJECT)
class CoverageDataService {

    /** file-path → (line-number → list-of-test-names) */
    private val data = mutableMapOf<String, Map<Int, List<String>>>()

    /** The commit hash that coverage data was loaded from. */
    var coverageCommitHash: String? = null
        private set

    /** The git root directory for the project. */
    var gitRoot: java.io.File? = null
        private set

    fun setCoverage(filePath: String, lines: Map<Int, List<String>>) {
        data[filePath] = lines
    }

    fun getCoverage(filePath: String): Map<Int, List<String>>? = data[filePath]

    fun allFiles(): Set<String> = data.keys

    fun clear() {
        data.clear()
        coverageCommitHash = null
        gitRoot = null
    }

    fun hasData(): Boolean = data.isNotEmpty()

    fun setCoverageContext(commitHash: String, gitRoot: java.io.File) {
        this.coverageCommitHash = commitHash
        this.gitRoot = gitRoot
    }

    companion object {
        fun getInstance(project: Project): CoverageDataService = project.service()
    }
}
