package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Error contract of the real `covd` daemon, exercised through [CovdClient.ensure] and
 * through raw HTTP.
 *
 * Every test walks one scenario from `api/internal/covd/faults.go` and checks the fields
 * the plugin acts on: `state`, `code`, `cause`, `retryable`, `retryAfterMs`, `detail`,
 * `hint`, `message`. The GitLab token must never appear in any of them, including the
 * `trace` the client model drops.
 */
class CovdErrorCodesTest : CovdTestBase() {

    // --- GitLab credential and project errors ---------------------------------------

    @Test
    fun `auth invalid reports AUTH_INVALID with a client cause and is not retried`() {
        gitlab.unauthorized = true

        val response = CovdClient.ensure(request())

        assertError(response, code = "AUTH_INVALID", cause = "client", retryable = false)
        assertHint(response)
        assertTrue(response.message.orEmpty(), response.message.orEmpty().contains("401"))
        // 401 is not a retryable status: the go-gitlab retry loop must not hammer GitLab.
        // (FakeGitLab counts every exchange in `requests`; the per-endpoint counters only
        // see the happy path.)
        assertEquals(1, gitlab.requests.size)
    }

    @Test
    fun `access denied maps a 404 project to ACCESS_DENIED`() {
        // A private project the token cannot see answers exactly like a missing one.
        gitlab.notFound = true

        val response = CovdClient.ensure(request())

        assertError(response, code = "ACCESS_DENIED", cause = "client", retryable = false)
        assertHint(response)
        assertTrue(response.message.orEmpty(), response.message.orEmpty().contains("404"))
        assertEquals(1, gitlab.requests.size)
    }

    // --- request validation ---------------------------------------------------------

    @Test
    fun `project setup rejects each missing required field`() {
        val cases = listOf(
            "settings.baseUrl is required" to request(baseUrl = ""),
            "settings.projectId is required" to request(projectId = ""),
            "component is required" to request(component = ""),
            "settings.token is required" to request(token = ""),
            "at least one job prefix is required" to request(jobPrefixes = emptyList()),
            // Whitespace-only prefixes are filtered before the emptiness check.
            "at least one job prefix is required" to request(jobPrefixes = listOf("  ")),
        )

        for ((expectedMessage, req) in cases) {
            val response = CovdClient.ensure(req)

            assertError(response, code = "PROJECT_SETUP", cause = null, retryable = false)
            assertTrue(
                "expected '$expectedMessage' in '$response'",
                response.message.orEmpty().contains(expectedMessage),
            )
            // KNOWN GAP (see the report): covd builds validation failures with
            // errorResponse(), which leaves `cause` empty, while the contract and
            // docs/covd-integration.md say cause=client for PROJECT_SETUP.
            assertNull("validation errors currently carry no cause", response.cause)
            assertNull("validation errors currently carry no hint", response.hint)
        }
        // Validation runs before any GitLab call.
        assertTrue(gitlab.requests.isEmpty())
    }

    @Test
    fun `project setup reports an unusable baseUrl with a client hint`() {
        // A non-empty but unusable URL reaches GitLab and fails as a url.Error with
        // "unsupported protocol scheme", which classifyFault maps to PROJECT_SETUP.
        val response = CovdClient.ensure(request(baseUrl = "not-a-url"))

        assertError(response, code = "PROJECT_SETUP", cause = "client", retryable = false)
        assertHint(response)
        assertTrue(response.message.orEmpty(), response.message.orEmpty().contains("unsupported protocol scheme"))
    }

    @Test
    fun `project setup reports a baseUrl that cannot be parsed`() {
        // This one never builds a GitLab client: it is again errorResponse(), so no
        // cause or hint is attached (same known gap as the validation loop above).
        val response = CovdClient.ensure(request(baseUrl = "://bad"))

        assertError(response, code = "PROJECT_SETUP", cause = null, retryable = false)
        assertTrue(response.message.orEmpty(), response.message.orEmpty().contains("missing protocol scheme"))
    }

    // --- data availability ----------------------------------------------------------

    @Test
    fun `no component jobs is reported when no job matches the prefixes`() {
        // The pipeline exists, but its coverage job belongs to another component.
        readyPipeline(jobName = "test:behat:coverage:web")

        val response = CovdClient.ensure(request())

        assertError(response, code = "NO_COMPONENT_JOBS", cause = "client", retryable = false)
        assertHint(response)
        assertTrue(response.message.orEmpty(), response.message.orEmpty().contains("no component jobs"))
        assertEquals(1, gitlab.pipelineListCalls)
        assertEquals(1, gitlab.jobListCalls)
        assertEquals(0, gitlab.downloads)
    }

    @Test
    fun `no data is reported when every candidate artifact is gone`() {
        // FakeGitLab always serves artifact bytes, so a dedicated server answers 404 for
        // the artifact: covd skips that candidate, finds no other one and reports NO_DATA.
        val pipelineCalls = AtomicInteger()
        val jobCalls = AtomicInteger()
        val artifactCalls = AtomicInteger()

        RawGitLab { exchange ->
            val path = exchange.requestURI.path
            when {
                path.contains("/artifacts") -> {
                    artifactCalls.incrementAndGet()
                    answer(exchange, 404, """{"message":"404 Not Found"}""")
                }
                path.endsWith("/jobs") -> {
                    jobCalls.incrementAndGet()
                    answer(
                        exchange, 200,
                        """[{"id":70,"name":"$JOB_PREFIX","status":"success","stage":"test",""" +
                            """"artifacts_file":{"filename":"artifacts.zip","size":4096}}]""",
                    )
                }
                path.endsWith("/pipelines") -> {
                    pipelineCalls.incrementAndGet()
                    answer(
                        exchange, 200,
                        """[{"id":7,"sha":"$head","ref":"$BRANCH","status":"success",""" +
                            """"source":"push","web_url":"https://gitlab.example.com/p/7"}]""",
                    )
                }
                else -> answer(exchange, 404, """{"message":"404 Not Found"}""")
            }
        }.use { server ->
            val response = CovdClient.ensure(request(baseUrl = server.baseUrl))

            assertError(response, code = "NO_DATA", cause = "client", retryable = false)
            assertHint(response)
            assertTrue(response.message.orEmpty(), response.message.orEmpty().contains("no coverage data"))
            // Two resolve attempts (the second one skips the exhausted pipeline) and one
            // artifact download in between.
            assertEquals(2, pipelineCalls.get())
            assertEquals(1, jobCalls.get())
            assertEquals(1, artifactCalls.get())
        }
    }

    // --- local git ------------------------------------------------------------------

    @Test
    fun `git errors when gitRoot is not a repository or does not exist`() {
        val notARepository = Files.createTempDirectory("covd-not-git-").toFile()
        try {
            for (root in listOf(notARepository, File(notARepository, "missing"))) {
                val response = CovdClient.ensure(request(gitRoot = root))

                assertError(response, code = "GIT", cause = "client", retryable = false)
                assertHint(response)
                assertTrue(response.message.orEmpty(), response.message.orEmpty().contains("cannot determine HEAD"))
            }
            // A checkout that cannot be read is a local problem: covd never asks GitLab.
            assertTrue("unexpected GitLab calls: ${gitlab.requests}", gitlab.requests.isEmpty())
        } finally {
            notARepository.deleteRecursively()
        }
    }

    // --- transport ------------------------------------------------------------------

    @Test
    fun `connection refused is retryable and carries the refused detail`() {
        // Bind an ephemeral port, learn its number and release it: connecting to it is
        // refused for certain.
        val unreachable = RawGitLab { }
        val url = unreachable.baseUrl
        unreachable.close()

        val response = CovdClient.ensure(request(baseUrl = url))

        assertError(response, code = "CONNECTION", cause = "upstream", retryable = true)
        assertEquals("refused", response.detail)
        assertHint(response)
        assertTrue(response.message.orEmpty(), response.message.orEmpty().contains("connection refused"))
        assertTrue("retryable errors need a retry hint: $response", response.retryAfterMs >= 1_000)
    }

    @Test
    fun `gitlab api 5xx is retried and reported retryable`() {
        for (status in listOf(500, 502)) {
            gitlab.forcedStatus = status
            val before = gitlab.requests.size

            val response = CovdClient.ensure(request())

            assertError(response, code = "GITLAB_API", cause = "upstream", retryable = true)
            assertHint(response)
            assertEquals("gitlab: status $status", response.message)
            assertTrue("retryable errors need a retry hint: $response", response.retryAfterMs >= 1_000)
            // go-gitlab retries 5xx (and 429) five times with a ~700-900 ms backoff.
            assertEquals("HTTP $status must be retried 5 times", 6, gitlab.requests.size - before)
        }
    }

    @Test
    fun `gitlab api client errors are not retried`() {
        gitlab.forcedStatus = 400
        val before = gitlab.requests.size

        val response = CovdClient.ensure(request())

        assertError(response, code = "GITLAB_API", cause = "upstream", retryable = false)
        assertHint(response)
        assertEquals("gitlab: status 400", response.message)
        assertEquals(1, gitlab.requests.size - before)
    }

    // --- daemon authentication ------------------------------------------------------

    @Test
    fun `raw request with a wrong daemon bearer is answered with UNAUTHORIZED`() {
        // A request rejected by validation still completes the handshake, so the daemon
        // is up and its port is known.
        assertEquals("PROJECT_SETUP", CovdClient.ensure(request(baseUrl = "")).code)

        val (status, text) = rawPost(
            path = "/v1/ensure",
            body = CovdClient.encodeRequestForTest(request()),
            bearer = "not-the-daemon-token",
        )

        assertEquals(text, 401, status)
        val parsed = CovdClient.parseEnsureForTest(text)
        assertEquals("UNAUTHORIZED", parsed.code)
        // The 401 body carries no `state`; it is a transport-level answer, and the
        // plugin's `when` falls through to its error branch because it is neither
        // "ready" nor "not_ready".
        assertNotEquals("ready", parsed.state)
        assertNotEquals("not_ready", parsed.state)
        assertTrue(parsed.message.orEmpty(), parsed.message.orEmpty().contains("bearer"))
        // The wrong token itself must not be echoed back.
        assertFalse(text.contains("not-the-daemon-token"))
    }

    @Test
    fun `gitlab token never appears in the error response or its trace`() {
        val sentinel = "glpat-leak-canary-123"
        gitlab.unauthorized = true

        val first = CovdClient.ensure(request(token = sentinel))
        assertEquals("AUTH_INVALID", first.code)
        assertFalse("the client model leaked the token: $first", first.toString().contains(sentinel))

        // Inspect the raw body too: the client model drops `trace`, and the trace is the
        // place where a leaked credential would hide.
        val (status, text) = rawPost(
            path = "/v1/ensure",
            body = CovdClient.encodeRequestForTest(request(token = sentinel)),
            bearer = CovdClient.currentHandshakeForTest()!!.token,
        )

        assertEquals("AUTH_INVALID is a 502 on the wire", 502, status)
        assertFalse("the raw response leaked the GitLab token: $text", text.contains(sentinel))
        assertTrue("trace must say a token was present: $text", text.contains("\"hasToken\":true"))
    }

    // --- protocol contract ----------------------------------------------------------

    @Test
    fun `every error code of the covd describe contract decodes into the client model`() {
        val binary = CovdTestBase.findBinary()
        assertTrue("covd binary disappeared", binary != null)

        val process = ProcessBuilder(binary!!.absolutePath, "--describe").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue("--describe did not finish", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("--describe failed: $output", 0, process.exitValue())

        val published = Json.parseToJsonElement(output.trim()).jsonObject["errorCodes"]!!.jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

        // The daemon-side contract, as published by `covd --describe`.
        val contract = setOf(
            "PROJECT_SETUP", "UNAUTHORIZED", "SHUTTING_DOWN", "AUTH_INVALID", "ACCESS_DENIED",
            "CONNECTION", "GITLAB_API", "NO_COMPONENT_JOBS", "NO_DATA", "GIT", "INTERNAL",
        )
        assertEquals("covd publishes a different error-code set", contract, published)

        // Every published code survives decoding, so a new code cannot be lost silently.
        for (code in published) {
            val parsed = CovdClient.parseEnsureForTest("""{"state":"error","code":"$code"}""")
            assertEquals(code, parsed.code)
            assertEquals("error", parsed.state)
        }

        // CoverageLoadService.describeError() names these codes for the user; the rest
        // fall back to hint/message. Keep both sides honest: the named ones must exist,
        // and only the daemon-side codes may stay unnamed.
        val namedByThePlugin = setOf(
            "AUTH_INVALID", "ACCESS_DENIED", "PROJECT_SETUP", "NO_COMPONENT_JOBS",
            "NO_DATA", "GIT", "CONNECTION", "GITLAB_API",
        )
        assertTrue(
            "codes named by the plugin that covd no longer publishes: ${namedByThePlugin - published}",
            published.containsAll(namedByThePlugin),
        )
        assertEquals(
            "only the daemon-side codes may be handled by the hint/message fallback",
            setOf("UNAUTHORIZED", "SHUTTING_DOWN", "INTERNAL"),
            published - namedByThePlugin,
        )
    }

    // --- helpers --------------------------------------------------------------------

    private fun assertError(
        response: CovdClient.EnsureResponse,
        code: String,
        cause: String?,
        retryable: Boolean,
    ) {
        assertEquals(response.toString(), "error", response.state)
        assertEquals(response.toString(), code, response.code)
        assertEquals(response.toString(), cause, response.cause)
        assertEquals(response.toString(), retryable, response.retryable)
        assertTrue("message must be set: $response", !response.message.isNullOrBlank())
        if (retryable) {
            assertTrue("retryable errors carry retryAfterMs: $response", response.retryAfterMs > 0)
        } else {
            assertEquals(0L, response.retryAfterMs)
        }
    }

    private fun assertHint(response: CovdClient.EnsureResponse) {
        assertTrue("hint must be set: $response", !response.hint.isNullOrBlank())
    }

    private fun rawPost(path: String, body: String, bearer: String): Pair<Int, String> {
        val handshake = CovdClient.currentHandshakeForTest() ?: error("the daemon is not running")
        val connection = URL("http://127.0.0.1:${handshake.port}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A throwaway GitLab that answers exactly what [handler] says. Only used where the
     * shared [FakeGitLab] cannot express the scenario (an artifact 404, a closed port).
     */
    private class RawGitLab(private val handler: (HttpExchange) -> Unit) : AutoCloseable {

        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "covd-raw-gitlab").apply { isDaemon = true }
        }
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    handler(exchange)
                } catch (_: Throwable) {
                    // The client may have gone; nobody is left to answer.
                } finally {
                    exchange.close()
                }
            }
            setExecutor(executor)
            start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun answer(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
