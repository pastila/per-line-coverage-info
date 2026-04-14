package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

/**
 * Pure JSON-RPC 2.0 dispatcher for the MCP (Model Context Protocol) server.
 *
 * Handles `initialize`, `notifications/initialized`, `tools/list`, and `tools/call`.
 * No HTTP — takes a JSON string and returns a JSON string.
 *
 * @param project the IntelliJ project. Pass null only in unit tests
 *   (tool calls that need coverage data will return an error).
 */
class McpHandler(private val project: Project?) {

    private val log: CoverageLog? by lazy {
        try { CoverageLog.get(McpHandler::class.java) } catch (_: Throwable) { null }
    }

    companion object {
        private const val PROTOCOL_VERSION = "2025-03-26"
        private const val SERVER_NAME = "per-line-coverage-info"
        private const val SERVER_VERSION = "1.0.0"

        private val TOOL_DESCRIPTOR = buildJsonObject {
            put("name", "get_coverage_for_file")
            put("description", "Returns per-line code coverage for a file in the currently open PhpStorm project. " +
                "Shows which tests cover each line. Coverage must already be loaded in the IDE " +
                "(via GitLab fetch or local .covt file).")
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("file_path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path to the file relative to the project root (e.g. src/Service/Foo.php)")
                    })
                })
                put("required", buildJsonArray { add("file_path") })
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

            // Notifications have no id — no response expected
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
            put("tools", buildJsonArray { add(TOOL_DESCRIPTOR) })
        }
        return jsonRpcResult(id, result)
    }

    private fun handleToolsCall(id: JsonElement, params: JsonObject?): String {
        val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull
            ?: return jsonRpcError(id, -32602, "Invalid params: missing tool name")

        if (toolName != "get_coverage_for_file") {
            return toolErrorResult(id, "Unknown tool: $toolName")
        }

        val arguments = params["arguments"]?.jsonObject
        val filePath = arguments?.get("file_path")?.jsonPrimitive?.contentOrNull
            ?: return toolErrorResult(id, "Missing required argument: file_path")

        return try {
            val text = getCoverageForFile(filePath)
            toolSuccessResult(id, text)
        } catch (e: Exception) {
            log?.warn("MCP tool error for file $filePath", e)
            toolErrorResult(id, "Error: ${e.message}")
        }
    }

    private fun handlePing(id: JsonElement): String {
        return jsonRpcResult(id, buildJsonObject {})
    }

    /**
     * Core tool logic: look up coverage for [filePath] and format it as human-readable text.
     */
    internal fun getCoverageForFile(filePath: String): String {
        if (project == null) {
            return "No project context available."
        }
        val dataService = CoverageDataService.getInstance(project)

        if (!dataService.hasData()) {
            return "No coverage data is currently loaded in the IDE. " +
                "Load coverage first (Fetch from GitLab or load a local .covt file)."
        }

        val candidates = mutableListOf(filePath)
        // Also try project-relative path resolved to absolute
        project?.basePath?.let { basePath ->
            candidates.add("$basePath/$filePath")
        }

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
        sb.appendLine()

        for ((lineNumber, tests) in coverageLines.entries.sortedBy { it.key }) {
            if (tests.isNotEmpty()) {
                sb.appendLine("Line $lineNumber: covered — tests: ${tests.joinToString(", ") { "\"$it\"" }}")
            } else {
                sb.appendLine("Line $lineNumber: not covered")
            }
        }

        return sb.toString().trimEnd()
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
