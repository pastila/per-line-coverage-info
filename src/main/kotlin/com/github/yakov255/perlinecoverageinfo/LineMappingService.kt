package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

/**
 * Project-level service that caches line mappings (old commit → current document)
 * and provides mapped coverage data for the highlighter.
 *
 * The mapping is computed lazily per file when first requested, then updated
 * incrementally via [updateMapping] when the document changes.
 */
@Service(Service.Level.PROJECT)
class LineMappingService(private val project: Project) {

    private val log = Logger.getInstance(LineMappingService::class.java)

    /**
     * Per-file line mapping: relativePath → (oldLine → newLine).
     * A null value means "not yet computed".
     * An empty entry is never stored — if mapping is identity, the key is absent.
     */
    private val mappings = mutableMapOf<String, Map<Int, Int>>()

    /** Per-file old content lines cache (for incremental updates). */
    private val oldContentCache = mutableMapOf<String, List<String>>()

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

        val rawCoverage = findRawCoverage(absolutePath, relativePath, dataService) ?: return null

        // Get or compute the line mapping
        val mapping = getOrComputeMapping(relativePath, currentContent)
            ?: return rawCoverage // null mapping = identity (file unchanged)

        return CoverageLineMapper.mapCoverage(rawCoverage, mapping)
    }

    /**
     * Sets a pre-computed mapping for a file (used after incremental updates).
     */
    fun setMapping(relativePath: String, mapping: Map<Int, Int>) {
        mappings[relativePath] = mapping
    }

    /**
     * Gets the current mapping for a file, or null if identity/not computed.
     */
    fun getMapping(relativePath: String): Map<Int, Int>? = mappings[relativePath]

    /**
     * Gets the cached old content lines for a file.
     */
    fun getOldContentLines(relativePath: String): List<String>? = oldContentCache[relativePath]

    /**
     * Clears all cached mappings (called when coverage data is reloaded).
     */
    fun clear() {
        mappings.clear()
        oldContentCache.clear()
    }

    /**
     * Invalidates the mapping for a specific file, forcing recomputation on next access.
     */
    fun invalidate(relativePath: String) {
        mappings.remove(relativePath)
        oldContentCache.remove(relativePath)
    }

    fun toRelativePath(absolutePath: String): String? {
        val basePath = project.basePath ?: return null
        val baseVf = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null
        val fileVf = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
        return VfsUtil.getRelativePath(fileVf, baseVf)
    }

    private fun getOrComputeMapping(relativePath: String, currentContent: String): Map<Int, Int>? {
        // Return cached mapping if available
        if (mappings.containsKey(relativePath)) {
            return mappings[relativePath]
        }

        val dataService = CoverageDataService.getInstance(project)
        val commitHash = dataService.coverageCommitHash ?: return null
        val gitRoot = dataService.gitRoot ?: return null

        val mapping = CoverageLineMapper.computeMapping(gitRoot, commitHash, relativePath, currentContent)
        if (mapping != null) {
            mappings[relativePath] = mapping
            // Cache old content for incremental updates
            val oldContent = CoverageApiClient.runGitCommand(gitRoot, "show", "$commitHash:$relativePath")
            if (oldContent != null) {
                oldContentCache[relativePath] = oldContent.lines()
            }
            log.warn("Coverage: computed line mapping for $relativePath: ${mapping.size} unchanged lines")
        }
        return mapping
    }

    private fun findRawCoverage(
        absolutePath: String,
        relativePath: String,
        dataService: CoverageDataService
    ): Map<Int, List<String>>? {
        return dataService.getCoverage(absolutePath)
            ?: dataService.getCoverage(relativePath)
            ?: dataService.getCoverage("/$relativePath")
            ?: dataService.allFiles().firstNotNullOfOrNull { storedPath ->
                if (storedPath.endsWith(relativePath) || relativePath.endsWith(storedPath.trimStart('/'))) {
                    dataService.getCoverage(storedPath)
                } else null
            }
    }

    companion object {
        fun getInstance(project: Project): LineMappingService = project.service()
    }
}
