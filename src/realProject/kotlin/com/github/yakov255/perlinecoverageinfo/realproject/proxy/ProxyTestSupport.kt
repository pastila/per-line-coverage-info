package com.github.yakov255.perlinecoverageinfo.realproject.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Local HTTP server that plays the upstream GitLab in the proxy tests. */
internal class TestUpstream : AutoCloseable {

    data class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    )

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "test-upstream").apply { isDaemon = true }
    }

    val requests = CopyOnWriteArrayList<Recorded>()

    /** Answers every request; replaced by a test that needs a different answer. */
    @Volatile
    var responder: (HttpExchange) -> Unit = { exchange ->
        exchange.send(200, "upstream-ok".toByteArray())
    }

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.readBytes()
                requests += Recorded(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    query = exchange.requestURI.rawQuery,
                    headers = exchange.requestHeaders.mapValues { (_, values) -> values.toList() },
                    body = body,
                )
                responder(exchange)
            } finally {
                runCatching { exchange.close() }
            }
        }
        server.start()
    }

    fun url(): String = "http://127.0.0.1:${server.address.port}"

    fun count(pathRegex: String): Int {
        val regex = Regex(pathRegex)
        return requests.count { regex.containsMatchIn(it.path) }
    }

    fun sawCredentialHeaders(): List<String> =
        CREDENTIAL_NAMES.filter { name ->
            requests.any { request -> request.headers.keys.any { it.equals(name, true) } }
        }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        /**
         * The credential headers the proxy must remove. `proxy-authorization` is not sent by the
         * JDK client to our proxy (the proxy is not configured as one), so it is asserted through
         * a raw socket request instead.
         */
        val CREDENTIAL_NAMES = listOf("authorization", "private-token", "job-token", "cookie")
    }
}

/**
 * First value of [name] of a recorded request; the JDK server normalizes header names
 * (`Content-type`), so the lookup ignores case.
 */
internal fun TestUpstream.Recorded.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

internal fun HttpExchange.send(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
    for ((name, value) in headers) responseHeaders.add(name, value)
    sendResponseHeaders(status, body.size.toLong())
    responseBody.use { it.write(body) }
}

internal fun HttpExchange.sendChunked(status: Int, chunks: List<ByteArray>) {
    sendResponseHeaders(status, 0)
    responseBody.use { output ->
        for (chunk in chunks) output.write(chunk)
    }
}

/** Deterministic test payload; not random so that a failure is reproducible. */
internal fun patternBytes(size: Int, seed: Int = 7): ByteArray {
    val bytes = ByteArray(size)
    var value = seed
    for (index in bytes.indices) {
        value = value * 31 + index
        bytes[index] = (value ushr 3).toByte()
    }
    return bytes
}

/** Sends one raw HTTP/1.1 request and returns the whole response text (headers + body). */
internal fun rawRequest(port: Int, path: String, extraHeaders: Map<String, String> = emptyMap()): String {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            for ((name, value) in extraHeaders) append("$name: $value\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }
}

internal fun HttpClient.get(uri: String): HttpResponse<InputStream> =
    send(
        HttpRequest.newBuilder(URI.create(uri)).GET().timeout(Duration.ofSeconds(30)).build(),
        HttpResponse.BodyHandlers.ofInputStream(),
    )

internal fun HttpClient.post(uri: String, body: String, contentType: String = "application/json"): HttpResponse<ByteArray> =
    send(
        HttpRequest.newBuilder(URI.create(uri))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )

internal fun newClient(connectTimeout: Duration = Duration.ofSeconds(5)): HttpClient =
    HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(connectTimeout)
        .build()

internal val testJson = Json { ignoreUnknownKeys = true }

/** Parses `proxy.jsonl`; each non-blank line must be one JSON object. */
internal fun readJournal(file: File): List<JsonObject> {
    if (!file.isFile) return emptyList()
    return file.readLines().filter { it.isNotBlank() }.map { testJson.parseToJsonElement(it).jsonObject }
}

/**
 * Reads the journal once it holds [count] rows. A row is flushed at the end of its request, so a
 * client that already received the last body byte may still be a moment ahead of the writer.
 */
internal fun awaitJournal(file: File, count: Int, timeoutMs: Long = 5_000): List<JsonObject> {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (true) {
        val rows = readJournal(file)
        if (rows.size >= count) return rows
        if (System.nanoTime() > deadline) return rows
        Thread.sleep(10)
    }
}

internal fun JsonObject.string(name: String): String = getValue(name).toString().trim('"')

internal fun JsonObject.intOrZero(name: String): Int = getValue(name).toString().toInt()
