package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

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

    /** Per-file cached old content from the coverage commit. */
    private val oldContentCache = mutableMapOf<String, String>()

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

    fun toRelativePath(absolutePath: String): String? {
        val basePath = project.basePath ?: return null
        val baseVf = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null
        val fileVf = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
        return VfsUtil.getRelativePath(fileVf, baseVf)
    }

    /**
     * Resolves the git-relative path for a file.
     * Coverage data uses paths relative to the project root, but git needs paths relative to the git root.
     */
    private fun toGitRelativePath(relativePath: String): String? {
        val dataService = CoverageDataService.getInstance(project)
        val gitRoot = dataService.gitRoot ?: return null
        val basePath = project.basePath ?: return null
        val projectDir = java.io.File(basePath)
        val absoluteFile = java.io.File(projectDir, relativePath)
        return absoluteFile.relativeTo(gitRoot).path
    }

    private fun getOrFetchOldContent(relativePath: String): String? {
        oldContentCache[relativePath]?.let { return it }

        val dataService = CoverageDataService.getInstance(project)
        val commitHash = dataService.coverageCommitHash ?: return null
        val gitRoot = dataService.gitRoot ?: return null

        val gitPath = toGitRelativePath(relativePath) ?: return null
        val oldContent = CoverageResolver.runGitCommand(gitRoot, "show", "$commitHash:$gitPath")
        if (oldContent != null) {
            oldContentCache[relativePath] = oldContent
        }
        return oldContent
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
