package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CovdClientTest {

    private val request = CovdClient.EnsureRequest(
        gitRoot = "/repo",
        component = "raketa",
        pathMode = "raketa",
        jobPrefixes = listOf("test:behat:coverage:raketa", "test:behat:raketa"),
        settings = CovdClient.Settings(
            baseUrl = "https://gitlab.example",
            projectId = "335",
            coverageBranch = "master",
            token = "glpat-secret",
        ),
    )

    @Test
    fun `handshake line is parsed`() {
        val handshake = CovdClient.parseHandshakeForTest(
            """{"covdReady":{"port":41231,"token":"abc","instanceId":"inst-1"}}""",
        )
        assertEquals(41231, handshake?.port)
        assertEquals("abc", handshake?.token)
        assertEquals("inst-1", handshake?.instanceId)
    }

    @Test
    fun `request carries every field the daemon requires`() {
        val json = CovdClient.encodeRequestForTest(request)
        listOf(
            "\"gitRoot\":\"/repo\"",
            "\"component\":\"raketa\"",
            "\"pathMode\":\"raketa\"",
            "\"jobPrefixes\":[\"test:behat:coverage:raketa\",\"test:behat:raketa\"]",
            "\"projectId\":\"335\"",
            "\"coverageBranch\":\"master\"",
            "\"token\":\"glpat-secret\"",
        ).forEach { expected ->
            assertTrue("missing $expected in $json", json.contains(expected))
        }
    }

    @Test
    fun `ready response keeps paths and stats`() {
        val response = CovdClient.parseEnsureForTest(
            """
            {"state":"ready","commit":"e29fd635","pipelineId":2359068,
             "cov4Path":"/cache/335/e29fd635-raketa.cov4","cached":false,"replaced":true,
             "stale":false,"stats":{"files":21621,"lines":425953,"covered":326369},
             "baselineCommit":"baseline123","baselineCov4Path":"/cache/335/baseline-raketa.cov4"}
            """.trimIndent(),
        )
        assertEquals("ready", response.state)
        assertEquals("e29fd635", response.commit)
        assertEquals("/cache/335/e29fd635-raketa.cov4", response.cov4Path)
        assertTrue(response.replaced)
        assertEquals(21621, response.stats?.files)
        assertEquals("baseline123", response.baselineCommit)
    }

    @Test
    fun `not ready response carries a retry hint`() {
        val response = CovdClient.parseEnsureForTest(
            """{"state":"not_ready","reason":"jobs_running","retryAfterMs":10000,"commit":"abc"}""",
        )
        assertEquals("not_ready", response.state)
        assertEquals("jobs_running", response.reason)
        assertEquals(10_000, response.retryAfterMs)
        assertNull(response.cov4Path)
    }

    @Test
    fun `import response keeps the cache path and stats`() {
        val response = CovdClient.jsonImportForTest(
            """
            {"state":"ready","commit":"a1b2c3","cov4Path":"/cache/335/a1b2c3-raketa.cov4",
             "stats":{"files":3,"lines":10,"covered":7}}
            """.trimIndent(),
        )
        assertEquals("ready", response.state)
        assertEquals("a1b2c3", response.commit)
        assertEquals("/cache/335/a1b2c3-raketa.cov4", response.cov4Path)
        assertEquals(7, response.stats?.covered)
    }

    @Test
    fun `error response carries code and hint`() {
        val response = CovdClient.parseEnsureForTest(
            """
            {"state":"error","code":"AUTH_INVALID","cause":"client","retryable":false,
             "hint":"token expired","message":"invalid token"}
            """.trimIndent(),
        )
        assertEquals("error", response.state)
        assertEquals("AUTH_INVALID", response.code)
        assertEquals("client", response.cause)
        assertEquals("token expired", response.hint)
    }
}
