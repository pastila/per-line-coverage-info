package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The HTTP half of the inventory against a local stub: paging, retry of transient 5xx and the
 * guarantee that no credential header is ever sent.
 */
class GitLabInventoryHttpTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val failure500 = AtomicInteger(0)
    private val pipelinesAnswers = AtomicInteger(0)
    private val retries = AtomicInteger(0)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.query ?: ""
            requests += "$path?$query"
            assertFalse(
                "the inventory must stay anonymous",
                exchange.requestHeaders.containsKey("Authorization") ||
                    exchange.requestHeaders.containsKey("Private-Token"),
            )
            when {
                path.endsWith("/pipelines") -> {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    if (failure500.getAndDecrement() > 0) {
                        respond(exchange, 503, """{"message":"503 Service Unavailable"}""")
                    } else if (pipelinesAnswers.getAndIncrement() == 0) {
                        respond(exchange, 200, PIPELINES_PAGE)
                    } else {
                        respond(exchange, 200, "[]")
                    }
                }

                path.endsWith("/pipelines/10/jobs") -> respond(exchange, 200, JOBS_10)
                path.endsWith("/pipelines/11/jobs") -> respond(exchange, 200, "[]")
                else -> respond(exchange, 404, """{"message":"404 Not Found"}""")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun inventory(maxAttempts: Int = 4): GitLabInventory = GitLabInventory(
        client = GitLabClient(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            pauseMs = 0,
            maxAttempts = maxAttempts,
            sleeper = { retries.incrementAndGet() },
        ),
        projectId = "335",
        pageSize = 100,
        maxPages = 2,
    )

    @Test
    fun `scan retries a 503 and summarizes the coverage jobs`() {
        failure500.set(1)
        var progressReports = 0

        val snapshot = GitLabInventory(
            client = GitLabClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                pauseMs = 0,
                maxAttempts = 4,
                sleeper = { retries.incrementAndGet() },
            ),
            projectId = "335",
            progress = { progressReports++ },
        ).scan(limit = 2)

        assertEquals("one retry for the 503", 1, retries.get())
        assertEquals(2, snapshot.pipelinesScanned)
        assertEquals(listOf("hotels", "raketa"), snapshot.components.sorted())
        assertEquals(7, snapshot.coverage.first { it.pipeline.id == 10L }.shardsOf("raketa"))
        assertEquals(32_102_000L, snapshot.coverage.first { it.pipeline.id == 10L }.bytesOf("raketa"))
        assertEquals(0, progressReports)
        assertTrue(requests.first().startsWith("/api/v4/projects/335/pipelines?"))
        assertTrue(requests.first().contains("status=success"))
    }

    @Test
    fun `scan stops after the page limit`() {
        val snapshot = inventory().scan(limit = 200)

        assertEquals(2, snapshot.pipelinesScanned)
        // One retried page, the second (empty) page, and one jobs request per pipeline.
        assertEquals(1, requests.count { it.startsWith("/api/v4/projects/335/pipelines?") })
        assertEquals(2, requests.count { it.contains("/jobs?") })
    }

    @Test
    fun `permanent 5xx fails after the allowed attempts`() {
        failure500.set(100)
        val client = GitLabClient(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            pauseMs = 0,
            maxAttempts = 3,
            sleeper = { retries.incrementAndGet() },
        )

        assertThrows(IOException::class.java) {
            client.get("/api/v4/projects/335/pipelines?status=success")
        }
        assertEquals(3, requests.size)
    }

    private companion object {
        val PIPELINES_PAGE = """
            [
              {"id":10,"iid":10,"project_id":335,"sha":"${"a".repeat(40)}","ref":"master",
               "status":"success","source":"push","created_at":"2026-11-10T10:00:00.000+05:00",
               "updated_at":"2026-11-10T11:00:00.000+05:00",
               "web_url":"https://gitlab.raketa.in/raketa/raketa/-/pipelines/10"},
              {"id":11,"iid":11,"project_id":335,"sha":"${"b".repeat(40)}","ref":"feature-x",
               "status":"success","source":"push","created_at":"2026-11-10T09:00:00.000+05:00",
               "updated_at":"2026-11-10T09:10:00.000+05:00",
               "web_url":"https://gitlab.raketa.in/raketa/raketa/-/pipelines/11"}
            ]
        """.trimIndent()

        val JOBS_10 = """
            [
              {"id":1,"name":"test:behat:coverage:raketa-8.5: [sprint:1]","status":"success",
               "artifacts_file":{"filename":"artifacts.zip","size":4586000}},
              {"id":2,"name":"test:behat:coverage:raketa-8.5: [sprint:2]","status":"success",
               "artifacts_file":{"filename":"artifacts.zip","size":4586000}},
              {"id":3,"name":"test:behat:coverage:raketa-8.5: [sprint:3]","status":"success",
               "artifacts_file":{"filename":"artifacts.zip","size":4586000}},
              {"id":4,"name":"test:behat:coverage:raketa-8.5: [sprint:4]","status":"success",
               "artifacts_file":{"filename":"artifacts.zip","size":4586000}},
              {"id":5,"name":"test:behat:coverage:raketa-8.5: [sprint:5]","status":"success",
               "artifacts_file":{"filename":"artifacts.zip","size":4586000}},
              {"id":6,"name":"test:behat:coverage:raketa-8.5: [sprint:6]","status":"success",
               "artifacts_file":{"filename":"artifacts.zip","size":4586000}},
              {"id":7,"name":"test:behat:coverage:raketa-8.5: [sprint:7]","status":"success",
               "artifacts_file":{"filename":"artifacts.zip","size":4586000}},
              {"id":8,"name":"test:behat:coverage:hotels-8.5","status":"success",
               "artifacts_file":{"filename":"artifacts.zip","size":683765}},
              {"id":9,"name":"codecov-8.5","status":"success"}
            ]
        """.trimIndent()
    }
}
