package com.github.yakov255.perlinecoverageinfo

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class GitLabProjectsClientTest {

    private var server: HttpServer? = null
    private val calls = AtomicInteger()

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun serve(responder: (call: Int) -> Pair<Int, String>): GitLabProjectsClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            val (status, body) = responder(calls.incrementAndGet())
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return GitLabProjectsClient("http://127.0.0.1:${s.address.port}", "secret-token")
    }

    private val oneProject = """[{"id":1,"name":"Raketa","path_with_namespace":"raketa/raketa","web_url":"u"}]"""

    @Test
    fun `retries a transient 5xx and returns the later answer`() {
        val client = serve { call -> if (call < 3) 504 to "timeout" else 200 to oneProject }

        val projects = client.listMemberProjects()

        assertEquals(3, calls.get())
        assertEquals("raketa/raketa", projects.single().pathWithNamespace)
    }

    @Test
    fun `does not retry a 401 and reports it`() {
        val client = serve { 401 to """{"message":"401 Unauthorized"}""" }

        try {
            client.listMemberProjects()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("401"))
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `gives up after the attempt limit with the last error`() {
        val client = serve { 503 to "busy" }

        try {
            client.listMemberProjects()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("503"))
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun `a malformed body is an error, not an empty list`() {
        val client = serve { 200 to "{not json" }

        try {
            client.listMemberProjects()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("unexpected GitLab response"))
        }
    }
}
