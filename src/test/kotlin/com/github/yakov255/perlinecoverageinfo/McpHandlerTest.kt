package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [McpHandler]'s JSON-RPC dispatch logic.
 *
 * These tests exercise the protocol layer — request parsing, method routing,
 * and response structure — without requiring a live Project or CoverageDataService.
 * The handler is constructed with a null project; tool-call tests that need coverage
 * data are expected to produce a controlled error path.
 */
class McpHandlerTest {

    private fun createHandler(): McpHandler = McpHandler(null)

    // --- initialize ---

    @Test
    fun initializeReturnsProtocolVersion() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject

        assertEquals("2.0", response["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(1, response["id"]?.jsonPrimitive?.int)
        assertNull(response["error"])

        val result = response["result"]!!.jsonObject
        assertEquals("2025-03-26", result["protocolVersion"]?.jsonPrimitive?.content)

        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals("per-line-coverage-info", serverInfo["name"]?.jsonPrimitive?.content)
        assertTrue(serverInfo["version"]?.jsonPrimitive?.content?.isNotBlank() == true)

        val capabilities = result["capabilities"]!!.jsonObject
        assertNotNull(capabilities["tools"])
    }

    // --- tools/list ---

    @Test
    fun toolsListReturnsSingleTool() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/list")
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject
        val tools = result["tools"]!!.jsonArray

        assertEquals(1, tools.size)
        val tool = tools[0].jsonObject
        assertEquals("get_coverage_for_file", tool["name"]?.jsonPrimitive?.content)
        assertNotNull(tool["description"])
        assertNotNull(tool["inputSchema"])

        val schema = tool["inputSchema"]!!.jsonObject
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertNotNull(schema["properties"]?.jsonObject?.get("file_path"))
        val required = schema["required"]!!.jsonArray
        assertTrue(required.any { it.jsonPrimitive.content == "file_path" })
    }

    // --- unknown method ---

    @Test
    fun unknownMethodReturnsError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "nonexistent/method")
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        assertNotNull(response["error"])

        val error = response["error"]!!.jsonObject
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
        assertTrue(error["message"]?.jsonPrimitive?.content?.contains("Method not found") == true)
    }

    // --- notification (no id) ---

    @Test
    fun notificationReturnsNull() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }.toString()

        val response = handler.handle(request)
        assertNull("Notifications should return null (no response)", response)
    }

    // --- missing method ---

    @Test
    fun missingMethodReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 5)
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val error = response["error"]!!.jsonObject
        assertEquals(-32600, error["code"]?.jsonPrimitive?.int)
    }

    // --- tools/call with missing tool name ---

    @Test
    fun toolsCallMissingNameReturnsError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 6)
            put("method", "tools/call")
            put("params", buildJsonObject {})
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        assertNotNull(response["error"])
        assertEquals(-32602, response["error"]!!.jsonObject["code"]?.jsonPrimitive?.int)
    }

    // --- tools/call with unknown tool ---

    @Test
    fun toolsCallUnknownToolReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 7)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "nonexistent_tool")
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val content = result["content"]!!.jsonArray
        assertTrue(content[0].jsonObject["text"]?.jsonPrimitive?.content?.contains("Unknown tool") == true)
    }

    // --- tools/call with missing file_path ---

    @Test
    fun toolsCallMissingFilePathReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 8)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "get_coverage_for_file")
                put("arguments", buildJsonObject {})
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("file_path"))
    }

    // --- ping ---

    @Test
    fun toolsCallWithFilePathButNoProjectReturnsToolContent() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 10)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "get_coverage_for_file")
                put("arguments", buildJsonObject {
                    put("file_path", "src/Service/Foo.php")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        assertNull(response["error"])

        val result = response["result"]!!.jsonObject
        val content = result["content"]!!.jsonArray
        assertEquals(1, content.size)
        val text = content[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue("Should mention no project context", text.contains("No project"))
    }

    // --- ping ---

    @Test
    fun pingReturnsEmptyResult() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 9)
            put("method", "ping")
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        assertNull(response["error"])
        assertNotNull(response["result"])
    }

    // --- malformed JSON ---

    @Test
    fun malformedJsonReturnsParseError() {
        val handler = createHandler()
        val responseJson = handler.handle("not valid json{{{")!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val error = response["error"]!!.jsonObject
        assertEquals(-32700, error["code"]?.jsonPrimitive?.int)
    }
}
