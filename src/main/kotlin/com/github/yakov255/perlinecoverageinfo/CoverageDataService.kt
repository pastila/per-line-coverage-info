package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

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

    private val log = CoverageLog.get(CoverageDataService::class.java)

    /** Monotonic counter incremented every time coverage data changes (set, baseline set, clear). */
    @Volatile
    var coverageGeneration: Long = 0
        private set

    /** In-memory coverage: file-path → (line-number → list-of-test-names) */
    private val data = mutableMapOf<String, Map<Int, List<String>>>()

    /** COV4 reader for disk-cached coverage (lazy per-file parsing) */
    private var cov4Reader: Cov4Reader? = null

    /** Optional baseline (master) COV4 reader for dual-coverage mode. */
    private var baselineReader: Cov4Reader? = null

    /** The commit hash that coverage data was loaded from. */
    var coverageCommitHash: String? = null
        private set

    /** Commit hash of the baseline (master) coverage, when in dual-coverage mode. */
    var baselineCommitHash: String? = null
        private set

    /** The git root directory for the project. */
    var gitRoot: java.io.File? = null
        private set

    /** Whether the current coverage is from an older (cached) commit, not the freshly resolved one. */
    var isStale: Boolean = false
        private set

    /** The merge-base between HEAD and the coverage branch at the time coverage was loaded. */
    var mergeBaseHash: String? = null
        private set

    /** The HEAD commit hash at the time coverage was loaded. */
    var headHash: String? = null
        private set

    /** How many commits the primary coverage is behind HEAD (null if unknown). */
    var primaryBehindBy: Int? = null
        private set

    /** How many commits the baseline coverage is behind merge-base (null if unknown). */
    var baselineBehindBy: Int? = null
        private set

    fun setCoverage(filePath: String, lines: Map<Int, List<String>>) {
        data[filePath] = lines
    }

    /**
     * Sets in-memory coverage data (from .covt download or local file).
     * Closes any existing COV4 reader.
     */
    fun setCoverageAll(allData: Map<String, Map<Int, List<String>>>) {
        log.info("CoverageDataService: setCoverageAll — ${allData.size} files")
        closeCov4Reader()
        data.clear()
        data.putAll(allData)
        notifyChanged()
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
        notifyChanged()
    }

    /**
     * Sets the baseline (master) reader for dual-coverage mode. Pass null to clear.
     * The baseline commit may differ from the primary [coverageCommitHash].
     */
    fun setBaselineCov4Reader(reader: Cov4Reader?, commitHash: String?) {
        closeBaselineReader()
        baselineReader = reader
        baselineCommitHash = commitHash
        if (reader != null) {
            log.info("Coverage: baseline reader set with ${reader.allFilePaths.size} files (commit ${commitHash?.take(8)})")
        }
        notifyChanged()
    }

    fun setWarningContext(
        headHash: String?,
        mergeBaseHash: String?,
        primaryBehindBy: Int?,
        baselineBehindBy: Int?,
    ) {
        this.headHash = headHash
        this.mergeBaseHash = mergeBaseHash
        this.primaryBehindBy = primaryBehindBy
        this.baselineBehindBy = baselineBehindBy
        notifyChanged()
    }

    fun clearBaseline() {
        closeBaselineReader()
        baselineReader = null
        baselineCommitHash = null
        notifyChanged()
    }

    fun hasBaseline(): Boolean = baselineReader != null

    /** Returns baseline coverage for the given file, or null if no baseline or file missing. */
    fun getBaselineCoverage(filePath: String): Map<Int, List<String>>? {
        return baselineReader?.getCoverage(filePath)
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
        log.info("CoverageDataService: clearing — commit=${coverageCommitHash?.take(8)}, files=${if (data.isNotEmpty()) data.size else cov4Reader?.allFilePaths?.size}")
        closeCov4Reader()
        closeBaselineReader()
        baselineReader = null
        baselineCommitHash = null
        data.clear()
        coverageCommitHash = null
        gitRoot = null
        isStale = false
        mergeBaseHash = null
        headHash = null
        primaryBehindBy = null
        baselineBehindBy = null
        LineMappingService.getInstance(project).clear()
        notifyChanged()
    }

    fun hasData(): Boolean = data.isNotEmpty() || cov4Reader != null

    fun setCoverageContext(commitHash: String, gitRoot: java.io.File, stale: Boolean = false) {
        log.info("CoverageDataService: setCoverageContext — commit=${commitHash.take(8)}, stale=$stale, gitRoot=$gitRoot")
        this.coverageCommitHash = commitHash
        this.gitRoot = gitRoot
        this.isStale = stale
        // Drop old-content cache entries from previous commits so the LRU stays focused on
        // files relevant to the currently active coverage. Keep the baseline commit warm too.
        val keep = setOfNotNull(commitHash, baselineCommitHash)
        LineMappingService.getInstance(project).pruneToCommits(keep)
    }

    private fun closeCov4Reader() {
        try {
            cov4Reader?.close()
        } catch (e: Exception) {
            log.warn("Failed to close COV4 reader", e)
        }
        cov4Reader = null
    }

    private fun closeBaselineReader() {
        try {
            baselineReader?.close()
        } catch (e: Exception) {
            log.warn("Failed to close baseline COV4 reader", e)
        }
        baselineReader = null
    }

    private fun notifyChanged() {
        coverageGeneration++
        project.messageBus.syncPublisher(COVERAGE_CHANGED_TOPIC).coverageChanged()
    }

    companion object {
        fun getInstance(project: Project): CoverageDataService = project.service()

        val COVERAGE_CHANGED_TOPIC = Topic(
            "CoverageDataService.COVERAGE_CHANGED", CoverageChangeListener::class.java,
        )
    }
}

fun interface CoverageChangeListener {
    fun coverageChanged()
}
