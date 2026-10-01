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
        "Reads a PHP file with per-line coverage markers. " +
        "Each returned line includes the line number, content, coverage flag, and count of covering tests. " +
        "Use coverage='uncovered' to see only uncovered lines (red in the IDE). " +
        "Use offset and limit for pagination through the filtered result. " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun get_coverage_for_file(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
        @McpDescription("Path to the file relative to the project root (e.g. src/Service/Foo.php)")
        file_path: String,
        @McpDescription("Pagination offset into the filtered result (default: 0)")
        offset: Int = 0,
        @McpDescription("Maximum lines to return (default: 100, max: 1000)")
        limit: Int = 100,
        @McpDescription("Filter by coverage: 'all' (default) — all lines; 'covered' — only covered lines; 'uncovered' — only uncovered lines")
        coverage: String = "all",
    ): CoverageFileResult {
        log.info("MCP tool: get_coverage_for_file file_path=$file_path offset=$offset limit=$limit coverage=$coverage")

        if (offset < 0) throw mcpError("offset must be >= 0")
        if (limit < 1 || limit > 1000) throw mcpError("limit must be between 1 and 1000")
        if (coverage !in listOf("all", "covered", "uncovered")) {
            throw mcpError("coverage must be 'all', 'covered', or 'uncovered'")
        }

        val resolved = resolveProject(project)
        val dataService = CoverageDataService.getInstance(resolved)

        if (!dataService.hasData()) {
            throw mcpError(
                "No coverage data is currently loaded in the IDE. " +
                    "Load coverage first (Fetch from GitLab or load a local .covt file)."
            )
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
            ?: throw mcpError(
                "No coverage found for file: $file_path\n" +
                    "The file may not be covered by any tests, or the path may not match.\n" +
                    "Available files with coverage: ${dataService.allFiles().size}"
            )

        val basePath = resolved.basePath
            ?: throw mcpError("Project has no base path")
        val file = File(basePath, file_path)
        if (!file.exists() || !file.isFile) {
            throw mcpError("File not found: $file_path")
        }
        val allLines = file.readLines()
        val totalLinesInFile = allLines.size

        val allLineInfo = allLines.mapIndexed { index, content ->
            val lineNumber = index + 1
            val tests = coverageLines[lineNumber] ?: emptyList()
            CoveredFileLine(
                lineNumber = lineNumber,
                content = content,
                testCount = tests.size,
                isCovered = tests.isNotEmpty(),
            )
        }

        val coveredLinesInFile = allLineInfo.count { it.isCovered }
        val uncoveredLinesInFile = totalLinesInFile - coveredLinesInFile

        val filtered = when (coverage) {
            "covered" -> allLineInfo.filter { it.isCovered }
            "uncovered" -> allLineInfo.filter { !it.isCovered }
            else -> allLineInfo
        }

        val totalMatchingLines = filtered.size
        val page = filtered.drop(offset).take(limit)

        return CoverageFileResult(
            file = file_path,
            commitHash = dataService.coverageCommitHash?.take(8),
            totalLinesInFile = totalLinesInFile,
            coveredLinesInFile = coveredLinesInFile,
            uncoveredLinesInFile = uncoveredLinesInFile,
            offset = offset,
            limit = limit,
            totalMatchingLines = totalMatchingLines,
            lines = page,
        )
    }

    @McpTool
    @McpDescription(
        "Returns test names that cover a specific line in a file. " +
        "Use offset and limit for pagination through the test list (default: 5 tests per page). " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun get_tests_at_line(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
        @McpDescription("Path to the file relative to the project root (e.g. src/Service/Foo.php)")
        file_path: String,
        @McpDescription("Line number (1-based)")
        line_number: Int,
        @McpDescription("Pagination offset within the test list (default: 0)")
        offset: Int = 0,
        @McpDescription("Maximum test names to return (default: 5, max: 100)")
        limit: Int = 5,
    ): CoverageLineTestsResult {
        log.info("MCP tool: get_tests_at_line file_path=$file_path line_number=$line_number offset=$offset limit=$limit")

        if (line_number < 1) throw mcpError("line_number must be >= 1")
        if (offset < 0) throw mcpError("offset must be >= 0")
        if (limit < 1 || limit > 100) throw mcpError("limit must be between 1 and 100")

        val resolved = resolveProject(project)
        val dataService = CoverageDataService.getInstance(resolved)

        if (!dataService.hasData()) {
            throw mcpError(
                "No coverage data is currently loaded in the IDE. " +
                    "Load coverage first (Fetch from GitLab or load a local .covt file)."
            )
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
            ?: throw mcpError("No coverage found for file: $file_path")

        val tests = coverageLines[line_number] ?: emptyList()
        val totalTests = tests.size
        val page = tests.drop(offset).take(limit)

        return CoverageLineTestsResult(
            file = file_path,
            lineNumber = line_number,
            isCovered = totalTests > 0,
            totalTests = totalTests,
            offset = offset,
            limit = limit,
            tests = page,
        )
    }

    @McpTool
    @McpDescription(
        "Returns the first covering test name for each of the given line numbers in a file. " +
        "Useful for quickly checking which tests exercise which lines without fetching the full test list. " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun get_first_tests_at_lines(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
        @McpDescription("Path to the file relative to the project root (e.g. src/Service/Foo.php)")
        file_path: String,
        @McpDescription("Comma-separated list of 1-based line numbers, e.g. '1,5,12'")
        line_numbers: String,
    ): CoverageMultipleLinesResult {
        log.info("MCP tool: get_first_tests_at_lines file_path=$file_path line_numbers=$line_numbers")

        val resolved = resolveProject(project)
        val dataService = CoverageDataService.getInstance(resolved)

        if (!dataService.hasData()) {
            throw mcpError(
                "No coverage data is currently loaded in the IDE. " +
                    "Load coverage first (Fetch from GitLab or load a local .covt file)."
            )
        }

        val parsedLineNumbers = line_numbers.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 1 }

        if (parsedLineNumbers.isEmpty()) {
            throw mcpError("No valid line numbers provided. Use comma-separated integers, e.g. '1,5,12'")
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
            ?: throw mcpError("No coverage found for file: $file_path")

        val lines = parsedLineNumbers.map { lineNumber ->
            val tests = coverageLines[lineNumber] ?: emptyList()
            CoveredLineFirstTest(
                lineNumber = lineNumber,
                isCovered = tests.isNotEmpty(),
                totalTests = tests.size,
                firstTest = tests.firstOrNull(),
            )
        }

        return CoverageMultipleLinesResult(
            file = file_path,
            lines = lines,
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
            throw mcpError(
                "No coverage data is currently loaded in the IDE. " +
                    "Load coverage first (Fetch from GitLab or load a local .covt file)."
            )
        }

        val allFiles = dataService.allFiles()
        if (allFiles.isEmpty()) {
            throw mcpError("No files with coverage data.")
        }

        if (sort !in listOf("coverage_asc", "name")) {
            throw mcpError("Invalid argument 'sort': must be 'coverage_asc' or 'name'")
        }
        if (offset < 0) {
            throw mcpError("Invalid argument 'offset': must be >= 0")
        }
        if (coverage !in listOf("all", "uncovered", "fully_covered")) {
            throw mcpError("Invalid argument 'coverage': must be 'all', 'uncovered', or 'fully_covered'")
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

    @McpTool
    @McpDescription(
        "Reports the state of local coverage collection: whether Behat runs started from the IDE " +
        "write a .covt file, which directory is watched for those files, and how many local runs " +
        "are currently merged over the CI coverage (runs collected on another commit are reported " +
        "separately as staleRuns — they are excluded from the merge until restored in the IDE). " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun get_local_coverage_status(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
    ): LocalCoverageStatusResult {
        log.info("MCP tool: get_local_coverage_status")
        return localCoverageStatus(resolveProject(project))
    }

    @McpTool
    @McpDescription(
        "Turns local coverage collection on or off — the same switch as the 'Collect Local Coverage' " +
        "toggle in the Covering Line toolbar. When on, every Behat run started from the IDE is " +
        "rewritten to collect coverage and write a .covt file that is merged over the CI coverage. " +
        "The setting is IDE-wide (it applies to every open project) and survives restarts. " +
        "Returns the resulting state. " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun set_local_coverage_collection(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
        @McpDescription("true — collect coverage for IDE Behat runs; false — run Behat unchanged")
        enabled: Boolean,
    ): LocalCoverageStatusResult {
        log.info("MCP tool: set_local_coverage_collection enabled=$enabled")
        val resolved = resolveProject(project)
        CoverageApiSettings.getInstance().collectLocalCoverage = enabled
        return localCoverageStatus(resolved)
    }

    @McpTool
    @McpDescription(
        "Restores the local runs that were collected on another commit — the same button as " +
        "'Restore Stale Local Coverage' in the Covering Line toolbar. After a checkout such runs are " +
        "excluded from the merge, because their file snapshots and the CI coverage they supersede " +
        "describe the previous checkout; restoring re-stamps them onto the current HEAD, which only " +
        "makes sense when the checkout did not change the covered code. Returns the resulting state. " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun restore_stale_local_coverage(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
    ): LocalCoverageStatusResult {
        log.info("MCP tool: restore_stale_local_coverage")
        val resolved = resolveProject(project)
        val localService = LocalCoverageService.getInstance(resolved)
        if (localService.staleRunCount() == 0) {
            throw mcpError(
                "No stale local runs: every local run belongs to the current HEAD" +
                    (if (localService.hasData()) "." else ", and none is loaded at all.")
            )
        }
        localService.restoreStale()
        return localCoverageStatus(resolved)
    }

    @McpTool
    @McpDescription(
        "Drops every locally collected run, stale ones included, so coverage falls back to the CI " +
        "data alone. Runs collected on another commit are already excluded from the merge after a " +
        "checkout; this removes them for good. " +
        "Required parameter 'project' — absolute path to the project root directory."
    )
    suspend fun clear_local_coverage(
        @McpDescription("Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
        project: String? = null,
    ): LocalCoverageStatusResult {
        log.info("MCP tool: clear_local_coverage")
        val resolved = resolveProject(project)
        LocalCoverageService.getInstance(resolved).clear()
        return localCoverageStatus(resolved)
    }

    private fun localCoverageStatus(project: Project): LocalCoverageStatusResult {
        val settings = CoverageApiSettings.getInstance()
        val localService = LocalCoverageService.getInstance(project)
        return LocalCoverageStatusResult(
            collectLocalCoverage = settings.collectLocalCoverage,
            localCoverageDir = settings.localCoverageDir,
            watchedDirectory = localService.watchedDirectory(),
            localRuns = localService.runCount(),
            staleRuns = localService.staleRunCount(),
            localTests = localService.tests().size,
            localFiles = localService.fileCount(),
            pluginEnabled = settings.enabled,
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
        throw mcpError(
            "Cannot determine the target project. $hint Open projects:\n$projectList",
        )
    }

    companion object {
        internal const val PAGE_SIZE = 50
    }

    private fun mcpError(message: String): Nothing =
        throw McpExpectedError(message, null)
}

@Serializable
data class CoverageFileResult(
    val file: String,
    val commitHash: String? = null,
    val totalLinesInFile: Int = 0,
    val coveredLinesInFile: Int = 0,
    val uncoveredLinesInFile: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val totalMatchingLines: Int = 0,
    val lines: List<CoveredFileLine> = emptyList(),
)

@Serializable
data class CoveredFileLine(
    val lineNumber: Int,
    val content: String,
    val testCount: Int,
    val isCovered: Boolean,
)

@Serializable
data class CoverageLineTestsResult(
    val file: String,
    val lineNumber: Int,
    val isCovered: Boolean = false,
    val totalTests: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val tests: List<String> = emptyList(),
)

@Serializable
data class CoverageMultipleLinesResult(
    val file: String,
    val lines: List<CoveredLineFirstTest> = emptyList(),
)

@Serializable
data class CoveredLineFirstTest(
    val lineNumber: Int,
    val isCovered: Boolean? = null,
    val totalTests: Int? = null,
    val firstTest: String? = null,
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

@Serializable
data class LocalCoverageStatusResult(
    /** Whether IDE Behat runs are rewritten to collect coverage (IDE-wide setting). */
    val collectLocalCoverage: Boolean,
    /** Git-root-relative directory watched for `.covt` files. */
    val localCoverageDir: String,
    /** Absolute path of that directory, null when the project is not inside a git repository. */
    val watchedDirectory: String? = null,
    /** Local runs held in memory, stale ones included. */
    val localRuns: Int = 0,
    /** Runs collected on another commit — kept, but not merged until restored in the panel. */
    val staleRuns: Int = 0,
    /** Scenarios of the non-stale runs — their CI coverage is superseded. */
    val localTests: Int = 0,
    /** Files touched by the non-stale runs. */
    val localFiles: Int = 0,
    /** Master switch of the plugin (Settings → Tools → GitLab Coverage). */
    val pluginEnabled: Boolean = false,
)
