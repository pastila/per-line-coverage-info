package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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

        val oldContent = getOrFetchOldContent(relativePath) ?: return rawCoverage

        // Diff old content vs current editor content using ComparisonManager
        val mapping = CoverageLineMapper.computeMappingFromContent(oldContent, currentContent)
            ?: return rawCoverage // null = identical content, use raw

        return CoverageLineMapper.mapCoverage(rawCoverage, mapping)
    }

    /**
     * Clears all cached data (called when coverage data is reloaded).
     */
    fun clear() {
        oldContentCache.clear()
    }

    /**
     * Drops cache entries whose commit hash does not match [activeCommit].
     * Called from [CoverageDataService.setCoverageContext] so entries from a
     * previous coverage commit don't occupy slots in the LRU after a reload.
     * Entries for the active commit are preserved so consecutive loads that
     * resolve to the same commit stay warm.
     */
    fun pruneToCommit(activeCommit: String) {
        val it = oldContentCache.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key.first != activeCommit) it.remove()
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

    private fun getOrFetchOldContent(relativePath: String): String? {
        val dataService = CoverageDataService.getInstance(project)
        val commitHash = dataService.coverageCommitHash ?: return null
        val gitRoot = dataService.gitRoot ?: return null

        val key = commitHash to relativePath
        oldContentCache[key]?.let { return it }

        val gitPath = toGitRelativePath(relativePath) ?: return null
        val oldContent = CoverageResolver.runGitCommand(gitRoot, "show", "$commitHash:$gitPath")
        if (oldContent != null) {
            oldContentCache[key] = oldContent
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
