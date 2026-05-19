package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [McpHandler]'s JSON-RPC dispatch logic.
 *
 * These tests exercise the protocol layer — request parsing, method routing,
 * and response structure — without requiring a live Project or CoverageDataService.
 * The handler is constructed with a lookup that always returns null; tool-call tests
 * that need coverage data either expect a "project not found" error or pass through
 * to the no-coverage-data path.
 */
class McpHandlerTest {

    private fun createHandler(): McpHandler = McpHandler { null }

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
    fun toolsListReturnsBothTools() {
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

        assertEquals(2, tools.size)

        // get_coverage_for_file schema
        val getCoverageTool = tools[0].jsonObject
        assertEquals("get_coverage_for_file", getCoverageTool["name"]?.jsonPrimitive?.content)
        assertNotNull(getCoverageTool["description"])
        assertNotNull(getCoverageTool["inputSchema"])

        val getSchema = getCoverageTool["inputSchema"]!!.jsonObject
        assertEquals("object", getSchema["type"]?.jsonPrimitive?.content)
        val getProps = getSchema["properties"]!!.jsonObject
        assertNotNull(getProps["project"])
        assertNotNull(getProps["file_path"])
        assertNotNull(getProps["detail"])
        val getRequired = getSchema["required"]!!.jsonArray
        assertTrue(getRequired.any { it.jsonPrimitive.content == "project" })
        assertTrue(getRequired.any { it.jsonPrimitive.content == "file_path" })

        val detailSchema = getProps["detail"]!!.jsonObject
        assertEquals("string", detailSchema["type"]?.jsonPrimitive?.content)
        val detailEnum = detailSchema["enum"]!!.jsonArray
        assertTrue(detailEnum.map { it.jsonPrimitive.content }.containsAll(listOf("summary", "detailed")))

        // list_files schema
        val listFilesTool = tools[1].jsonObject
        assertEquals("list_files", listFilesTool["name"]?.jsonPrimitive?.content)
        assertNotNull(listFilesTool["description"])
        assertNotNull(listFilesTool["inputSchema"])

        val listSchema = listFilesTool["inputSchema"]!!.jsonObject
        val listProps = listSchema["properties"]!!.jsonObject
        assertNotNull(listProps["project"])
        assertNotNull(listProps["path"])
        assertNotNull(listProps["recursive"])
        assertNotNull(listProps["sort"])
        assertNotNull(listProps["offset"])
        assertNotNull(listProps["coverage"])
        val listRequired = listSchema["required"]!!.jsonArray
        assertTrue(listRequired.any { it.jsonPrimitive.content == "project" })
        assertTrue(listRequired.any { it.jsonPrimitive.content == "path" })
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

    // --- tools/call with missing project (required) ---

    @Test
    fun toolsCallMissingProjectReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 8)
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
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("Missing required argument: project"))
    }

    // --- tools/call with missing file_path ---

    @Test
    fun toolsCallMissingFilePathReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 9)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "get_coverage_for_file")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("file_path"))
    }

    // --- tools/call with project that doesn't resolve ---

    @Test
    fun toolsCallUnrecognizedProjectReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 10)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "get_coverage_for_file")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
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
        assertTrue("Should mention no project found", text.contains("No project found for path"))
    }

    // --- ping ---

    @Test
    fun pingReturnsEmptyResult() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 11)
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

    // --- collapseToRanges ---

    @Test
    fun collapseToRangesEmpty() {
        val handler = createHandler()
        assertEquals("none", handler.collapseToRanges(emptyList()))
    }

    @Test
    fun collapseToRangesSingleLine() {
        val handler = createHandler()
        assertEquals("5", handler.collapseToRanges(listOf(5)))
    }

    @Test
    fun collapseToRangesConsecutiveLines() {
        val handler = createHandler()
        assertEquals("1-5", handler.collapseToRanges(listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun collapseToRangesMixed() {
        val handler = createHandler()
        assertEquals("1-3, 7, 10-12", handler.collapseToRanges(listOf(1, 2, 3, 7, 10, 11, 12)))
    }

    // --- detail parameter ---

    @Test
    fun toolsCallWithInvalidDetailReturnsError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 12)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "get_coverage_for_file")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                    put("file_path", "src/Foo.php")
                    put("detail", "invalid")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("Invalid argument 'detail'"))
    }

    @Test
    fun toolsCallWithDetailParameterAcceptsSummary() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 13)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "get_coverage_for_file")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                    put("file_path", "src/Foo.php")
                    put("detail", "detailed")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        assertNull(response["error"])

        val result = response["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("No project found for path"))
    }

    // --- list_files tool ---

    @Test
    fun listFilesMissingProjectReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 20)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "list_files")
                put("arguments", buildJsonObject {
                    put("path", "src")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("project"))
    }

    @Test
    fun listFilesMissingPathReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 21)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "list_files")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("path"))
    }

    @Test
    fun listFilesInvalidSortReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 22)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "list_files")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                    put("path", "src")
                    put("sort", "invalid")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("Invalid argument 'sort'"))
    }

    @Test
    fun listFilesNegativeOffsetReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 23)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "list_files")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                    put("path", "src")
                    put("offset", -1)
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject

        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("offset"))
    }

    @Test
    fun listFilesUnrecognizedProjectReturnsNoProject() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 24)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "list_files")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                    put("path", "src")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        assertNull(response["error"])

        val result = response["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("No project found for path"))
    }

    @Test
    fun listFilesDefaultsToCoverageAscSort() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 25)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "list_files")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                    put("path", "")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        assertNull(response["error"])

        val result = response["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("No project found for path"))
    }

    @Test
    fun listFilesInvalidCoverageReturnsToolError() {
        val handler = createHandler()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 26)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "list_files")
                put("arguments", buildJsonObject {
                    put("project", "/home/user/test-project")
                    put("path", "src")
                    put("coverage", "invalid")
                })
            })
        }.toString()

        val responseJson = handler.handle(request)!!
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val result = response["result"]!!.jsonObject
        assertTrue(result["isError"]?.jsonPrimitive?.boolean == true)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        assertTrue(text.contains("coverage"))
    }
}
