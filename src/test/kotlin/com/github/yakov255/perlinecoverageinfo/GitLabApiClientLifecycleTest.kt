package com.github.yakov255.perlinecoverageinfo

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GitLabApiClientLifecycleTest {
    private fun server() = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private fun client(server: HttpServer) = GitLabApiClient("http://127.0.0.1:${server.address.port}", "token")
    private fun ownThreads() = Thread.getAllStackTraces().keys.filter { it.name.startsWith("Coverage-GitLab-") }.toSet()
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Condition should become true within 5 seconds", condition())
    }

    @Test
    fun `async artifact retries 429 and succeeds`() {
        val server = server()
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            if (requests.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(429, -1)
            } else {
                exchange.sendResponseHeaders(200, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            }
            exchange.close()
        }
        server.start()
        try {
            client(server).use { api ->
                assertArrayEquals(byteArrayOf(1, 2, 3), api.downloadSingleArtifactFileAsync(1, 2, "coverage").get(5, TimeUnit.SECONDS))
                assertEquals(2, requests.get())
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `closing cancels a pending retry and stops owned threads`() {
        val before = ownThreads()
        val server = server()
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "60")
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.start()
        val api = client(server)
        try {
            val result = api.downloadSingleArtifactFileAsync(1, 2, "coverage")
            awaitCondition { (ownThreads() - before).any { it.name == "Coverage-GitLab-Retry" } }
            val created = ownThreads() - before
            api.close()
            api.close()
            assertTrue(result.isCancelled)
            awaitCondition { created.none { it.isAlive } }
            assertEquals(1, requests.get())
            assertTrue(api.downloadSingleArtifactFileAsync(1, 2, "coverage").isCancelled)
        } finally { api.close(); server.stop(0) }
    }

    @Test
    fun `closing cancels an active response without waiting for the server`() {
        val server = server()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/") { exchange ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val api = client(server)
        try {
            val result = api.downloadSingleArtifactFileAsync(1, 2, "coverage")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            api.close()
            assertTrue(result.isCancelled)
        } finally { release.countDown(); api.close(); server.stop(0) }
    }
}
