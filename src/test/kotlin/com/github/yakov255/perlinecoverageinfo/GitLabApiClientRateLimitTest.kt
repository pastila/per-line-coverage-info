package com.github.yakov255.perlinecoverageinfo

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class GitLabApiClientRateLimitTest {

    private fun pipelineJson() =
        """[{"id":1,"sha":"abc","ref":"master","status":"success","web_url":"http://x"}]"""

    private fun respond429(exchange: com.sun.net.httpserver.HttpExchange) {
        exchange.responseHeaders.add("Retry-After", "1")
        exchange.sendResponseHeaders(429, -1)
    }

    private fun respond200(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `429 is retried and eventually succeeds`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val requests = AtomicInteger(0)
        server.createContext("/api/v4/projects/335/pipelines") { exchange ->
            if (requests.incrementAndGet() == 1) {
                respond429(exchange)
            } else {
                respond200(exchange, pipelineJson())
            }
            exchange.close()
        }
        server.start()
        try {
            val client = GitLabApiClient("http://127.0.0.1:${server.address.port}", "token")
            val pipelines = client.listPipelines(335, "master", "success", 100)
            assertEquals(2, requests.get())
            assertEquals(1, pipelines.size)
            assertEquals("abc", pipelines[0].sha)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `429 retry honours Retry-After delay`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val requests = AtomicInteger(0)
        server.createContext("/api/v4/projects/335/pipelines") { exchange ->
            if (requests.incrementAndGet() == 1) {
                respond429(exchange)
            } else {
                respond200(exchange, "[]")
            }
            exchange.close()
        }
        server.start()
        try {
            val client = GitLabApiClient("http://127.0.0.1:${server.address.port}", "token")
            val started = System.currentTimeMillis()
            client.listPipelines(335, "master", "success", 100)
            val elapsed = System.currentTimeMillis() - started
            assertTrue("expected ~1s Retry-After delay, took $elapsed ms", elapsed >= 900)
            assertEquals(2, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `requests carry a plugin identifying User-Agent`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var userAgent: String? = null
        server.createContext("/api/v4/projects/335/pipelines") { exchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            respond200(exchange, "[]")
            exchange.close()
        }
        server.start()
        try {
            val client = GitLabApiClient("http://127.0.0.1:${server.address.port}", "token")
            client.listPipelines(335, "master", "success", 100)
            assertNotNull("User-Agent header must be present", userAgent)
            assertTrue(
                "expected plugin UA, got '$userAgent'",
                userAgent!!.startsWith("Per-Line-Coverage-Info/"),
            )
        } finally {
            server.stop(0)
        }
    }
}
