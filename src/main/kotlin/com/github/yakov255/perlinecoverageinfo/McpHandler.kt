package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

/**
 * Pure JSON-RPC 2.0 dispatcher for the MCP (Model Context Protocol) server.
 *
 * Handles `initialize`, `notifications/initialized`, `tools/list`, and `tools/call`.
 * No HTTP — takes a JSON string and returns a JSON string.
 *
 * @param projectLookup function to resolve a project by its absolute base path.
 *   Pass `{ null }` in unit tests that don't need coverage data.
 */
class McpHandler(private val projectLookup: (String) -> Project?) {

    private val log: CoverageLog? by lazy {
        try { CoverageLog.get(McpHandler::class.java) } catch (_: Throwable) { null }
    }

    companion object {
        private const val PROTOCOL_VERSION = "2025-03-26"
        private const val SERVER_NAME = "per-line-coverage-info"
        private const val SERVER_VERSION = "1.0.0"
        private const val PAGE_SIZE = 50

        private val GET_COVERAGE_DESCRIPTOR = buildJsonObject {
            put("name", "get_coverage_for_file")
            put("description", "Returns per-line code coverage for a file. " +
                "Required parameter 'project' — absolute path to the project root directory.")
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("project", buildJsonObject {
                        put("type", "string")
                        put("description", "Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
                    })
                    put("file_path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path to the file relative to the project root (e.g. src/Service/Foo.php)")
                    })
                    put("detail", buildJsonObject {
                        put("type", "string")
                        put("description", "Output detail level: 'summary' (default) — covered/uncovered line ranges and statistics only; 'detailed' — test names per covered line")
                        put("enum", buildJsonArray { add("summary"); add("detailed") })
                    })
                })
                put("required", buildJsonArray { add("project"); add("file_path") })
            })
        }

        private val LIST_FILES_DESCRIPTOR = buildJsonObject {
            put("name", "list_files")
            put("description", "Lists files with coverage data under a directory. " +
                "Required parameter 'project' — absolute path to the project root directory.")
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("project", buildJsonObject {
                        put("type", "string")
                        put("description", "Required. Absolute path to the project root (e.g. /home/user/projects/my-app)")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Directory path relative to the project root (e.g. 'src/Service/'). Use '' for root.")
                    })
                    put("recursive", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include files in subdirectories (default: false — only direct children)")
                    })
                    put("sort", buildJsonObject {
                        put("type", "string")
                        put("description", "Sort order: 'coverage_asc' (default) — lowest coverage first; 'name' — alphabetical by path")
                        put("enum", buildJsonArray { add("coverage_asc"); add("name") })
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Pagination offset (default: 0). Use with page size of $PAGE_SIZE to iterate through results.")
                    })
                    put("coverage", buildJsonObject {
                        put("type", "string")
                        put("description", "Filter by coverage level: 'all' (default) — all files; 'uncovered' — files with 0% coverage; 'fully_covered' — files with 100% coverage.")
                        put("enum", buildJsonArray { add("all"); add("uncovered"); add("fully_covered") })
                    })
                })
                put("required", buildJsonArray { add("project"); add("path") })
            })
        }
    }

    /**
     * Handles a JSON-RPC 2.0 request and returns the response JSON string.
     * Returns null for notifications (no response expected).
     */
    fun handle(requestJson: String): String? {
        return try {
            val request = Json.parseToJsonElement(requestJson).jsonObject
            val method = request["method"]?.jsonPrimitive?.contentOrNull
            val id = request["id"]

            if (method == null) {
                return jsonRpcError(id, -32600, "Invalid Request: missing method")
            }

            if (id == null || id is JsonNull) {
                handleNotification(method)
                return null
            }

            when (method) {
                "initialize" -> handleInitialize(id, request["params"]?.jsonObject)
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(id, request["params"]?.jsonObject)
                "ping" -> handlePing(id)
                else -> jsonRpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            log?.warn("MCP handler error: ${e.message}")
            jsonRpcError(null, -32700, "Parse error: ${e.message}")
        }
    }

    private fun handleNotification(method: String) {
        when (method) {
            "notifications/initialized" -> log?.info("MCP client initialized")
            else -> log?.debug("MCP notification ignored: $method")
        }
    }

    private fun handleInitialize(id: JsonElement, params: JsonObject?): String {
        val clientName = params?.get("clientInfo")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "unknown"
        log?.info("MCP initialize from client: $clientName")

        val result = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {})
            })
            put("serverInfo", buildJsonObject {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            })
        }
        return jsonRpcResult(id, result)
    }

    private fun handleToolsList(id: JsonElement): String {
        val result = buildJsonObject {
            put("tools", buildJsonArray {
                add(GET_COVERAGE_DESCRIPTOR)
                add(LIST_FILES_DESCRIPTOR)
            })
        }
        return jsonRpcResult(id, result)
    }

    private fun handleToolsCall(id: JsonElement, params: JsonObject?): String {
        val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull
            ?: return jsonRpcError(id, -32602, "Invalid params: missing tool name")

        return when (toolName) {
            "get_coverage_for_file" -> handleGetCoverageForFile(id, params)
            "list_files" -> handleListFiles(id, params)
            else -> toolErrorResult(id, "Unknown tool: $toolName")
        }
    }

    private fun handleGetCoverageForFile(id: JsonElement, params: JsonObject?): String {
        val arguments = params?.get("arguments")?.jsonObject ?: return toolErrorResult(id, "Missing arguments")
        val projectBasePath = arguments["project"]?.jsonPrimitive?.contentOrNull
            ?: return toolErrorResult(id, "Missing required argument: project.\nPlease provide the 'project' parameter with the absolute path to the project root directory.")
        val filePath = arguments["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return toolErrorResult(id, "Missing required argument: file_path")
        val detail = arguments["detail"]?.jsonPrimitive?.contentOrNull ?: "summary"

        if (detail !in listOf("summary", "detailed")) {
            return toolErrorResult(id, "Invalid argument 'detail': must be 'summary' or 'detailed'")
        }

        val project = projectLookup(projectBasePath)
            ?: return toolErrorResult(id, "No project found for path: $projectBasePath.\nProvide the absolute path to the project root directory.")

        return try {
            val text = getCoverageForFile(project, filePath, detail)
            toolSuccessResult(id, text)
        } catch (e: Exception) {
            log?.warn("MCP tool error for file $filePath", e)
            toolErrorResult(id, "Error: ${e.message}")
        }
    }

    private fun handleListFiles(id: JsonElement, params: JsonObject?): String {
        val arguments = params?.get("arguments")?.jsonObject ?: return toolErrorResult(id, "Missing arguments")
        val projectBasePath = arguments["project"]?.jsonPrimitive?.contentOrNull
            ?: return toolErrorResult(id, "Missing required argument: project.\nPlease provide the 'project' parameter with the absolute path to the project root directory.")
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull
            ?: return toolErrorResult(id, "Missing required argument: path")
        val recursive = arguments["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val sort = arguments["sort"]?.jsonPrimitive?.contentOrNull ?: "coverage_asc"
        val offset = arguments["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val coverage = arguments["coverage"]?.jsonPrimitive?.contentOrNull ?: "all"

        if (sort !in listOf("coverage_asc", "name")) {
            return toolErrorResult(id, "Invalid argument 'sort': must be 'coverage_asc' or 'name'")
        }
        if (offset < 0) {
            return toolErrorResult(id, "Invalid argument 'offset': must be >= 0")
        }
        if (coverage !in listOf("all", "uncovered", "fully_covered")) {
            return toolErrorResult(id, "Invalid argument 'coverage': must be 'all', 'uncovered', or 'fully_covered'")
        }

        val project = projectLookup(projectBasePath)
            ?: return toolErrorResult(id, "No project found for path: $projectBasePath.\nProvide the absolute path to the project root directory.")

        return try {
            val text = listCoverageFiles(project, path, recursive, sort, offset, coverage)
            toolSuccessResult(id, text)
        } catch (e: Exception) {
            log?.warn("MCP tool error for list_files path=$path", e)
            toolErrorResult(id, "Error: ${e.message}")
        }
    }

    private fun handlePing(id: JsonElement): String {
        return jsonRpcResult(id, buildJsonObject {})
    }

    internal fun getCoverageForFile(project: Project, filePath: String, detail: String = "summary"): String {
        val dataService = CoverageDataService.getInstance(project)

        if (!dataService.hasData()) {
            return "No coverage data is currently loaded in the IDE. " +
                "Load coverage first (Fetch from GitLab or load a local .covt file)."
        }

        val candidates = mutableListOf<String>()
        project.basePath?.let { basePath ->
            val absoluteFile = java.io.File(basePath, filePath)
            dataService.gitRoot?.let { gitRoot ->
                try {
                    candidates.add(
                        absoluteFile.relativeTo(gitRoot).path
                            .replace(java.io.File.separatorChar, '/')
                    )
                } catch (_: IllegalArgumentException) { }
            }
        }
        candidates.add(filePath)

        val coverageLines = CoveragePathResolver.resolve(dataService, candidates)
            ?: return "No coverage found for file: $filePath\n" +
                "The file may not be covered by any tests, or the path may not match.\n" +
                "Available files with coverage: ${dataService.allFiles().size}"

        val commitHash = dataService.coverageCommitHash ?: "unknown"
        val coveredCount = coverageLines.count { (_, tests) -> tests.isNotEmpty() }
        val uncoveredCount = coverageLines.count { (_, tests) -> tests.isEmpty() }
        val totalLines = coverageLines.size

        val sb = StringBuilder()
        sb.appendLine("Coverage for $filePath (commit ${commitHash.take(8)}, $coveredCount/$totalLines lines covered, $uncoveredCount uncovered)")

        if (detail == "summary") {
            sb.appendLine()
            val coveredLines = coverageLines.entries
                .filter { it.value.isNotEmpty() }
                .sortedBy { it.key }
                .map { it.key }
            val uncoveredLines = coverageLines.entries
                .filter { it.value.isEmpty() }
                .sortedBy { it.key }
                .map { it.key }
            sb.appendLine("Covered: ${collapseToRanges(coveredLines)}")
            sb.append("Uncovered: ${collapseToRanges(uncoveredLines)}")
        } else {
            sb.appendLine()
            for ((lineNumber, tests) in coverageLines.entries.sortedBy { it.key }) {
                if (tests.isNotEmpty()) {
                    sb.appendLine("$lineNumber: ${tests.joinToString(", ")}")
                } else {
                    sb.appendLine("$lineNumber: —")
                }
            }
        }

        return sb.toString().trimEnd()
    }

    internal fun listCoverageFiles(
        project: Project,
        dirPath: String,
        recursive: Boolean = false,
        sort: String = "coverage_asc",
        offset: Int = 0,
        coverage: String = "all",
    ): String {
        val dataService = CoverageDataService.getInstance(project)

        if (!dataService.hasData()) {
            return "No coverage data is currently loaded in the IDE. " +
                "Load coverage first (Fetch from GitLab or load a local .covt file)."
        }

        val allFiles = dataService.allFiles()
        if (allFiles.isEmpty()) {
            return "No files with coverage data."
        }

        val prefix = dirPath.trim('/')
        val filtered = allFiles
            .filter { path ->
                if (prefix.isEmpty()) return@filter true
                if (!path.startsWith(prefix)) return@filter false
                val rest = path.substring(prefix.length)
                rest.isEmpty() || rest.startsWith("/")
            }
            .filter { path ->
                if (prefix.isEmpty()) return@filter true
                val rest = path.substring(prefix.length).removePrefix("/")
                if (rest.isEmpty()) return@filter false
                if (recursive) return@filter true
                !rest.contains("/")
            }
            .mapNotNull { path ->
                val coverage = dataService.getCoverage(path) ?: return@mapNotNull null
                val total = coverage.size
                val covered = coverage.count { (_, tests) -> tests.isNotEmpty() }
                FileCoverageInfo(path, total, covered)
            }
            .filter { file ->
                when (coverage) {
                    "uncovered" -> file.covered == 0
                    "fully_covered" -> file.covered == file.total && file.total > 0
                    else -> true
                }
            }

        val sorted = when (sort) {
            "name" -> filtered.sortedBy { it.path }
            else -> filtered.sortedWith(compareBy({ it.covered.toDouble() / it.total }, { it.path }))
        }

        val totalFiles = sorted.size
        val page = sorted.drop(offset).take(PAGE_SIZE)

        val sb = StringBuilder()

        val displayPath = if (prefix.isEmpty()) "/" else "$prefix/"
        sb.appendLine("Files in $displayPath (${totalFiles} file${if (totalFiles != 1) "s" else ""} with coverage, commit ${(dataService.coverageCommitHash ?: "unknown").take(8)})")
        sb.appendLine()

        if (page.isEmpty()) {
            sb.append("No files found on this page.")
            return sb.toString()
        }

        for (f in page) {
            val pct = if (f.total > 0) f.covered * 100 / f.total else 0
            val relativePath = if (prefix.isEmpty()) f.path else f.path.removePrefix("$prefix/").removePrefix("/")
            val marker = if (pct == 0) "  ←" else ""
            sb.appendLine("  $relativePath  ${f.covered}/${f.total}  ${pct}%${marker}")
        }

        if (totalFiles > offset + PAGE_SIZE) {
            val nextOffset = offset + PAGE_SIZE
            sb.appendLine()
            sb.append("... and ${totalFiles - nextOffset} more files. Use offset=$nextOffset to see the next page.")
        }

        return sb.toString().trimEnd()
    }

    private data class FileCoverageInfo(val path: String, val total: Int, val covered: Int)

    internal fun collapseToRanges(lineNumbers: List<Int>): String {
        if (lineNumbers.isEmpty()) return "none"
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

    // --- JSON-RPC helpers ---

    private fun jsonRpcResult(id: JsonElement?, result: JsonElement): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonNull)
            put("result", result)
        }.toString()
    }

    private fun jsonRpcError(id: JsonElement?, code: Int, message: String): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonNull)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString()
    }

    private fun toolSuccessResult(id: JsonElement, text: String): String {
        val result = buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
        }
        return jsonRpcResult(id, result)
    }

    private fun toolErrorResult(id: JsonElement, text: String): String {
        val result = buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("isError", true)
        }
        return jsonRpcResult(id, result)
    }
}
