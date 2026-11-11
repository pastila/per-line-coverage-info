package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Startup and timeout contract of [CovdClient] against a **fake** daemon (review items M3/M4/L1).
 *
 * The fake is a POSIX shell script (like `CovdFakeDaemonContractTest`): it prints a handshake
 * line, or a start error on stderr, or nothing at all. No `covd` binary and no GitLab are needed.
 */
class CovdDaemonStartupTest {

    private lateinit var workDir: File

    private val daemons = mutableListOf<FakeDaemon>()
    private val scripts = AtomicInteger(0)

    @Before
    fun setUp() {
        assumeFalse(
            "the fake daemon wrapper needs a POSIX shell",
            System.getProperty("os.name").orEmpty().lowercase().contains("win"),
        )
        CovdClient.resetForTest()
        workDir = Files.createTempDirectory("covd-startup-").toFile()
    }

    @After
    fun tearDown() {
        CovdClient.resetForTest()
        daemons.forEach { runCatching { it.stop() } }
        daemons.clear()
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    // --- M4: stderr with the daemon's own start error ---------------------------------

    @Test
    fun `a start error on stderr is reported with its code and message`() {
        CovdClient.handshakeTimeoutMs = 1_000
        useFreeformScript(
            """
            printf '%s\n' '{"covdStartError":{"code":"LOCK","message":"cache dir is locked by another process"}}' >&2
            exec sleep 3600
            """.trimIndent(),
        )

        val error = assertThrows(CovdClient.Unavailable::class.java) { CovdClient.ensure(ensureRequest()) }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("LOCK"))
        assertTrue(message, message.contains("cache dir is locked by another process"))
    }

    @Test
    fun `a plain stderr line is included when the daemon prints no handshake`() {
        CovdClient.handshakeTimeoutMs = 1_000
        useFreeformScript(
            """
            printf '%s\n' 'fatal: cannot open the cache directory' >&2
            exec sleep 3600
            """.trimIndent(),
        )

        val error = assertThrows(CovdClient.Unavailable::class.java) { CovdClient.ensure(ensureRequest()) }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("did not print a handshake line"))
        assertTrue(message, message.contains("fatal: cannot open the cache directory"))
    }

    // --- L1: the token of a malformed handshake is a secret ---------------------------

    @Test
    fun `a garbage handshake masks the daemon token`() {
        val token = listOf("daemon", "secret", "token").joinToString("-")
        useHandshakeLine("{\"covdReady\":{\"port\":0,\"token\":\"" + token + "\",\"instanceId\":\"inst\"}}")

        val error = assertThrows(CovdClient.Unavailable::class.java) { CovdClient.ensure(ensureRequest()) }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("invalid covd handshake"))
        assertFalse("the daemon token leaked: $message", message.contains(token))
        assertTrue("the token is masked in place: $message", message.contains("\"token\":\"***\""))
    }

    // --- M4: a failed handshake leaves no process behind ------------------------------

    @Test
    fun `a daemon that never prints a handshake is destroyed`() {
        CovdClient.handshakeTimeoutMs = 300
        val pidFile = File(workDir, "daemon.pid")
        useFreeformScript(
            """
            echo ${'$'}${'$'} > "${pidFile.absolutePath}"
            exec sleep 3600
            """.trimIndent(),
        )

        assertThrows(CovdClient.Unavailable::class.java) { CovdClient.ensure(ensureRequest()) }

        val pid = pidFile.readText().trim().toLong()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            if (!alive) return
            Thread.sleep(50)
        }
        assertFalse("the failed daemon process $pid is still alive", ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `stdout is drained after the handshake so the daemon never blocks on its pipe`() {
        val daemon = startDaemon()
        val drained = File(workDir, "drained.marker")
        // 2 MB of noise after the handshake: more than a pipe buffer holds. The marker can only
        // appear when somebody reads the output continuously.
        val script = newScript(
            """
            #!/bin/sh
            printf '{"covdReady":{"port":${daemon.port},"token":"tok","instanceId":"${daemon.instanceId}"}}\n'
            i=0
            while [ ${'$'}i -lt 200000 ]; do
              printf 'noise noise noise noise noise noise noise noise\n'
              i=${'$'}((i + 1))
            done
            : > "${drained.absolutePath}"
            exec sleep 3600
            """.trimIndent() + "\n",
        )
        CovdClient.binaryOverride = script

        assertNotNull(CovdClient.artifacts("42"))

        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !drained.isFile) {
            Thread.sleep(50)
        }
        assertTrue("the fake daemon blocked on stdout: the client does not drain it", drained.isFile)
    }

    // --- M3: a read timeout is not a dead connection ---------------------------------

    @Test
    fun `a slow ensure times out once without restarting the daemon`() {
        val daemon = startDaemon()
        daemon.ensureDelayMs = 2_000
        CovdClient.ensureTimeoutMs = 300

        val started = System.nanoTime()
        assertThrows(IOException::class.java) { CovdClient.ensure(ensureRequest()) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("slow ensure took ${elapsedMs} ms", elapsedMs < 10_000)
        assertEquals("the daemon must not be restarted after a read timeout", 1, CovdClient.startCount.get())
        assertEquals("the request must not be repeated", 1, daemon.ensureRequests.size)
        assertNotNull("the busy daemon is still reachable", CovdClient.artifacts("42"))
    }

    // --- helpers ---------------------------------------------------------------------

    private fun ensureRequest() = CovdClient.EnsureRequest(
        gitRoot = "/tmp/repo",
        component = "api",
        pathMode = "raw",
        jobPrefixes = listOf("test:behat:coverage:api"),
        settings = CovdClient.Settings(
            baseUrl = "https://gitlab.example",
            projectId = "42",
            coverageBranch = "master",
            token = "gitlab-secret-token",
        ),
    )

    /** Starts a fake HTTP daemon and points the client at a wrapper that prints its handshake. */
    private fun startDaemon(): FakeDaemon {
        val daemon = FakeDaemon().also { it.start() }
        daemons += daemon
        CovdClient.binaryOverride = newScript(
            """
            #!/bin/sh
            printf '{"covdReady":{"port":${daemon.port},"token":"tok","instanceId":"${daemon.instanceId}"}}\n'
            exec sleep 3600
            """.trimIndent() + "\n",
        )
        return daemon
    }

    private fun useHandshakeLine(line: String) {
        useFreeformScript("printf '%s\\n' '${line.replace("'", "'\\''")}'\nexec sleep 3600")
    }

    private fun useFreeformScript(body: String) {
        CovdClient.binaryOverride = newScript("#!/bin/sh\n$body\n")
    }

    private fun newScript(content: String): File {
        val file = File(workDir, "covd-${scripts.incrementAndGet()}.sh")
        file.writeText(content)
        file.setExecutable(true)
        return file
    }

    /** In-process stand-in for the covd HTTP API: ping, ensure and the artifact list. */
    private class FakeDaemon {

        @Volatile var instanceId = "startup-instance"
        @Volatile var ensureDelayMs = 0L
        @Volatile var ensureBody = """{"state":"ready","commit":"abc"}"""

        val ensureRequests = java.util.concurrent.CopyOnWriteArrayList<Int>()

        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "fake-covd-startup").apply { isDaemon = true }
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
            try {
                when ("${exchange.requestMethod} ${exchange.requestURI.path}") {
                    "GET /v1/ping" -> respond(
                        exchange,
                        """{"protocol":1,"build":"fake","instanceId":"$instanceId"}""",
                    )
                    "POST /v1/ensure" -> {
                        ensureRequests += 1
                        if (ensureDelayMs > 0) Thread.sleep(ensureDelayMs)
                        respond(exchange, ensureBody)
                    }
                    "GET /v1/artifacts" -> respond(exchange, "[]")
                    else -> respond(exchange, """{"code":"NOT_FOUND"}""", 404)
                }
            } catch (_: Exception) {
                // The client hung up (read timeout); nothing to do.
            } finally {
                exchange.close()
            }
        }

        private fun respond(exchange: HttpExchange, body: String, status: Int = 200) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
