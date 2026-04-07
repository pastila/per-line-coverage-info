package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-level service holding per-line coverage data.
 *
 * Supports two modes:
 * - **In-memory**: coverage loaded from .covt download or local file (setCoverageAll)
 * - **Reader-backed**: coverage read on demand from a .cov4 cache file (setCov4Reader)
 *
 * The reader-backed mode avoids loading all coverage into memory — individual files
 * are parsed from the .cov4 binary only when an editor requests them.
 */
@Service(Service.Level.PROJECT)
class CoverageDataService(private val project: Project) {

    private val log = Logger.getInstance(CoverageDataService::class.java)

    /** In-memory coverage: file-path → (line-number → list-of-test-names) */
    private val data = mutableMapOf<String, Map<Int, List<String>>>()

    /** COV4 reader for disk-cached coverage (lazy per-file parsing) */
    private var cov4Reader: Cov4Reader? = null

    /** The commit hash that coverage data was loaded from. */
    var coverageCommitHash: String? = null
        private set

    /** The git root directory for the project. */
    var gitRoot: java.io.File? = null
        private set

    /** Whether the current coverage is from an older (cached) commit, not the freshly resolved one. */
    var isStale: Boolean = false
        private set

    fun setCoverage(filePath: String, lines: Map<Int, List<String>>) {
        data[filePath] = lines
    }

    /**
     * Sets in-memory coverage data (from .covt download or local file).
     * Closes any existing COV4 reader.
     */
    fun setCoverageAll(allData: Map<String, Map<Int, List<String>>>) {
        closeCov4Reader()
        data.clear()
        data.putAll(allData)
    }

    /**
     * Sets a COV4 reader for on-demand file coverage (from disk cache).
     * Clears any in-memory data.
     */
    fun setCov4Reader(reader: Cov4Reader) {
        closeCov4Reader()
        data.clear()
        cov4Reader = reader
        log.info("Coverage: using COV4 reader with ${reader.allFilePaths.size} files")
    }

    /**
     * Gets coverage for a file. Checks in-memory data first, then COV4 reader.
     */
    fun getCoverage(filePath: String): Map<Int, List<String>>? {
        data[filePath]?.let { return it }
        return cov4Reader?.getCoverage(filePath)
    }

    /**
     * Returns all file paths with coverage data.
     */
    fun allFiles(): Set<String> {
        if (data.isNotEmpty()) return data.keys
        return cov4Reader?.allFilePaths ?: emptySet()
    }

    fun clear() {
        closeCov4Reader()
        data.clear()
        coverageCommitHash = null
        gitRoot = null
        isStale = false
        LineMappingService.getInstance(project).clear()
    }

    fun hasData(): Boolean = data.isNotEmpty() || cov4Reader != null

    fun setCoverageContext(commitHash: String, gitRoot: java.io.File, stale: Boolean = false) {
        this.coverageCommitHash = commitHash
        this.gitRoot = gitRoot
        this.isStale = stale
        // Drop old-content cache entries from previous commits so the LRU stays
        // focused on files relevant to the currently active coverage.
        LineMappingService.getInstance(project).pruneToCommit(commitHash)
    }

    private fun closeCov4Reader() {
        try {
            cov4Reader?.close()
        } catch (e: Exception) {
            log.warn("Failed to close COV4 reader", e)
        }
        cov4Reader = null
    }

    companion object {
        fun getInstance(project: Project): CoverageDataService = project.service()
    }
}
