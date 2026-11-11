package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-level service holding per-line coverage data.
 *
 * Coverage is read on demand from a `.cov4` cache file ([setCov4Reader]): individual files
 * are parsed from the binary only when an editor requests them, so a large dataset is never
 * loaded into memory at once. Dual-coverage mode adds a second baseline reader.
 */
@Service(Service.Level.PROJECT)
class CoverageDataService(private val project: Project) {

    private val log = CoverageLog.get(CoverageDataService::class.java)

    /**
     * Everything the service knows about the active coverage, published as one immutable value.
     * A reader of [snapshot] sees the reader, the commit and the warning context of the same
     * moment, never a mix of two loads.
     */
    data class Snapshot(
        /** COV4 reader for disk-cached coverage (lazy per-file parsing). */
        val primary: Cov4Reader? = null,
        /** Optional baseline (master) COV4 reader for dual-coverage mode. */
        val baseline: Cov4Reader? = null,
        /** The commit hash that coverage data was loaded from. */
        val commit: String? = null,
        /** Commit hash of the baseline (master) coverage, when in dual-coverage mode. */
        val baselineCommit: String? = null,
        /** The git root directory for the project. */
        val gitRoot: java.io.File? = null,
        /** Whether the current coverage is from an older (cached) commit, not the freshly resolved one. */
        val stale: Boolean = false,
        /** The merge-base between HEAD and the coverage branch at the time coverage was loaded. */
        val mergeBaseHash: String? = null,
        /** The HEAD commit hash at the time coverage was loaded. */
        val headHash: String? = null,
        /** How many commits the primary coverage is behind HEAD (null if unknown). */
        val primaryBehindBy: Int? = null,
        /** How many commits the baseline coverage is behind merge-base (null if unknown). */
        val baselineBehindBy: Int? = null,
    )

    private val lock = Any()

    /** Replaced as a whole under [lock]; readers take it without locking. */
    @Volatile
    private var state = Snapshot()

    private val generation = AtomicLong(0)

    /** Monotonic counter incremented every time coverage data changes (set, baseline set, clear). */
    val coverageGeneration: Long get() = generation.get()

    /** The current consistent view of all coverage state. */
    fun snapshot(): Snapshot = state

    val coverageCommitHash: String? get() = state.commit
    val baselineCommitHash: String? get() = state.baselineCommit
    val gitRoot: java.io.File? get() = state.gitRoot
    val isStale: Boolean get() = state.stale
    val mergeBaseHash: String? get() = state.mergeBaseHash
    val headHash: String? get() = state.headHash
    val primaryBehindBy: Int? get() = state.primaryBehindBy
    val baselineBehindBy: Int? get() = state.baselineBehindBy

    /**
     * Swaps the snapshot under the lock and closes the readers the new one no longer holds.
     * Closing happens inside the lock so two racing updates cannot close each other's reader twice.
     */
    private fun update(change: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            val old = state
            val new = change(old)
            state = new
            if (old.primary != null && old.primary !== new.primary) closeQuietly(old.primary, "COV4")
            if (old.baseline != null && old.baseline !== new.baseline) closeQuietly(old.baseline, "baseline COV4")
        }
    }

    /**
     * Sets a COV4 reader for on-demand file coverage (from disk cache).
     */
    fun setCov4Reader(reader: Cov4Reader) {
        update { it.copy(primary = reader) }
        log.info("Coverage: using COV4 reader with ${reader.allFilePaths.size} files")
        notifyChanged()
    }

    /**
     * Sets the primary context and, when given, its reader in one step, so no observer sees the
     * new commit with the old reader. A null [reader] keeps the current one (same commit again).
     */
    fun setCoverage(commitHash: String, gitRoot: java.io.File, stale: Boolean, reader: Cov4Reader?) {
        log.info("CoverageDataService: setCoverage — commit=${commitHash.take(8)}, stale=$stale, reader=${reader != null}")
        var keep: Set<String> = emptySet()
        update {
            keep = setOfNotNull(commitHash, it.baselineCommit)
            it.copy(commit = commitHash, gitRoot = gitRoot, stale = stale, primary = reader ?: it.primary)
        }
        LineMappingService.getInstance(project).pruneToCommits(keep)
        if (reader != null) notifyChanged()
    }

    /**
     * Sets the baseline (master) reader for dual-coverage mode. Pass null to clear.
     * The baseline commit may differ from the primary [coverageCommitHash].
     */
    fun setBaselineCov4Reader(reader: Cov4Reader?, commitHash: String?) {
        update { it.copy(baseline = reader, baselineCommit = commitHash) }
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
        update {
            it.copy(
                headHash = headHash,
                mergeBaseHash = mergeBaseHash,
                primaryBehindBy = primaryBehindBy,
                baselineBehindBy = baselineBehindBy,
            )
        }
        notifyChanged()
    }

    fun clearBaseline() {
        update { it.copy(baseline = null, baselineCommit = null) }
        notifyChanged()
    }

    fun hasBaseline(): Boolean = state.baseline != null

    /** Returns baseline coverage for the given file, or null if no baseline or file missing. */
    fun getBaselineCoverage(filePath: String): Map<Int, List<String>>? {
        return state.baseline?.getCoverage(filePath)
    }

    /**
     * Gets coverage for a file from the COV4 reader.
     */
    fun getCoverage(filePath: String): Map<Int, List<String>>? = state.primary?.getCoverage(filePath)

    /**
     * Returns all file paths with coverage data.
     */
    fun allFiles(): Set<String> = state.primary?.allFilePaths ?: emptySet()

    fun clear() {
        val before = state
        log.info("CoverageDataService: clearing — commit=${before.commit?.take(8)}, files=${before.primary?.allFilePaths?.size}")
        update { Snapshot() }
        LineMappingService.getInstance(project).clear()
        notifyChanged()
    }

    fun hasData(): Boolean = state.primary != null

    fun setCoverageContext(commitHash: String, gitRoot: java.io.File, stale: Boolean = false) {
        log.info("CoverageDataService: setCoverageContext — commit=${commitHash.take(8)}, stale=$stale, gitRoot=$gitRoot")
        var keep: Set<String> = emptySet()
        update {
            keep = setOfNotNull(commitHash, it.baselineCommit)
            it.copy(commit = commitHash, gitRoot = gitRoot, stale = stale)
        }
        // Drop old-content cache entries from previous commits so the LRU stays focused on
        // files relevant to the currently active coverage. Keep the baseline commit warm too.
        LineMappingService.getInstance(project).pruneToCommits(keep)
    }

    private fun closeQuietly(reader: Cov4Reader, what: String) {
        try {
            reader.close()
        } catch (e: Exception) {
            log.warn("Failed to close $what reader", e)
        }
    }

    private fun notifyChanged() {
        generation.incrementAndGet()
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
