package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extended `trace` (request, gitlab calls, errors, `saveMs`) as it is documented in
 * `coverage-assistant/docs/covd-integration.md` §5. Every field has a default, so an older
 * daemon that sends only `requestId` still decodes.
 */
class CovdTraceParsingTest {

    @Test
    fun `the full trace from the integration guide is decoded`() {
        val response = CovdClient.parseEnsureForTest(
            """
            {
              "state": "ready",
              "commit": "e29fd6351b2c3d4e5f60718293a4b5c6d7e8f901",
              "trace": {
                "requestId": "r-35d2ad3e6c77fd11",
                "joinedFlight": false,
                "request": {"projectId":"335","component":"raketa","gitRoot":"/tmp/raketa-load",
                            "head":"e29fd6351b2c","coverageBranch":"master",
                            "jobPrefixes":["test:behat:coverage:raketa"],"hasToken":true,
                            "proxy":"http://proxy.local:3128"},
                "outcome": {"state":"ready","httpStatus":200},
                "timing": {"gitMs":2,"resolveMs":1818,"downloadMs":1670,"parseMs":162,
                           "mergeMs":209,"writeMs":626,"saveMs":0,"baselineMs":0,
                           "totalMs":4489,"resolveAttempts":1},
                "gitlab": [{"atMs":3,"method":"GET","path":"/api/v4/projects/335/pipelines",
                            "status":200,"ms":210,"bytes":8123,"attempt":1}],
                "decisions": [{"scope":"primary","pipelineId":2359068,"sha":"e29fd635",
                               "source":"push","code":"chosen"}],
                "errors": []
              }
            }
            """.trimIndent(),
        )

        val trace = requireNotNull(response.trace)
        assertEquals("r-35d2ad3e6c77fd11", trace.requestId)
        assertFalse(trace.joinedFlight)

        val request = requireNotNull(trace.request)
        assertEquals("335", request.projectId)
        assertEquals("raketa", request.component)
        assertEquals("/tmp/raketa-load", request.gitRoot)
        assertEquals("e29fd6351b2c", request.head)
        assertEquals("master", request.coverageBranch)
        assertEquals(listOf("test:behat:coverage:raketa"), request.jobPrefixes)
        assertTrue(request.hasToken)
        assertEquals("http://proxy.local:3128", request.proxy)

        assertEquals(200, trace.outcome?.httpStatus)

        val timing = requireNotNull(trace.timing)
        assertEquals(2L, timing.gitMs)
        assertEquals(1818L, timing.resolveMs)
        assertEquals(1670L, timing.downloadMs)
        assertEquals(162L, timing.parseMs)
        assertEquals(209L, timing.mergeMs)
        assertEquals(626L, timing.writeMs)
        assertEquals(0L, timing.saveMs)
        assertEquals(4489L, timing.totalMs)
        assertEquals(1, timing.resolveAttempts)

        val call = trace.gitlab.single()
        assertEquals(3L, call.atMs)
        assertEquals("GET", call.method)
        assertEquals("/api/v4/projects/335/pipelines", call.path)
        assertEquals(200, call.status)
        assertEquals(210L, call.ms)
        assertEquals(8123L, call.bytes)
        assertEquals(1, call.attempt)
        assertNull(call.error)

        assertEquals("chosen", trace.decisions.single().code)
        assertTrue(trace.errors.isEmpty())
    }

    @Test
    fun `gitlab calls without a status and the errors list are decoded`() {
        val trace = requireNotNull(
            CovdClient.parseEnsureForTest(
                """
                {"state":"error","code":"CONNECTION",
                 "trace":{"requestId":"r-2",
                          "gitlab":[{"atMs":0,"method":"GET","path":"/api/v4/projects/335/pipelines",
                                     "ms":5000,"attempt":2,"error":"timeout"},
                                    {"atMs":5000,"method":"GET","path":"/api/v4/projects/335/jobs",
                                     "status":502,"ms":40,"bytes":120,"attempt":2}],
                          "errors":[{"scope":"baseline","code":"NO_DATA","message":"no baseline coverage"},
                                    {"scope":"cache","code":"WRITE","message":"disk full","retryable":true}]}}
                """.trimIndent(),
            ).trace,
        )

        assertEquals(2, trace.gitlab.size)
        assertNull("no status means no answer at all", trace.gitlab[0].status)
        assertEquals("timeout", trace.gitlab[0].error)
        assertEquals(502, trace.gitlab[1].status)

        assertEquals(listOf("baseline", "cache"), trace.errors.map { it.scope })
        assertEquals("NO_DATA", trace.errors[0].code)
        assertFalse(trace.errors[0].retryable)
        assertTrue(trace.errors[1].retryable)
    }

    @Test
    fun `null lists from the daemon are coerced to empty ones`() {
        // A Go daemon may marshal a nil slice as null; a trace must never fail to decode.
        val trace = requireNotNull(
            CovdClient.parseEnsureForTest(
                """{"state":"ready","trace":{"requestId":"r-1","gitlab":null,"errors":null,"decisions":null,
                    "request":{"projectId":"335","jobPrefixes":null,"hasToken":true}}}""",
            ).trace,
        )

        assertTrue(trace.gitlab.isEmpty())
        assertTrue(trace.errors.isEmpty())
        assertTrue(trace.decisions.isEmpty())
        assertTrue(requireNotNull(trace.request).jobPrefixes.isEmpty())
    }

    @Test
    fun `an older trace decodes with default fields`() {
        val trace = requireNotNull(
            CovdClient.parseEnsureForTest("""{"state":"ready","trace":{"requestId":"r-1"}}""").trace,
        )

        assertNull(trace.request)
        assertNull(trace.timing)
        assertTrue(trace.gitlab.isEmpty())
        assertTrue(trace.errors.isEmpty())
        assertTrue(trace.decisions.isEmpty())
    }
}
