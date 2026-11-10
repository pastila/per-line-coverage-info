package com.github.yakov255.perlinecoverageinfo.realproject.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ReflectingProxyFaultsTest {

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
    fun throttlesDownloadToTheConfiguredRate() {
        val payload = patternBytes(300_000)
        upstream.responder = { exchange -> exchange.send(200, payload) }
        proxy.throttle(300_000, "/api/v4/large")

        val startedAt = System.nanoTime()
        val response = client.get(proxy.baseUrl() + "/api/v4/large")
        val body = response.body().use { it.readBytes() }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(200, response.statusCode())
        assertTrue(payload.contentEquals(body))
        assertTrue("throttled download took only $elapsedMs ms", elapsedMs >= 750)
        assertEquals(
            "throttle",
            awaitJournal(journal, 1).single().getValue("fault").toString().trim('"'),
        )
    }

    @Test
    fun throttleDoesNotApplyToOtherPaths() {
        val payload = patternBytes(300_000)
        upstream.responder = { exchange -> exchange.send(200, payload) }
        proxy.throttle(10_000, "/api/v4/large")

        val startedAt = System.nanoTime()
        client.get(proxy.baseUrl() + "/api/v4/small").body().use { it.readBytes() }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("unmatched path was throttled: $elapsedMs ms", elapsedMs < 300)
    }

    @Test
    fun dropsConnectionAfterBytesOnceThenServesNormally() {
        val total = 512 * 1024
        val afterBytes = 64 * 1024
        val payload = patternBytes(total)
        upstream.responder = { exchange -> exchange.send(200, payload) }
        proxy.dropAfter("/api/v4/jobs/9/artifacts", afterBytes.toLong(), times = 1)

        val firstResponse = client.get(proxy.baseUrl() + "/api/v4/jobs/9/artifacts")
        assertEquals(200, firstResponse.statusCode())
        var received = 0L
        var failed = false
        try {
            firstResponse.body().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    received += read
                }
            }
        } catch (expected: IOException) {
            failed = true
        }
        assertTrue("the client must see a broken body", failed)
        assertTrue("client received $received bytes of $total", received < total)

        val secondResponse = client.get(proxy.baseUrl() + "/api/v4/jobs/9/artifacts")
        assertEquals(200, secondResponse.statusCode())
        assertTrue(payload.contentEquals(secondResponse.body().use { it.readBytes() }))

        val rows = awaitJournal(journal, 2)
        assertEquals(2, rows.size)
        assertEquals("drop", rows[0].getValue("fault").toString().trim('"'))
        assertEquals("null", rows[1].getValue("fault").toString())
        assertEquals(1, proxy.stats().artifactsCompleted)
    }

    @Test
    fun respondsWithStatusFromTheConfiguredRequestOnwards() {
        proxy.respondStatus(
            pathRegex = "/api/v4/projects/335/pipelines",
            status = 401,
            fromRequestN = 2,
            times = 1,
            body = """{"message":"401 Unauthorized"}""",
        )

        val path = "/api/v4/projects/335/pipelines?per_page=1"
        val first = client.get(proxy.baseUrl() + path)
        assertEquals(200, first.statusCode())
        first.body().close()

        val second = client.get(proxy.baseUrl() + path)
        assertEquals(401, second.statusCode())
        assertEquals("""{"message":"401 Unauthorized"}""", second.body().use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals(1, upstream.requests.size)

        val third = client.get(proxy.baseUrl() + path)
        assertEquals(200, third.statusCode())
        third.body().close()
        assertEquals(2, upstream.requests.size)

        val rows = awaitJournal(journal, 3)
        val statuses = rows.map { it.intOrZero("status") }
        assertEquals(listOf(200, 401, 200), statuses)
        assertEquals(
            "status",
            rows[1].getValue("fault").toString().trim('"'),
        )
    }

    @Test
    fun respondStatusWithoutBodyIsEmpty() {
        proxy.respondStatus("/api/v4/forbidden", 503, body = "")
        val response = client.get(proxy.baseUrl() + "/api/v4/forbidden")
        assertEquals(503, response.statusCode())
        assertEquals(0, response.body().use { it.readBytes().size })
        assertEquals(0, upstream.requests.size)
    }

    @Test
    fun delaysMatchingRequests() {
        proxy.delay(300, "/api/v4/slow")
        val startedAt = System.nanoTime()
        client.get(proxy.baseUrl() + "/api/v4/slow").body().use { it.readBytes() }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("delay was not applied: $elapsedMs ms", elapsedMs >= 280)
    }

    @Test
    fun clearFaultsStopsInjections() {
        proxy.respondStatus("/api/v4/flaky", 500, body = "boom")
        assertEquals(500, client.get(proxy.baseUrl() + "/api/v4/flaky").statusCode())
        assertEquals(0, upstream.requests.size)

        proxy.clearFaults()
        val response = client.get(proxy.baseUrl() + "/api/v4/flaky")
        assertEquals(200, response.statusCode())
        response.body().close()
        assertEquals(1, upstream.requests.size)
    }

    @Test
    fun emitsEventsAndAwaitersSeeArtifacts() {
        val events = CopyOnWriteArrayList<ProxyEvent>()
        proxy.onEvent { events += it }

        val artifact = patternBytes(128 * 1024)
        upstream.responder = { exchange -> exchange.send(200, artifact) }

        val worker = Thread {
            client.get(proxy.baseUrl() + "/api/v4/projects/335/jobs/123/artifacts").body().use { it.readBytes() }
        }
        worker.start()
        try {
            assertTrue("artifact start was not observed", proxy.awaitArtifactStarted(1, java.time.Duration.ofSeconds(10)))
            assertTrue("artifact completion was not observed", proxy.awaitArtifactCompleted(1, java.time.Duration.ofSeconds(10)))
        } finally {
            worker.join(10_000)
        }

        client.get(proxy.baseUrl() + "/api/v4/projects/335/pipelines").body().use { it.readBytes() }
        assertTrue(proxy.awaitRequests("/api/v4/projects/335/pipelines", 1, java.time.Duration.ofSeconds(5)))
        assertNotEquals(0, events.size)

        val artifactEvent = events.first { it.artifactJobId == 123 && it.kind == ProxyEvent.Kind.RESPONSE }
        assertEquals(200, artifactEvent.status)
        assertEquals(artifact.size.toLong(), artifactEvent.bytesDown)
        assertEquals(1, proxy.stats().artifactsCompleted)
    }

    @Test
    fun appliesFaultsFromTheControlEndpoint() {
        val faults = """{"respondStatus":{"pathRegex":"/api/v4/pinned","status":503,"body":"busy"}}"""
        val applied = client.post(proxy.baseUrl() + ReflectingProxy.CONTROL_PREFIX + "/faults", faults)
        assertEquals(200, applied.statusCode())

        val response = client.get(proxy.baseUrl() + "/api/v4/pinned")
        assertEquals(503, response.statusCode())
        assertEquals("busy", response.body().use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals(0, upstream.requests.size)

        val dropped = client.post(
            proxy.baseUrl() + ReflectingProxy.CONTROL_PREFIX + "/faults",
            """{"clear":true,"dropAfter":{"pathRegex":"/artifacts","afterBytes":1024,"times":2}}""",
        )
        assertEquals(200, dropped.statusCode())

        val badJson = client.post(proxy.baseUrl() + ReflectingProxy.CONTROL_PREFIX + "/faults", "{not json")
        assertEquals(400, badJson.statusCode())
        assertTrue(badJson.body().toString(Charsets.UTF_8).contains("error"))

        // A control request never becomes a journal row.
        assertEquals(1, awaitJournal(journal, 1).size)
    }
}
