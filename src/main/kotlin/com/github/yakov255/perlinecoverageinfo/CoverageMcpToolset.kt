package com.github.yakov255.perlinecoverageinfo

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.Serializable
import java.io.File

class CoverageMcpToolset : McpToolset {

    private val log = CoverageLog.get(CoverageMcpToolset::class.java)

    @McpTool
    @McpDescription(
        "Returns per-line code coverage for a PHP file. " +
        "Shows which lines are covered/uncovered and which tests cover each line. " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun get_coverage_for_file(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
        @McpDescription("Path to the file relative to the project root (e.g. src/Service/Foo.php)")
        file_path: String,
        @McpDescription("Output detail level: 'summary' (default) — covered/uncovered line ranges and statistics only; 'detailed' — test names per covered line")
        detail: String = "summary",
    ): CoverageFileResult {
        log.info("MCP tool: get_coverage_for_file file_path=$file_path detail=$detail")
        val resolved = resolveProject(project)
        val dataService = CoverageDataService.getInstance(resolved)

        if (!dataService.hasData()) {
            throw McpExpectedError(
                "No coverage data is currently loaded in the IDE. " +
                    "Load coverage first (Fetch from GitLab or load a local .covt file)."
            )
        }

        if (detail !in listOf("summary", "detailed")) {
            throw McpExpectedError("Invalid argument 'detail': must be 'summary' or 'detailed'")
        }

        val candidates = mutableListOf<String>()
        resolved.basePath?.let { basePath ->
            val absoluteFile = File(basePath, file_path)
            dataService.gitRoot?.let { gitRoot ->
                try {
                    candidates.add(
                        absoluteFile.relativeTo(gitRoot).path
                            .replace(File.separatorChar, '/')
                    )
                } catch (_: IllegalArgumentException) { }
            }
        }
        candidates.add(file_path)

        val coverageLines = CoveragePathResolver.resolve(dataService, candidates)
            ?: throw McpExpectedError(
                "No coverage found for file: $file_path\n" +
                    "The file may not be covered by any tests, or the path may not match.\n" +
                    "Available files with coverage: ${dataService.allFiles().size}"
            )

        val commitHash = dataService.coverageCommitHash ?: "unknown"
        val sortedLines = coverageLines.entries.sortedBy { it.key }
        val coveredCount = sortedLines.count { (_, tests) -> tests.isNotEmpty() }
        val totalLines = sortedLines.size
        val uncoveredCount = totalLines - coveredCount

        val coveredLineNumbers = sortedLines.filter { it.value.isNotEmpty() }.map { it.key }
        val uncoveredLineNumbers = sortedLines.filter { it.value.isEmpty() }.map { it.key }

        val linesMap = if (detail == "detailed") {
            sortedLines.associate { (line, tests) ->
                line to if (tests.isNotEmpty()) tests.joinToString(", ") else ""
            }
        } else null

        return CoverageFileResult(
            file = file_path,
            commitHash = commitHash.take(8),
            coveredLines = coveredCount,
            uncoveredLines = uncoveredCount,
            totalLines = totalLines,
            lines = linesMap,
            coveredRanges = collapseToRanges(coveredLineNumbers),
            uncoveredRanges = collapseToRanges(uncoveredLineNumbers),
        )
    }

    @McpTool
    @McpDescription(
        "Lists files with coverage data under a directory. " +
        "Shows each file's coverage percentage, number of covered and total lines. " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun list_files(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
        @McpDescription("Directory path relative to the project root (e.g. 'src/Service/'). Use empty string for root.")
        path: String,
        @McpDescription("Include files in subdirectories recursively (default: false)")
        recursive: Boolean = false,
        @McpDescription("Sort order: 'coverage_asc' (default) — lowest coverage first; 'name' — alphabetical by path")
        sort: String = "coverage_asc",
        @McpDescription("Pagination offset (default: 0). Page size is 50.")
        offset: Int = 0,
        @McpDescription("Filter by coverage level: 'all' (default) — all files; 'uncovered' — files with 0% coverage; 'fully_covered' — files with 100% coverage")
        coverage: String = "all",
    ): CoverageListResult {
        log.info("MCP tool: list_files path=$path sort=$sort offset=$offset coverage=$coverage")
        val resolved = resolveProject(project)
        val dataService = CoverageDataService.getInstance(resolved)

        if (!dataService.hasData()) {
            throw McpExpectedError(
                "No coverage data is currently loaded in the IDE. " +
                    "Load coverage first (Fetch from GitLab or load a local .covt file)."
            )
        }

        val allFiles = dataService.allFiles()
        if (allFiles.isEmpty()) {
            throw McpExpectedError("No files with coverage data.")
        }

        if (sort !in listOf("coverage_asc", "name")) {
            throw McpExpectedError("Invalid argument 'sort': must be 'coverage_asc' or 'name'")
        }
        if (offset < 0) {
            throw McpExpectedError("Invalid argument 'offset': must be >= 0")
        }
        if (coverage !in listOf("all", "uncovered", "fully_covered")) {
            throw McpExpectedError("Invalid argument 'coverage': must be 'all', 'uncovered', or 'fully_covered'")
        }

        val prefix = path.trim('/')

        val filtered = allFiles
            .filter { filePath ->
                if (prefix.isEmpty()) return@filter true
                if (!filePath.startsWith(prefix)) return@filter false
                val rest = filePath.substring(prefix.length)
                rest.isEmpty() || rest.startsWith("/")
            }
            .filter { filePath ->
                if (prefix.isEmpty()) return@filter true
                val rest = filePath.substring(prefix.length).removePrefix("/")
                if (rest.isEmpty()) return@filter false
                if (recursive) return@filter true
                !rest.contains("/")
            }
            .mapNotNull { filePath ->
                val cov = dataService.getCoverage(filePath) ?: return@mapNotNull null
                val total = cov.size
                val covered = cov.count { (_, tests) -> tests.isNotEmpty() }
                val pct = if (total > 0) covered * 100 / total else 0
                CoverageFileInfo(
                    path = filePath,
                    coveredLines = covered,
                    totalLines = total,
                    coveragePercent = pct,
                )
            }
            .filter { info ->
                when (coverage) {
                    "uncovered" -> info.coveragePercent == 0
                    "fully_covered" -> info.coveragePercent == 100 && info.totalLines > 0
                    else -> true
                }
            }

        val sorted = when (sort) {
            "name" -> filtered.sortedBy { it.path }
            else -> filtered.sortedWith(compareBy({ it.coveragePercent }, { it.path }))
        }

        val totalFiles = sorted.size
        val page = sorted.drop(offset).take(PAGE_SIZE)
        val nextOffset = if (totalFiles > offset + PAGE_SIZE) offset + PAGE_SIZE else null

        return CoverageListResult(
            path = if (prefix.isEmpty()) "/" else "$prefix/",
            commitHash = dataService.coverageCommitHash?.take(8),
            totalFiles = totalFiles,
            files = page,
            nextOffset = nextOffset,
        )
    }

    // TODO: Use McpProjectLocationInputs.resolveProject() from the MCP framework
    // for proper multi-project resolution (session headers, roots capability)
    private fun resolveProject(projectPath: String?): Project {
        val openProjects = ProjectManager.getInstance().openProjects

        if (projectPath != null) {
            val canonicalPath = File(projectPath).canonicalPath
            val matched = openProjects.firstOrNull {
                it.basePath != null && File(it.basePath).canonicalPath == canonicalPath
            }
            if (matched != null) return matched
        }

        if (openProjects.size == 1) return openProjects[0]

        val projectList = openProjects.mapNotNull { it.basePath }.joinToString("\n")
        val hint = if (projectPath != null) {
            "Project path '$projectPath' does not match any open project. "
        } else {
            "No project path provided. "
        }
        throw McpExpectedError(
            "Cannot determine the target project. $hint Open projects:\n$projectList",
        )
    }

    companion object {
        internal const val PAGE_SIZE = 50

        internal fun collapseToRanges(lineNumbers: List<Int>): String {
            if (lineNumbers.isEmpty()) return ""
            val ranges = mutableListOf<String>()
            var rangeStart = lineNumbers[0]
            var rangeEnd = rangeStart
            for (i in 1 until lineNumbers.size) {
                if (lineNumbers[i] == rangeEnd + 1) {
                    rangeEnd = lineNumbers[i]
                } else {
                    ranges.add(if (rangeStart == rangeEnd) "$rangeStart" else "$rangeStart-$rangeEnd")
                    rangeStart = lineNumbers[i]
                    rangeEnd = rangeStart
                }
            }
            ranges.add(if (rangeStart == rangeEnd) "$rangeStart" else "$rangeStart-$rangeEnd")
            return ranges.joinToString(", ")
        }
    }
}

@Serializable
data class CoverageFileResult(
    val file: String,
    val commitHash: String? = null,
    val coveredLines: Int = 0,
    val uncoveredLines: Int = 0,
    val totalLines: Int = 0,
    /** Detailed per-line coverage: map of line number -> comma-separated test names. Null in summary mode. */
    val lines: Map<Int, String>? = null,
    /** Collapsed ranges of covered lines, e.g. "1-10, 12-16" */
    val coveredRanges: String = "",
    /** Collapsed ranges of uncovered lines, e.g. "11, 17-20" */
    val uncoveredRanges: String = "",
)

@Serializable
data class CoverageListResult(
    val path: String,
    val commitHash: String? = null,
    val totalFiles: Int = 0,
    val files: List<CoverageFileInfo> = emptyList(),
    val nextOffset: Int? = null,
)

@Serializable
data class CoverageFileInfo(
    val path: String,
    val coveredLines: Int,
    val totalLines: Int,
    val coveragePercent: Int,
)
