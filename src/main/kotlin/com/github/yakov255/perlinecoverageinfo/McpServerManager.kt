package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Application-level service that runs a single lightweight HTTP server for MCP (Model Context Protocol).
 *
 * Manages a registry of open projects keyed by their canonical base path.
 * When a tool call arrives, the [McpHandler] looks up the project by the
 * required `project` argument (absolute path). Paths are normalised via
 * [File.canonicalPath] so that trailing slashes, symlinks, etc. match correctly.
 *
 * Binds to `localhost:<mcpPort>` and exposes a single `/mcp` endpoint that accepts
 * JSON-RPC 2.0 requests via POST (Streamable HTTP transport).
 *
 * Lifecycle: starts on first project open (if enabled), stops on last project close.
 * All mutating methods are `@Synchronized` to prevent races when multiple projects
 * open or close concurrently.
 */
@Service(Service.Level.APP)
class McpServerManager : Disposable {

    private val log = CoverageLog.get(McpServerManager::class.java)

    private val projects = ConcurrentHashMap<String, Project>()
    private var server: HttpServer? = null
    private var handler: McpHandler? = null
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "McpServerManager").apply { isDaemon = true }
    }

    /** Current port the server is bound to, or null if not running. */
    @Volatile
    var boundPort: Int? = null
        private set

    init {
        log.debug("McpServerManager init: subscribing to ProjectManager.TOPIC")
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                @Suppress("DEPRECATION")
                override fun projectOpened(project: Project) {
                    log.info("PM.TOPIC projectOpened: '${project.name}' at '${project.basePath}'")
                    registerProject(project)
                }

                @Suppress("DEPRECATION")
                override fun projectClosed(project: Project) {
                    log.info("PM.TOPIC projectClosed: '${project.name}' at '${project.basePath}'")
                    unregisterProject(project)
                }
            })
        // Also register already-open projects (in case McpServerManager is initialized after project open)
        val openProjects = ProjectManager.getInstance().openProjects
        if (openProjects.isNotEmpty()) {
            log.debug("McpServerManager init: found ${openProjects.size} already-open project(s)")
            for (p in openProjects) {
                registerProject(p)
            }
        }
    }

    /**
     * Registers a project in the lookup map.
     * Starts the HTTP server if this is the first project and MCP is enabled.
     */
    @Synchronized
    fun registerProject(project: Project) {
        val rawPath = project.basePath
        if (rawPath == null) {
            log.info("registerProject: skipped, basePath is null for project '${project.name}'")
            return
        }
        val basePath = File(rawPath).canonicalPath
        val prev = projects.put(basePath, project)
        if (prev != null) {
            log.debug("registerProject: replaced existing entry for '$basePath'")
        }
        log.info("registerProject: registered '${project.name}' at '$basePath' (total: ${projects.size})")
        val settings = CoverageMcpAppSettings.getInstance()
        if (settings.mcpEnabled && server == null) {
            log.info("registerProject: mcpEnabled is true, starting server on port ${settings.mcpPort}")
            start(settings.mcpPort)
        } else {
            log.info("registerProject: mcpEnabled=${settings.mcpEnabled}, server=${server != null} — not starting")
        }
    }

    /**
     * Unregisters a project. Stops the server when no projects remain.
     */
    @Synchronized
    fun unregisterProject(project: Project) {
        val rawPath = project.basePath
        if (rawPath == null) {
            log.info("unregisterProject: skipped, basePath is null for project '${project.name}'")
            return
        }
        val basePath = File(rawPath).canonicalPath
        projects.remove(basePath)
        log.info("unregisterProject: removed '${project.name}' at '$basePath' (remaining: ${projects.size})")
        if (projects.isEmpty()) {
            log.info("unregisterProject: no projects left, stopping server")
            stop()
        }
    }

    /**
     * Looks up a project by its base path.
     * The path is canonicalised before lookup so that trailing slashes
     * and symlinks do not prevent matching.
     */
    fun projectByBasePath(basePath: String): Project? {
        val canonical = try { File(basePath).canonicalPath } catch (_: Exception) { basePath }
        return projects[canonical]
    }

    /**
     * Starts the HTTP server on the given port. No-op if already running on that port.
     */
    @Synchronized
    fun start(port: Int) {
        if (server != null && boundPort == port) {
            log.debug("start: already running on port $port, no-op")
            return
        }
        log.debug("start: stopping previous server (if any) before binding to port $port")
        stop()

        try {
            handler = McpHandler { basePath -> projectByBasePath(basePath) }
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            httpServer.executor = executor
            httpServer.createContext("/mcp") { exchange -> handleMcpRequest(exchange) }
            httpServer.start()
            server = httpServer
            boundPort = port
            log.info("MCP server started on http://127.0.0.1:$port/mcp (${projects.size} project(s) registered)")
            if (projects.isNotEmpty()) {
                log.info("start: registered projects: ${projects.keys.joinToString(", ")}")
            }
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
    @Synchronized
    fun stop() {
        server?.let { s ->
            try {
                s.stop(1)
                log.info("MCP server stopped (was on port $boundPort)")
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
        log.info("restart: port=$port, enabled=$enabled, projects=${projects.size}")
        if (projects.isNotEmpty()) {
            log.info("restart: registered projects: ${projects.keys.joinToString(", ")}")
        }
        log.info("restart: caller stack trace:", RuntimeException("restart caller stack trace"))
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
        log.debug("dispose: shutting down MCP server")
        stop()
        executor.shutdownNow()
    }

    companion object {
        fun getInstance(): McpServerManager = service()
    }
}
