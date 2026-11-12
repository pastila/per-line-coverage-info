package com.github.yakov255.perlinecoverageinfo.realproject.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One polite smoke against the real GitLab: the proxy must reach it, strip the (dummy) token and
 * journal the request. Skipped when `gitlab.raketa.in` is unreachable, so the suite stays green
 * offline. Downloads only one small API page.
 */
class ReflectingProxyRealGitLabTest {

    @Test
    fun readsOnePipelinesPageThroughTheProxy() {
        assumeTrue("gitlab.raketa.in is unreachable, skipping the real-server smoke", reachable())

        val journal = File.createTempFile("reflecting-proxy-smoke", ".jsonl")
        journal.deleteOnExit()
        try {
            ReflectingProxy(upstream = "https://gitlab.raketa.in", journal = journal).use { proxy ->
                val client = newClient(Duration.ofSeconds(10))
                val path = "/api/v4/projects/335/pipelines?per_page=1"
                var status = 0
                var body = ""
                // The server occasionally answers 504: retry twice with a pause instead of failing.
                for (attempt in 1..3) {
                    if (attempt > 1) Thread.sleep(2_000)
                    val response = try {
                        client.send(
                            HttpRequest.newBuilder(URI.create(proxy.baseUrl() + path))
                                .header("PRIVATE-TOKEN", "dummy")
                                .timeout(Duration.ofSeconds(30))
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    } catch (e: IOException) {
                        null
                    }
                    if (response != null) {
                        status = response.statusCode()
                        body = response.body()
                    }
                    if (status == 200) break
                }
                assumeTrue("gitlab.raketa.in answered $status, skipping the smoke", status == 200)
                assertTrue("expected a JSON array, got: ${body.take(120)}", body.trimStart().startsWith("["))

                val row = readJournal(journal).last {
                    it.string("path") == "/api/v4/projects/335/pipelines"
                }
                assertEquals("GET", row.string("method"))
                assertEquals("per_page=1", row.string("query"))
                assertEquals(200, row.intOrZero("status"))
                assertEquals("true", row.getValue("authSeen").toString())
                assertEquals(1, proxy.stats().requests)
            }
        } finally {
            journal.delete()
        }
    }

    private fun reachable(): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress("gitlab.raketa.in", 443), 3_000) }
        true
    }.getOrDefault(false)
}
