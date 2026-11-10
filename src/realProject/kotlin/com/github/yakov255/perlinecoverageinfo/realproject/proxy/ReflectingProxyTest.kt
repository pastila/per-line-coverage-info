package com.github.yakov255.perlinecoverageinfo.realproject.proxy

import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReflectingProxyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var upstream: TestUpstream
    private lateinit var journal: File
    private lateinit var proxy: ReflectingProxy
    private val client = newClient()

    @Before
    fun setUp() {
        upstream = TestUpstream()
        journal = temporaryFolder.newFile("proxy.jsonl")
        proxy = ReflectingProxy(upstream.url(), journal, 0)
    }

    @After
    fun tearDown() {
        proxy.close()
        upstream.close()
    }

    @Test
    fun removesCredentialHeadersBeforeUpstream() {
        val request = HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/api/v4/projects/335"))
            .header("Authorization", "Bearer dummy")
            .header("PRIVATE-TOKEN", "dummy")
            .header("JOB-TOKEN", "dummy-job")
            .header("Cookie", "session=dummy")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertEquals(1, upstream.requests.size)
        assertEquals(emptyList<String>(), upstream.sawCredentialHeaders())

        val rows = awaitJournal(journal, 1)
        assertEquals(1, rows.size)
        assertEquals("true", rows.single().getValue("authSeen").toString())
    }

    @Test
    fun removesProxyAuthorizationFromRawRequest() {
        rawRequest(
            proxy.port,
            "/api/v4/projects/335",
            mapOf("Proxy-Authorization" to "Basic ZHVtbXk=", "PRIVATE-TOKEN" to "dummy"),
        )
        assertEquals(emptyList<String>(), upstream.sawCredentialHeaders())
        assertEquals("true", awaitJournal(journal, 1).single().getValue("authSeen").toString())
    }

    @Test
    fun marksAuthSeenFalseWithoutCredentials() {
        client.get(proxy.baseUrl() + "/api/v4/projects/335").body().use { it.readBytes() }
        assertEquals("false", awaitJournal(journal, 1).single().getValue("authSeen").toString())
    }

    @Test
    fun forwardsMethodPathQueryAndBody() {
        upstream.responder = { exchange ->
            val body = upstream.requests.last().body
            exchange.send(201, body)
        }
        val payload = patternBytes(100_000)
        val response = client.send(
            HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/api/v4/projects/335/jobs?scope[]=success"))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

        assertEquals(201, response.statusCode())
        assertTrue(payload.contentEquals(response.body()))
        val recorded = upstream.requests.single()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v4/projects/335/jobs", recorded.path)
        assertEquals("scope[]=success", recorded.query)
        assertTrue(recorded.body.contentEquals(payload))
        assertEquals("application/octet-stream", recorded.header("content-type"))

        val row = awaitJournal(journal, 1).single()
        assertEquals("POST", row.string("method"))
        assertEquals(100_000, row.intOrZero("bytesUp"))
        assertEquals(100_000, row.intOrZero("bytesDown"))
    }

    @Test
    fun streamsLargeBodyWithoutBufferingIt() {
        val total = 8 * 1024 * 1024
        val payload = patternBytes(total)
        val firstChunkSize = 64 * 1024
        val clientReceivedFirstChunk = CountDownLatch(1)
        val firstChunkArrivedWhileUpstreamWasSending = AtomicBoolean(false)

        upstream.responder = { exchange ->
            exchange.sendResponseHeaders(200, total.toLong())
            exchange.responseBody.use { output ->
                output.write(payload, 0, firstChunkSize)
                output.flush()
                // If the proxy buffered the whole body, this await times out and the flag stays false.
                firstChunkArrivedWhileUpstreamWasSending.set(
                    clientReceivedFirstChunk.await(5, TimeUnit.SECONDS),
                )
                output.write(payload, firstChunkSize, total - firstChunkSize)
            }
        }

        val response = client.get(proxy.baseUrl() + "/api/v4/projects/335/jobs/42/artifacts")
        assertEquals(200, response.statusCode())

        val received = ByteArrayOutputStream(total)
        var firstRead = true
        response.body().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                received.write(buffer, 0, read)
                if (firstRead) {
                    firstRead = false
                    clientReceivedFirstChunk.countDown()
                }
            }
        }

        assertTrue("proxy buffered the body instead of streaming it", firstChunkArrivedWhileUpstreamWasSending.get())
        assertEquals(total, received.size())
        assertTrue(payload.contentEquals(received.toByteArray()))
        assertEquals(total.toLong(), awaitJournal(journal, 1).single().getValue("bytesDown").toString().toLong())
    }

    @Test
    fun passesStatusAndResponseHeadersThrough() {
        upstream.responder = { exchange ->
            exchange.send(
                404,
                """{"message":"404 Not found"}""".toByteArray(),
                mapOf("Content-Type" to "application/json", "X-Gitlab-Test" to "kept"),
            )
        }
        val response = client.get(proxy.baseUrl() + "/api/v4/projects/999")
        assertEquals(404, response.statusCode())
        assertEquals("kept", response.headers().firstValue("x-gitlab-test").orElse(null))
        assertEquals("""{"message":"404 Not found"}""", response.body().use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals(404, awaitJournal(journal, 1).single().intOrZero("status"))
    }

    @Test
    fun rewritesAbsoluteUpstreamUrlsInLinkAndLocation() {
        upstream.responder = { exchange ->
            exchange.send(
                302,
                ByteArray(0),
                mapOf(
                    "Location" to upstream.url() + "/api/v4/redirected",
                    "Link" to "<" + upstream.url() + "/api/v4/projects/335/pipelines?page=2>; rel=\"next\"",
                ),
            )
        }

        val response = client.get(proxy.baseUrl() + "/api/v4/projects/335/pipelines")
        assertEquals(302, response.statusCode())
        assertEquals(proxy.baseUrl() + "/api/v4/redirected", response.headers().firstValue("location").orElse(null))
        assertEquals(
            "<" + proxy.baseUrl() + "/api/v4/projects/335/pipelines?page=2>; rel=\"next\"",
            response.headers().firstValue("link").orElse(null),
        )
    }

    @Test
    fun keepsChunkedResponseChunked() {
        upstream.responder = { exchange ->
            exchange.sendChunked(200, listOf("hello ".toByteArray(), "chunked".toByteArray()))
        }
        val raw = rawRequest(proxy.port, "/api/v4/chunky")
        val head = raw.substringBefore("\r\n\r\n").lowercase()
        val body = raw.substringAfter("\r\n\r\n")

        assertTrue("expected chunked framing, got:\n$head", head.contains("transfer-encoding: chunked"))
        assertTrue(body.contains("hello"))
        assertTrue(body.contains("chunked"))
    }

    @Test
    fun writesJournalRowsInTheDocumentedFormat() {
        upstream.responder = { exchange -> exchange.send(200, "{}".toByteArray()) }
        client.get(proxy.baseUrl() + "/api/v4/projects/335/pipelines?per_page=1").body().use { it.readBytes() }
        client.get(proxy.baseUrl() + "/api/v4/projects/335/jobs/777/artifacts").body().use { it.readBytes() }
        client.get(proxy.baseUrl() + "/api/v4/projects/335/plain").body().use { it.readBytes() }

        val rows = awaitJournal(journal, 3)
        assertEquals(3, rows.size)
        val documentedKeys = listOf(
            "t", "seq", "method", "path", "query", "status", "bytesDown", "bytesUp",
            "ms", "fault", "artifactJobId", "authSeen",
        )
        for (row in rows) assertEquals(documentedKeys, row.keys.toList())

        val first = rows[0]
        assertEquals("GET", first.string("method"))
        assertEquals("/api/v4/projects/335/pipelines", first.string("path"))
        assertEquals("per_page=1", first.string("query"))
        assertEquals(200, first.intOrZero("status"))
        assertEquals("null", first.getValue("fault").toString())
        assertEquals("null", first.getValue("artifactJobId").toString())
        assertEquals(1, first.intOrZero("seq"))
        assertEquals(2, rows[1].intOrZero("seq"))

        assertEquals(777, rows[1].intOrZero("artifactJobId"))
        assertNull(ReflectingProxy.artifactJobId("/api/v4/projects/335/jobs/not-a-number/artifacts"))
        assertEquals(777, ReflectingProxy.artifactJobId("/api/v4/projects/335/jobs/777/artifacts"))
        assertEquals(0, rows[2].intOrZero("bytesUp"))
    }

    @Test
    fun servesParallelRequests() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val tasks = (1..threads).map { index ->
                pool.submit<Int> {
                    start.await()
                    client.get(proxy.baseUrl() + "/api/v4/projects/335/parallel/$index")
                        .body().use { it.readBytes() }
                    200
                }
            }
            start.countDown()
            assertTrue(tasks.all { it.get(30, TimeUnit.SECONDS) == 200 })
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads, upstream.requests.size)
        val stats = proxy.stats()
        assertEquals(threads, stats.requests)
        assertEquals(threads, stats.byStatus[200])
        assertEquals(threads.toLong() * "upstream-ok".length, stats.bytesDown)
        assertEquals(threads, awaitJournal(journal, threads).size)
    }

    @Test
    fun reportsStatsOnTheControlEndpointWithoutUpstreamTraffic() {
        proxy.onEvent { }
        client.get(proxy.baseUrl() + "/api/v4/projects/335").body().use { it.readBytes() }
        client.get(proxy.baseUrl() + "/api/v4/projects/335").body().use { it.readBytes() }

        val statsResponse = client.get(proxy.baseUrl() + ReflectingProxy.CONTROL_PREFIX + "/stats")
        assertEquals(200, statsResponse.statusCode())
        val statsJson = testJson.parseToJsonElement(
            statsResponse.body().use { it.readBytes().toString(Charsets.UTF_8) },
        ).jsonObject
        assertEquals(2, statsJson["requests"].toString().toInt())
        assertEquals("""{"200":2}""", statsJson["byStatus"].toString())
        // One upstream request per proxied request: the control endpoint adds none and is not journaled.
        assertEquals(2, upstream.requests.size)
        assertEquals(2, awaitJournal(journal, 2).size)
    }
}
