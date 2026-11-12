package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract tests for [CovdClient] against a **fake** daemon, without the real `covd` binary.
 *
 * The client starts the daemon itself and reads one handshake line from its stdout, so the fake
 * is two pieces: an [HttpServer] with mutable responses and a POSIX shell script that prints a
 * handshake line and then sleeps. The script reads the port from a file, which lets a test move
 * the daemon to a new port between two requests.
 *
 * These tests always run: they need no covd binary and no GitLab.
 */
class CovdFakeDaemonContractTest {

    private lateinit var workDir: File
    private lateinit var portFile: File
    private val daemons = CopyOnWriteArrayList<FakeDaemon>()
    private val scripts = AtomicInteger(0)

    @Before
    fun setUp() {
        assumeFalse(
            "the fake daemon wrapper needs a POSIX shell",
            System.getProperty("os.name").orEmpty().lowercase().contains("win"),
        )
        CovdClient.resetForTest()
        workDir = Files.createTempDirectory("covd-fake-daemon-").toFile()
        portFile = File(workDir, "port")
    }

    @After
    fun tearDown() {
        CovdClient.resetForTest()
        daemons.forEach { runCatching { it.stop() } }
        daemons.clear()
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    // --- handshake ----------------------------------------------------------------

    @Test
    fun `handshake rejects a garbage line`() {
        useFreeformScript("printf 'not a handshake\\n'\nexec sleep 3600")

        val error = assertThrows(CovdClient.Unavailable::class.java) {
            CovdClient.ensure(ensureRequest())
        }

        assertTrue(error.message.orEmpty().contains("invalid covd handshake"))
    }

    @Test
    fun `handshake rejects an empty line`() {
        useFreeformScript("printf '\\n'\nexec sleep 3600")

        val error = assertThrows(CovdClient.Unavailable::class.java) {
            CovdClient.ensure(ensureRequest())
        }

        assertTrue(error.message.orEmpty().contains("invalid covd handshake"))
    }

    @Test
    fun `handshake times out when the daemon prints nothing`() {
        CovdClient.handshakeTimeoutMs = 300
        useFreeformScript("exec sleep 3600")

        val started = System.nanoTime()
        val error = assertThrows(CovdClient.Unavailable::class.java) {
            CovdClient.ensure(ensureRequest())
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(error.message.orEmpty().contains("did not print a handshake line"))
        assertTrue("handshake wait took ${elapsedMs} ms", elapsedMs < 5_000)
    }

    @Test
    fun `handshake rejects a non-positive port`() {
        useHandshakeLine("""{"covdReady":{"port":0,"token":"t","instanceId":"i"}}""")

        val error = assertThrows(CovdClient.Unavailable::class.java) {
            CovdClient.ensure(ensureRequest())
        }

        assertTrue(error.message.orEmpty().contains("invalid covd handshake"))
    }

    @Test
    fun `handshake rejects an empty token`() {
        useHandshakeLine("""{"covdReady":{"port":1234,"token":"","instanceId":"i"}}""")

        val error = assertThrows(CovdClient.Unavailable::class.java) {
            CovdClient.ensure(ensureRequest())
        }

        assertTrue(error.message.orEmpty().contains("invalid covd handshake"))
    }

    // --- ping: never send the GitLab token to a stranger ---------------------------

    @Test
    fun `a foreign instance id stops the client before it sends any ensure request`() {
        val daemon = startDaemon()
        daemon.pingInstanceId = "somebody-else"

        val error = assertThrows(CovdClient.Unavailable::class.java) {
            CovdClient.ensure(ensureRequest())
        }

        assertTrue(error.message.orEmpty().contains("does not answer"))
        assertTrue("the stranger must not receive the ensure request", daemon.ensureRequests.isEmpty())
    }

    @Test
    fun `an incompatible protocol stops the client before it sends any ensure request`() {
        val daemon = startDaemon()
        daemon.pingProtocol = 2

        val error = assertThrows(CovdClient.Unavailable::class.java) {
            CovdClient.ensure(ensureRequest())
        }

        assertTrue(error.message.orEmpty().contains("does not answer"))
        assertTrue("the stranger must not receive the ensure request", daemon.ensureRequests.isEmpty())
    }

    // --- authentication and request body ------------------------------------------

    @Test
    fun `ensure sends the daemon bearer token in the header and the GitLab token in the body`() {
        val daemon = startDaemon()
        daemon.ensure = FakeDaemon.Response(200, """{"state":"ready","commit":"abc","pipelineId":7}""")
        val gitlabToken = "gitlab-secret-TOKEN"
        val request = ensureRequest(gitlabToken = gitlabToken)

        val response = CovdClient.ensure(request)

        assertEquals("ready", response.state)
        val seen = daemon.ensureRequests.single()
        assertEquals("Bearer ${daemon.token}", seen.header("Authorization"))
        assertTrue("body: ${seen.body}", seen.body.contains(gitlabToken))
        assertFalse("GitLab token leaked into headers", seen.headers.values.any { it.contains(gitlabToken) })
        assertFalse("GitLab token leaked into the URL", seen.path.contains(gitlabToken) || seen.query.contains(gitlabToken))

        val body = Json.parseToJsonElement(seen.body).jsonObject
        listOf("gitRoot", "component", "pathMode", "jobPrefixes", "artifactPath", "gitPath", "settings", "force", "dedupKey")
            .forEach { assertTrue("missing field $it", body.containsKey(it)) }
        assertEquals(request.gitRoot, body.string("gitRoot"))
        assertEquals(request.component, body.string("component"))
        assertEquals(request.pathMode, body.string("pathMode"))
        assertEquals(request.jobPrefixes, body["jobPrefixes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(request.force, body["force"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(request.dedupKey, body.string("dedupKey"))

        val settings = body["settings"]!!.jsonObject
        listOf("baseUrl", "projectId", "coverageBranch", "token", "proxy", "caBundlePath")
            .forEach { assertTrue("missing settings field $it", settings.containsKey(it)) }
        assertEquals(gitlabToken, settings.string("token"))
        assertEquals(request.settings.baseUrl, settings.string("baseUrl"))
        assertEquals(request.settings.projectId, settings.string("projectId"))
        assertEquals(request.settings.coverageBranch, settings.string("coverageBranch"))
    }

    @Test
    fun `import artifacts and delete carry the daemon bearer token`() {
        val daemon = startDaemon()
        daemon.importResponse = FakeDaemon.Response(200, """{"state":"ready","commit":"c1"}""")
        daemon.artifacts = FakeDaemon.Response(200, """[{"commit":"c1","component":"api"}]""")
        daemon.delete = FakeDaemon.Response(200, """{"ok":true}""")

        val imported = CovdClient.import(
            CovdClient.ImportRequest(covtPath = "/tmp/a.covt", projectId = "42", component = "api"),
        )
        val list = CovdClient.artifacts("42")
        val deleted = CovdClient.deleteArtifact("42", "c1", "api")

        assertEquals("ready", imported.state)
        assertEquals(1, list!!.size)
        assertTrue(deleted)

        val expected = "Bearer ${daemon.token}"
        assertEquals(expected, daemon.requests.single { it.method == "POST" && it.path == "/v1/import" }.header("Authorization"))
        assertEquals(expected, daemon.requests.single { it.method == "GET" && it.path == "/v1/artifacts" }.header("Authorization"))
        assertEquals(expected, daemon.requests.single { it.method == "DELETE" && it.path == "/v1/artifacts" }.header("Authorization"))
    }

    // --- response handling ---------------------------------------------------------

    @Test
    fun `an empty body with HTTP 500 is reported as unavailable`() {
        val daemon = startDaemon()
        daemon.ensure = FakeDaemon.Response(500, "")

        val error = assertThrows(CovdClient.Unavailable::class.java) {
            CovdClient.ensure(ensureRequest())
        }

        assertTrue(error.message.orEmpty().contains("empty ensure response (HTTP 500)"))
    }

    @Test
    fun `a non-2xx answer without a state is normalized to an error state`() {
        val daemon = startDaemon()
        daemon.ensure = FakeDaemon.Response(401, """{"code":"UNAUTHORIZED","message":"invalid or missing bearer token"}""")

        val unauthorized = CovdClient.ensure(ensureRequest())
        assertEquals("error", unauthorized.state)
        assertEquals("UNAUTHORIZED", unauthorized.code)

        daemon.ensure = FakeDaemon.Response(500, "{}")
        val bare = CovdClient.ensure(ensureRequest())
        assertEquals("error", bare.state)
        assertEquals("HTTP_500", bare.code)
    }

    @Test
    fun `a body that is not an ensure answer is reported as unavailable`() {
        val daemon = startDaemon()
        daemon.ensure = FakeDaemon.Response(502, "<html><body>Bad Gateway</body></html>")
        val html = assertThrows(CovdClient.Unavailable::class.java) { CovdClient.ensure(ensureRequest()) }
        assertTrue(html.message.orEmpty(), html.message.orEmpty().contains("not an ensure response"))

        daemon.ensure = FakeDaemon.Response(200, "{}")
        val stateless = assertThrows(CovdClient.Unavailable::class.java) { CovdClient.ensure(ensureRequest()) }
        assertTrue(stateless.message.orEmpty(), stateless.message.orEmpty().contains("without a state"))
    }

    @Test
    fun `import applies the same status handling as ensure`() {
        val daemon = startDaemon()
        val request = CovdClient.ImportRequest(covtPath = "/tmp/a.covt", projectId = "42", component = "api")

        daemon.importResponse = FakeDaemon.Response(401, """{"code":"UNAUTHORIZED"}""")
        val unauthorized = CovdClient.import(request)
        assertEquals("error", unauthorized.state)
        assertEquals("UNAUTHORIZED", unauthorized.code)

        daemon.importResponse = FakeDaemon.Response(502, "<html>gateway</html>")
        val html = assertThrows(CovdClient.Unavailable::class.java) { CovdClient.import(request) }
        assertTrue(html.message.orEmpty(), html.message.orEmpty().contains("not an import response"))
    }

    @Test
    fun `the trace of an ensure answer is decoded`() {
        val daemon = startDaemon()
        daemon.ensure = FakeDaemon.Response(
            200,
            """{"state":"ready","commit":"abc","trace":{"requestId":"r-1","joinedFlight":true,"leaderRequestId":"r-0",
                "outcome":{"state":"ready","httpStatus":200},
                "timing":{"resolveMs":120,"downloadMs":300,"totalMs":450,"resolveAttempts":2},
                "decisions":[{"scope":"primary","pipelineId":7,"sha":"abc","source":"push","code":"chosen"},
                             {"scope":"baseline","pipelineId":5,"sha":"def","code":"not_ready","reason":"jobs_running"}]}}""",
        )

        val trace = requireNotNull(CovdClient.ensure(ensureRequest()).trace)

        assertEquals("r-1", trace.requestId)
        assertTrue(trace.joinedFlight)
        assertEquals("r-0", trace.leaderRequestId)
        assertEquals(200, trace.outcome?.httpStatus)
        assertEquals(450L, trace.timing?.totalMs)
        assertEquals(2, trace.timing?.resolveAttempts)
        assertEquals(listOf("chosen", "not_ready"), trace.decisions.map { it.code })
        assertEquals("jobs_running", trace.decisions[1].reason)
    }

    @Test
    fun `unknown response fields are ignored for forward compatibility`() {
        val daemon = startDaemon()
        daemon.ensure = FakeDaemon.Response(
            200,
            """{"state":"ready","commit":"abc","futureField":{"nested":true},"trace":{"requestId":"r1"}}""",
        )

        val response = CovdClient.ensure(ensureRequest())

        assertEquals("ready", response.state)
        assertEquals("abc", response.commit)
    }

    @Test
    fun `ensure maps ready, not_ready and error states`() {
        val daemon = startDaemon()

        daemon.ensure = FakeDaemon.Response(
            200,
            """{"state":"ready","commit":"abc","cov4Path":"/tmp/x.cov4","cached":true,"replaced":false}""",
        )
        CovdClient.ensure(ensureRequest()).let { response ->
            assertEquals("ready", response.state)
            assertEquals("abc", response.commit)
            assertEquals("/tmp/x.cov4", response.cov4Path)
            assertTrue(response.cached)
            assertFalse(response.replaced)
        }

        daemon.ensure = FakeDaemon.Response(
            200,
            """{"state":"not_ready","reason":"running","retryAfterMs":1500,"stale":true}""",
        )
        CovdClient.ensure(ensureRequest()).let { response ->
            assertEquals("not_ready", response.state)
            assertEquals("running", response.reason)
            assertEquals(1500L, response.retryAfterMs)
            assertTrue(response.stale)
        }

        daemon.ensure = FakeDaemon.Response(
            200,
            """{"state":"error","code":"NO_DATA","cause":"upstream","message":"nothing","retryable":false}""",
        )
        CovdClient.ensure(ensureRequest()).let { response ->
            assertEquals("error", response.state)
            assertEquals("NO_DATA", response.code)
            assertEquals("upstream", response.cause)
            assertEquals("nothing", response.message)
            assertFalse(response.retryable)
        }
    }

    @Test
    fun `a slow ensure times out instead of hanging and the process does not stay behind`() {
        val daemon = startDaemon()
        daemon.ensureDelayMs = 2_000
        CovdClient.ensureTimeoutMs = 300

        val started = System.nanoTime()
        assertThrows(IOException::class.java) { CovdClient.ensure(ensureRequest()) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("slow ensure took ${elapsedMs} ms", elapsedMs < 10_000)
        val process = CovdClient.currentProcessForTest()
        assertNotNull("the client must have started a process", process)
        CovdClient.resetForTest()
        assertTrue("daemon process is still alive", process!!.waitFor(5, TimeUnit.SECONDS))
    }

    // --- reconnects ----------------------------------------------------------------

    @Test
    fun `a refused connection after a successful handshake restarts the daemon exactly once`() {
        val first = startDaemon()
        assertEquals(emptyList<CovdClient.Artifact>(), CovdClient.artifacts("42"))
        assertEquals(1, CovdClient.startCount.get())

        first.stop()
        val second = startDaemon()
        Files.writeString(portFile.toPath(), second.port.toString())
        second.ensure = FakeDaemon.Response(200, """{"state":"ready","commit":"second"}""")

        val response = CovdClient.ensure(ensureRequest())

        assertEquals("ready", response.state)
        assertEquals("second", response.commit)
        assertEquals(2, CovdClient.startCount.get())
        assertEquals(1, second.ensureRequests.size)
        assertTrue("the dead daemon must not receive ensure", first.ensureRequests.isEmpty())
    }

    @Test
    fun `two consecutive connection failures propagate and restart at most twice`() {
        val daemon = startDaemon()
        daemon.closeEnsureConnection = true
        assertNotNull(CovdClient.artifacts("42"))
        val before = CovdClient.startCount.get()

        assertThrows(IOException::class.java) { CovdClient.ensure(ensureRequest()) }
        val started = CovdClient.startCount.get() - before

        assertTrue("started $started times for one ensure", started in 1..2)
        // The JDK HTTP client may retry a POST once when the server drops the connection, so
        // the exact request count belongs to HttpURLConnection, not to CovdClient.
        assertTrue("no ensure was attempted", daemon.ensureRequests.size >= 2)
    }

    @Test
    fun `concurrent ensures share one daemon process`() {
        val daemon = startDaemon()
        daemon.ensure = FakeDaemon.Response(200, """{"state":"ready","commit":"c"}""")
        assertNotNull(CovdClient.artifacts("42"))

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit(Callable { CovdClient.ensure(ensureRequest()) })
            }
            futures.forEach { assertEquals("ready", it.get(10, TimeUnit.SECONDS).state) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, CovdClient.startCount.get())
        assertEquals(threads, daemon.ensureRequests.size)
    }

    // --- artifacts and delete ------------------------------------------------------

    @Test
    fun `artifacts returns null when the daemon is unreachable and a list on HTTP 200`() {
        useFreeformScript("printf 'not a handshake\\n'\nexec sleep 3600")
        assertNull(CovdClient.artifacts("42"))

        val daemon = startDaemon()
        daemon.artifacts = FakeDaemon.Response(200, """[{"commit":"c1","component":"api"}]""")

        assertEquals(1, CovdClient.artifacts("42")!!.size)
    }

    @Test
    fun `delete returns true only for HTTP 200`() {
        val daemon = startDaemon()

        daemon.delete = FakeDaemon.Response(200, """{"ok":true}""")
        assertTrue(CovdClient.deleteArtifact("42", "c1", "api"))

        daemon.delete = FakeDaemon.Response(404, """{"code":"NOT_FOUND"}""")
        assertFalse(CovdClient.deleteArtifact("42", "c1", "api"))

        daemon.delete = FakeDaemon.Response(500, "")
        assertFalse(CovdClient.deleteArtifact("42", "c1", "api"))
    }

    @Test
    fun `special characters in project id commit and component survive query encoding`() {
        val daemon = startDaemon()
        daemon.artifacts = FakeDaemon.Response(200, "[]")
        daemon.delete = FakeDaemon.Response(200, """{"ok":true}""")
        val projectId = "project/with space&x=1"
        val commit = "abc+def/123"
        val component = "api:behat coverage"

        assertNotNull(CovdClient.artifacts(projectId))
        assertTrue(CovdClient.deleteArtifact(projectId, commit, component))

        val list = daemon.requests.single { it.method == "GET" && it.path == "/v1/artifacts" }
        assertEquals(projectId, list.queryValue("projectId"))

        val delete = daemon.requests.single { it.method == "DELETE" && it.path == "/v1/artifacts" }
        assertEquals(projectId, delete.queryValue("projectId"))
        assertEquals(commit, delete.queryValue("commit"))
        assertEquals(component, delete.queryValue("component"))
    }

    // --- helpers -------------------------------------------------------------------

    private fun ensureRequest(
        gitlabToken: String = "gitlab-secret-token",
        projectId: String = "42",
        component: String = "api",
    ) = CovdClient.EnsureRequest(
        gitRoot = "/tmp/repo",
        component = component,
        pathMode = "raw",
        jobPrefixes = listOf("test:behat:coverage:api"),
        settings = CovdClient.Settings(
            baseUrl = "https://gitlab.example",
            projectId = projectId,
            coverageBranch = "master",
            token = gitlabToken,
        ),
    )

    /** Starts a fake daemon and points the client at a wrapper that reads its port from [portFile]. */
    private fun startDaemon(configure: FakeDaemon.() -> Unit = {}): FakeDaemon {
        val daemon = FakeDaemon().apply(configure)
        daemon.start()
        daemons += daemon
        Files.writeString(portFile.toPath(), daemon.port.toString())
        val script = newScript(
            """
            #!/bin/sh
            PORT="${'$'}(cat "${portFile.absolutePath}")"
            printf '{"covdReady":{"port":%s,"token":"${daemon.token}","instanceId":"${daemon.instanceId}"}}\n' "${'$'}PORT"
            exec sleep 3600
            """.trimIndent() + "\n",
        )
        CovdClient.binaryOverride = script
        return daemon
    }

    /** Points the client at a wrapper that prints [line] once and then sleeps. */
    private fun useHandshakeLine(line: String) {
        useFreeformScript("printf '%s\\n' '${line.replace("'", "'\\''")}'\nexec sleep 3600")
    }

    /** Points the client at a raw `#!/bin/sh` script with [body]. */
    private fun useFreeformScript(body: String) {
        CovdClient.binaryOverride = newScript("#!/bin/sh\n$body\n")
    }

    private fun newScript(content: String): File {
        val file = File(workDir, "covd-${scripts.incrementAndGet()}.sh")
        file.writeText(content)
        file.setExecutable(true)
        return file
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.content ?: error("missing string field $name")

    /**
     * In-process stand-in for the covd API. Responses are mutable fields so a test can shape
     * one endpoint without touching the others; every request is recorded.
     */
    private class FakeDaemon {

        data class Response(val status: Int, val body: String = "")

        data class Seen(
            val method: String,
            val path: String,
            val query: String,
            val headers: Map<String, String>,
            val body: String,
        ) {
            fun header(name: String): String? =
                headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

            fun queryValue(name: String): String? =
                query.split('&').firstOrNull { it.substringBefore('=') == name }
                    ?.substringAfter('=', "")
                    ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
        }

        @Volatile var token = "daemon-token"
        @Volatile var instanceId = "instance-1"
        @Volatile var pingInstanceId: String? = null
        @Volatile var pingProtocol = 1
        @Volatile var pingStatus = 200
        @Volatile var ensure = Response(200, """{"state":"ready"}""")
        @Volatile var importResponse = Response(200, """{"state":"ready"}""")
        @Volatile var artifacts = Response(200, "[]")
        @Volatile var delete = Response(200, """{"ok":true}""")
        @Volatile var ensureDelayMs = 0L

        /** Close the ensure connection without a response, like a daemon that died mid-request. */
        @Volatile var closeEnsureConnection = false

        val requests = CopyOnWriteArrayList<Seen>()
        val ensureRequests: List<Seen>
            get() = requests.filter { it.method == "POST" && it.path == "/v1/ensure" }

        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "fake-covd").apply { isDaemon = true }
        }
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange -> handle(exchange) }
                this.executor = executor
            }

        val port: Int get() = server.address.port

        fun start() = server.start()

        fun stop() {
            server.stop(0)
            executor.shutdownNow()
        }

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val seen = Seen(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery.orEmpty(),
                headers = exchange.requestHeaders.entries.associate { it.key to it.value.joinToString(",") },
                body = body,
            )
            requests += seen
            try {
                when ("${exchange.requestMethod} ${exchange.requestURI.path}") {
                    "GET /v1/ping" -> respond(
                        exchange,
                        if (pingStatus == 200) {
                            Response(
                                200,
                                """{"protocol":$pingProtocol,"build":"fake","instanceId":"${pingInstanceId ?: instanceId}"}""",
                            )
                        } else {
                            Response(pingStatus, "")
                        },
                    )
                    "POST /v1/ensure" -> if (closeEnsureConnection) {
                        exchange.close()
                    } else {
                        if (ensureDelayMs > 0) Thread.sleep(ensureDelayMs)
                        respond(exchange, ensure)
                    }
                    "POST /v1/import" -> respond(exchange, importResponse)
                    "GET /v1/artifacts" -> respond(exchange, artifacts)
                    "DELETE /v1/artifacts" -> respond(exchange, delete)
                    else -> respond(exchange, Response(404, """{"code":"NOT_FOUND"}"""))
                }
            } catch (_: Exception) {
                // The client hung up (for example after its read timeout); nothing to do.
            }
        }

        private fun respond(exchange: HttpExchange, response: Response) {
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(response.status, -1L)
            } else {
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
    }
}
