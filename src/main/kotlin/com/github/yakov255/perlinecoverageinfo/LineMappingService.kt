package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.github.yakov255.perlinecoverageinfo.CoverageLog
import java.io.File
import java.nio.file.Paths

/**
 * Project-level service that caches old file content (from the coverage commit)
 * and computes line mappings on demand using ComparisonManager.
 *
 * The old content is fetched once via `git show` and cached.
 * The mapping (old→new line numbers) is recomputed from the cached old content
 * and the current editor document text whenever highlights are applied.
 */
@Service(Service.Level.PROJECT)
class LineMappingService(private val project: Project) {

    private val log = CoverageLog.get(LineMappingService::class.java)

    /**
     * Per-file cached old content, keyed by (commitHash, relativePath).
     *
     * Keyed by commit hash so that a coverage reload pointing at a different
     * commit does not surface stale content. Uses access-order LRU with a
     * hard cap so the cache can't grow unboundedly across many commits.
     */
    private val oldContentCache = object : LinkedHashMap<Pair<String, String>, String>(
        16, 0.75f, /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, String>): Boolean {
            return size > MAX_CACHED_FILES
        }
    }

    /**
     * Returns mapped coverage for a file, translating old line numbers to current ones.
     * Returns null if no coverage exists for the file.
     *
     * @param absolutePath the absolute path of the file in the editor
     * @param currentContent the current text content of the editor document
     */
    fun getMappedCoverage(absolutePath: String, currentContent: String): Map<Int, List<String>>? {
        val dataService = CoverageDataService.getInstance(project)
        val relativePath = toRelativePath(absolutePath) ?: return null

        val rawCoverage = findRawCoverage(relativePath, dataService) ?: return null
        val commitHash = dataService.coverageCommitHash ?: return rawCoverage

        return mapWithCommit(rawCoverage, relativePath, commitHash, currentContent)
    }

    /**
     * Same as [getMappedCoverage] but for the baseline (master) coverage in dual-coverage mode.
     * Returns null when no baseline is loaded or the file is absent from the baseline coverage.
     */
    fun getMappedBaselineCoverage(absolutePath: String, currentContent: String): Map<Int, List<String>>? {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasBaseline()) return null
        val baselineCommit = dataService.baselineCommitHash ?: return null
        val relativePath = toRelativePath(absolutePath) ?: return null
        val gitRelativePath = toGitRelativePath(relativePath) ?: return null
        val raw = dataService.getBaselineCoverage(gitRelativePath) ?: return null
        return mapWithCommit(raw, relativePath, baselineCommit, currentContent)
    }

    private fun mapWithCommit(
        rawCoverage: Map<Int, List<String>>,
        relativePath: String,
        commitHash: String,
        currentContent: String,
    ): Map<Int, List<String>> {
        val oldContent = getOrFetchOldContent(relativePath, commitHash) ?: return rawCoverage
        val mapping = CoverageLineMapper.computeMappingFromContent(oldContent, currentContent)
            ?: return rawCoverage
        return CoverageLineMapper.mapCoverage(rawCoverage, mapping)
    }

    /**
     * Clears all cached data (called when coverage data is reloaded).
     */
    fun clear() {
        synchronized(oldContentCache) {
            log.info("LineMappingService: clearing ${oldContentCache.size} cached entries")
            oldContentCache.clear()
        }
    }

    /**
     * Drops cache entries whose commit hash is not in [activeCommits].
     * Called from [CoverageDataService.setCoverageContext] so entries from previous
     * coverage commits don't occupy slots in the LRU after a reload, while still
     * keeping both the primary and the baseline commit warm in dual-coverage mode.
     */
    fun pruneToCommits(activeCommits: Set<String>) {
        synchronized(oldContentCache) {
            log.info("LineMappingService: pruning cache, keeping commits: $activeCommits")
            val it = oldContentCache.entries.iterator()
            while (it.hasNext()) {
                if (it.next().key.first !in activeCommits) it.remove()
            }
        }
    }

    fun toRelativePath(absolutePath: String): String? {
        val basePath = project.basePath ?: return null
        return try {
            Paths.get(basePath).relativize(Paths.get(absolutePath))
                .toString()
                .replace(File.separatorChar, '/')
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Resolves the git-relative path for a file.
     * Coverage data stores paths relative to the git root, so this is the canonical lookup key.
     */
    private fun toGitRelativePath(relativePath: String): String? {
        val dataService = CoverageDataService.getInstance(project)
        val gitRoot = dataService.gitRoot ?: return null
        val basePath = project.basePath ?: return null
        val projectDir = java.io.File(basePath)
        val absoluteFile = java.io.File(projectDir, relativePath)
        return absoluteFile.relativeTo(gitRoot).path.replace(File.separatorChar, '/')
    }

    private fun getOrFetchOldContent(relativePath: String, commitHash: String): String? {
        val dataService = CoverageDataService.getInstance(project)
        val gitRoot = dataService.gitRoot
        if (gitRoot == null) {
            log.info("LineMappingService: gitRoot is null")
            return null
        }

        val key = commitHash to relativePath
        synchronized(oldContentCache) {
            oldContentCache[key]?.let { return it }
        }

        val gitPath = toGitRelativePath(relativePath) ?: return null
        val oldContent = CoverageResolver.runGitCommand(gitRoot, "show", "$commitHash:$gitPath")
        if (oldContent != null) {
            log.info("LineMappingService: git show succeeded for $commitHash:$gitPath (${oldContent.length} bytes)")
            synchronized(oldContentCache) {
                oldContentCache[key] = oldContent
            }
        } else {
            log.warn("LineMappingService: git show failed for $commitHash:$gitPath")
        }
        return oldContent
    }

    private fun findRawCoverage(
        relativePath: String,
        dataService: CoverageDataService
    ): Map<Int, List<String>>? {
        // Coverage data stores git-root-relative paths (e.g. "api/hotels/src/Foo.php"),
        // so that is the only key we need to look up.
        val gitRelativePath = toGitRelativePath(relativePath) ?: return null
        return dataService.getCoverage(gitRelativePath)
    }

    companion object {
        /** Hard cap on the (commit, path) LRU. At ~50KB per file that's ~25MB worst case. */
        private const val MAX_CACHED_FILES = 500

        fun getInstance(project: Project): LineMappingService = project.service()
    }
}
