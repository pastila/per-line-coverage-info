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

    fun setCoverage(filePath: String, lines: Map<Int, List<String>>) {
        data[filePath] = lines
    }

    fun getCoverage(filePath: String): Map<Int, List<String>>? = data[filePath]

    fun allFiles(): Set<String> = data.keys

    fun clear() = data.clear()

    fun hasData(): Boolean = data.isNotEmpty()

    companion object {
        fun getInstance(project: Project): CoverageDataService = project.service()
    }
}
