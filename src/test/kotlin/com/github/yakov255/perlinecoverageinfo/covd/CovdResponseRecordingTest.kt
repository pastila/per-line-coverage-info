package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.github.yakov255.perlinecoverageinfo.CovdResponseRecorder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * End-to-end check of `-Dcoverage.covdResponses`: a fake daemon answers one `ensure`/`import`
 * and the file the plugin writes next to it must carry the request, the raw answer and no token.
 */
class CovdResponseRecordingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var workDir: File
    private lateinit var responsesDir: File
    private var server: HttpServer? = null

    @Volatile
    private var ensureBody = """{"state":"ready"}"""

    @Volatile
    private var importBody = """{"state":"ready"}"""

    @Volatile
    private var ensureStatus = 200

    @Before
    fun setUp() {
        assumeFalse(
            "the fake daemon wrapper needs a POSIX shell",
            System.getProperty("os.name").orEmpty().lowercase().contains("win"),
        )
        CovdClient.resetForTest()
        workDir = Files.createTempDirectory("covd-recording-").toFile()
        responsesDir = File(workDir, "covd-responses")
        CovdResponseRecorder.dirOverride = responsesDir
        startFakeDaemon()
    }

    @After
    fun tearDown() {
        CovdClient.resetForTest()
        CovdResponseRecorder.resetForTest()
        server?.stop(0)
        server = null
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    @Test
    fun `an ensure answer is saved with the request and without the token`() {
        ensureBody =
            """{"state":"ready","commit":"e29fd635","cached":false,"replaced":true,"stale":false,"stats":{"files":21621}}"""
        val token = "glpat-from-the-test-run"

        CovdClient.ensure(ensureRequest(token))

        val file = onlyFile()
        assertTrue(file.name, Regex("""^\d{4}-\d{8}-\d{6}-\d{3}-ensure-ready\.json$""").matches(file.name))
        val text = file.readText()
        assertFalse("the GitLab token must never be saved", text.contains(token))
        assertTrue("the answer body must be verbatim", text.endsWith("\"response\":$ensureBody}"))

        val root = json.parseToJsonElement(text).jsonObject
        assertEquals("ensure", root["kind"]!!.jsonPrimitive.content)
        assertEquals(200, root["httpStatus"]!!.jsonPrimitive.content.toInt())
        assertTrue(root["elapsedMs"]!!.jsonPrimitive.content.toLong() >= 0)

        val request = root["request"]!!.jsonObject
        assertEquals("raketa", request["component"]!!.jsonPrimitive.content)
        assertEquals("335", request["projectId"]!!.jsonPrimitive.content)
        assertEquals("/tmp/raketa-load", request["gitRoot"]!!.jsonPrimitive.content)
        assertEquals("master", request["coverageBranch"]!!.jsonPrimitive.content)
        assertEquals("raketa", request["pathMode"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:41000", request["baseUrl"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("test:behat:coverage:raketa", "test:behat:raketa"),
            request["jobPrefixes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `an import answer is saved with kind import`() {
        importBody = """{"state":"ready","commit":"a1b2c3","cov4Path":"/tmp/a1b2c3-raketa.cov4"}"""

        CovdClient.import(
            CovdClient.ImportRequest(
                covtPath = "/tmp/local.covt",
                projectId = "335",
                component = "raketa",
                pathMode = "raketa",
                gitRoot = "/tmp/raketa-load",
            ),
        )

        val file = onlyFile()
        assertTrue(file.name, file.name.endsWith("-import-ready.json"))
        val request = json.parseToJsonElement(file.readText()).jsonObject["request"]!!.jsonObject
        assertEquals("/tmp/local.covt", request["covtPath"]!!.jsonPrimitive.content)
        assertEquals("raketa", request["component"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a daemon error keeps its HTTP status and state in the file name`() {
        ensureBody = """{"state":"error","code":"AUTH_INVALID","cause":"client","retryable":false}"""
        ensureStatus = 502

        CovdClient.ensure(ensureRequest("dummy"))

        val file = onlyFile()
        assertTrue(file.name, file.name.endsWith("-ensure-error.json"))
        assertEquals(502, json.parseToJsonElement(file.readText()).jsonObject["httpStatus"]!!.jsonPrimitive.content.toInt())
    }

    private fun onlyFile(): File {
        val files = responsesDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
        assertEquals("exactly one saved answer", 1, files.size)
        return files.single()
    }

    private fun ensureRequest(token: String) = CovdClient.EnsureRequest(        gitRoot = "/tmp/raketa-load",
        component = "raketa",
        pathMode = "raketa",
        jobPrefixes = listOf("test:behat:coverage:raketa", "test:behat:raketa"),
        settings = CovdClient.Settings(
            baseUrl = "http://127.0.0.1:41000",
            projectId = "335",
            coverageBranch = "master",
            token = token,
        ),
    )

    private fun startFakeDaemon() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange -> handle(exchange) }
        http.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "fake-covd-recording").apply { isDaemon = true }
        }
        http.start()
        server = http

        val script = File(workDir, "covd.sh")
        script.writeText(
            """
            #!/bin/sh
            printf '{"covdReady":{"port":${http.address.port},"token":"daemon-token","instanceId":"inst-recording"}}\n'
            exec sleep 3600
            """.trimIndent() + "\n",
        )
        script.setExecutable(true)
        CovdClient.binaryOverride = script
    }

    private fun handle(exchange: HttpExchange) {
        exchange.requestBody.use { it.readBytes() }
        try {
            when (exchange.requestURI.path) {
                "/v1/ping" -> respond(
                    exchange,
                    200,
                    """{"protocol":1,"build":"fake","instanceId":"inst-recording"}""",
                )
                "/v1/ensure" -> respond(exchange, ensureStatus, ensureBody)
                "/v1/import" -> respond(exchange, 200, importBody)
                "/v1/artifacts" -> respond(exchange, 200, "[]")
                else -> respond(exchange, 404, """{"code":"NOT_FOUND"}""")
            }
        } catch (_: Exception) {
            // The client hung up; nothing to answer.
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
