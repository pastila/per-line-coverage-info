package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Project-level service that runs a lightweight HTTP server for MCP (Model Context Protocol).
 *
 * Binds to `localhost:<mcpPort>` and exposes a single `/mcp` endpoint that accepts
 * JSON-RPC 2.0 requests via POST (Streamable HTTP transport). Claude CLI, Copilot CLI,
 * and other MCP-compatible tools connect here.
 *
 * Lifecycle: starts on project open (if enabled), stops on project close.
 */
@Service(Service.Level.PROJECT)
class McpServer(private val project: Project) : Disposable {

    private val log = CoverageLog.get(McpServer::class.java)

    private var server: HttpServer? = null
    private var handler: McpHandler? = null
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "McpServer-${project.name}").apply { isDaemon = true }
    }

    /** Current port the server is bound to, or null if not running. */
    var boundPort: Int? = null
        private set

    init {
        val settings = CoverageMcpSettings.getInstance(project)
        if (settings.mcpEnabled) {
            start(settings.mcpPort)
        }
    }

    /**
     * Starts the HTTP server on the given port. No-op if already running on that port.
     */
    fun start(port: Int) {
        if (server != null && boundPort == port) return
        stop()

        try {
            handler = McpHandler(project)
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            httpServer.executor = executor
            httpServer.createContext("/mcp") { exchange -> handleMcpRequest(exchange) }
            httpServer.start()
            server = httpServer
            boundPort = port
            log.info("MCP server started on http://127.0.0.1:$port/mcp (project: ${project.name})")
        } catch (e: java.net.BindException) {
            log.warn("MCP server failed to bind to port $port: ${e.message}")
            server = null
            boundPort = null
        } catch (e: Exception) {
            log.error("MCP server failed to start", e)
            server = null
            boundPort = null
        }
    }

    /**
     * Stops the HTTP server gracefully.
     */
    fun stop() {
        server?.let { s ->
            try {
                s.stop(1)
                log.info("MCP server stopped (was on port $boundPort, project: ${project.name})")
            } catch (e: Exception) {
                log.warn("MCP server stop error: ${e.message}")
            }
        }
        server = null
        boundPort = null
    }

    /**
     * Restarts the server with new settings. Called from the Settings UI.
     */
    fun restart(port: Int, enabled: Boolean) {
        stop()
        if (enabled) {
            start(port)
        }
    }

    /** True when the HTTP server is running and accepting connections. */
    val isRunning: Boolean get() = server != null

    private fun handleMcpRequest(exchange: HttpExchange) {
        try {
            // Only accept POST
            if (exchange.requestMethod != "POST") {
                sendResponse(exchange, 405, """{"error":"Method not allowed. Use POST."}""")
                return
            }

            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                sendResponse(exchange, 400, """{"error":"Empty request body"}""")
                return
            }

            val response = handler?.handle(body)

            if (response != null) {
                // Normal JSON-RPC response
                exchange.responseHeaders.add("Content-Type", "application/json")
                sendResponse(exchange, 200, response)
            } else {
                // Notification — 202 Accepted, no body
                sendResponse(exchange, 202, "")
            }
        } catch (e: Exception) {
            log.error("MCP request handling error", e)
            try {
                sendResponse(exchange, 500, """{"error":"Internal server error"}""")
            } catch (_: Exception) {
                // Response may already be committed
            }
        }
    }

    private fun sendResponse(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (body.isNotEmpty()) {
            exchange.responseHeaders.add("Content-Type", "application/json")
        }
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) {
            exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }

    override fun dispose() {
        stop()
        executor.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): McpServer = project.service()
    }
}
