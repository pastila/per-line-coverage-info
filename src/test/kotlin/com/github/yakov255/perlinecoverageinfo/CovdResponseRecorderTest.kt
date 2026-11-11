package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CovdResponseRecorderTest {

    private lateinit var dir: File
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("covd-responses-").toFile()
        CovdResponseRecorder.resetForTest()
        CovdResponseRecorder.dirOverride = dir
    }

    @After
    fun tearDown() {
        CovdResponseRecorder.resetForTest()
        System.clearProperty(CoveragePaths.COVD_RESPONSES_PROPERTY)
        dir.deleteRecursively()
    }

    private fun request() = buildJsonObject {
        put("component", "raketa")
        put("projectId", "335")
        put("gitRoot", "/tmp/raketa-load")
        put("coverageBranch", "master")
        put("pathMode", "raketa")
        put("force", false)
    }

    private fun onlyFile(): File {
        val files = dir.listFiles().orEmpty()
        assertEquals("exactly one answer file", 1, files.size)
        return files.single()
    }

    @Test
    fun `file name follows the documented pattern`() {
        CovdResponseRecorder.record("ensure", 1234, 200, request(), """{"state":"ready"}""", null)

        val name = onlyFile().name
        assertTrue(name, Regex("""^\d{4}-\d{8}-\d{6}-\d{3}-ensure-ready\.json$""").matches(name))
    }

    @Test
    fun `file name starts with the zero padded sequence number`() {
        val name = CovdResponseRecorder.fileName(7, 1_760_000_000_000, "ensure", "ready")
        assertTrue(name, name.startsWith("0007-"))
        assertTrue(name, name.endsWith("-ensure-ready.json"))
        assertTrue(name, Regex("""^0007-\d{8}-\d{6}-\d{3}-ensure-ready\.json$""").matches(name))
    }

    @Test
    fun `answer body is embedded unmodified and request fields are kept`() {
        val body = """{"state":"ready","commit":"e29fd635","cached":false, "stats":{"files":3}}"""

        val seq = CovdResponseRecorder.record("ensure", 4321, 200, request(), body, null)

        val file = onlyFile()
        val text = file.readText()
        assertTrue(text, text.endsWith("\"response\":$body}"))

        val root = json.parseToJsonElement(text).jsonObject
        assertEquals(seq, root["seq"]!!.jsonPrimitive.content.toInt())
        assertEquals("ensure", root["kind"]!!.jsonPrimitive.content)
        assertEquals(4321L, root["elapsedMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(200, root["httpStatus"]!!.jsonPrimitive.content.toInt())
        assertTrue("atMs should be an epoch millisecond value", root["atMs"]!!.jsonPrimitive.content.toLong() > 1_600_000_000_000L)

        val savedRequest = root["request"]!!.jsonObject
        assertEquals("raketa", savedRequest["component"]!!.jsonPrimitive.content)
        assertEquals("335", savedRequest["projectId"]!!.jsonPrimitive.content)
        assertEquals("/tmp/raketa-load", savedRequest["gitRoot"]!!.jsonPrimitive.content)
        assertEquals("master", savedRequest["coverageBranch"]!!.jsonPrimitive.content)
        assertEquals("raketa", savedRequest["pathMode"]!!.jsonPrimitive.content)
        assertFalse("force is a request field, not an answer one", savedRequest["force"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the token is scrubbed even when the request carries it`() {
        val token = "glpat-super-secret-token"
        val requestWithToken = buildJsonObject {
            put("component", "raketa")
            put("note", "token=$token")
        }

        CovdResponseRecorder.record("ensure", 5, 200, requestWithToken, """{"state":"ready"}""", token)

        val text = onlyFile().readText()
        assertFalse("the token must never reach the file", text.contains(token))
        assertTrue(text, "***" in text)
    }

    @Test
    fun `a body that is not JSON is stored as a string and named unparseable`() {
        CovdResponseRecorder.record("ensure", 5, 502, request(), "<html>Bad Gateway</html>", null)

        val file = onlyFile()
        assertTrue(file.name, file.name.endsWith("-unparseable.json"))
        val root = json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("<html>Bad Gateway</html>", (root["response"] as JsonPrimitive).content)
    }

    @Test
    fun `a body without a state field is named unknown`() {
        CovdResponseRecorder.record("import", 5, 200, request(), """{"commit":"abc"}""", null)

        assertTrue(onlyFile().name, onlyFile().name.endsWith("-import-unknown.json"))
    }

    @Test
    fun `each answer gets its own sequence number`() {
        val first = CovdResponseRecorder.record("ensure", 1, 200, request(), """{"state":"ready"}""", null)
        val second = CovdResponseRecorder.record("ensure", 2, 200, request(), """{"state":"ready"}""", null)

        assertEquals(first + 1, second)
        val names = dir.listFiles().orEmpty().map { it.name }.sorted()
        assertTrue(names[0], names[0].startsWith(String.format("%04d-", first)))
        assertTrue(names[1], names[1].startsWith(String.format("%04d-", second)))
    }

    @Test
    fun `a write failure does not throw and does not lose the sequence`() {
        val blocked = File(dir, "blocked")
        blocked.writeText("not a directory")
        CovdResponseRecorder.dirOverride = blocked

        val seq = CovdResponseRecorder.record("ensure", 1, 200, request(), """{"state":"ready"}""", null)

        assertTrue(seq >= 1)
        assertTrue(blocked.isFile)
    }

    @Test
    fun `nothing is written while the property and the seam are unset`() {
        CovdResponseRecorder.dirOverride = null

        val seq = CovdResponseRecorder.record("ensure", 1, 200, request(), """{"state":"ready"}""", null)

        assertTrue(seq >= 1)
        assertEquals("no file when saving is off", 0, dir.listFiles().orEmpty().size)
    }
}
