package com.github.yakov255.perlinecoverageinfo.realproject.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reflecting HTTP proxy in front of a GitLab instance, plus fault injection.
 *
 * The IDE, `covd` and the scenarios talk to `http://127.0.0.1:<port>` exactly as they would talk to
 * `https://gitlab.raketa.in`. Every request is forwarded unchanged except for the credential
 * headers, which are dropped before the request leaves this process:
 * `Authorization`, `PRIVATE-TOKEN`, `JOB-TOKEN`, `Cookie` and `Proxy-Authorization`. The proxy is
 * therefore safe to use with the token `dummy` and a real repository: no credential can reach the
 * upstream server.
 *
 * Behaviour:
 *
 * * One upstream request per proxied request (no retries, no extra probes). The control endpoints
 *   under [CONTROL_PREFIX] never reach the upstream server.
 * * Response bodies are streamed: an artifact is copied in [BUFFER_SIZE] chunks, never buffered
 *   whole. A `Content-Length` from the upstream is preserved; a chunked upstream answer stays
 *   chunked. Request bodies are streamed in the same way and their size is journaled as
 *   `bytesUp`.
 * * `Location`, `Content-Location` and `Link` header values are rewritten so that an absolute
 *   upstream URL points at this proxy instead (`https://gitlab.raketa.in/x` -> `http://127.0.0.1:P/x`).
 *   Relative values are passed through untouched.
 * * Redirects are **not** followed ([HttpClient.Redirect.NEVER]): a `3xx` answer reaches the
 *   client with its (rewritten) `Location`, so the client keeps its own redirect policy and the
 *   journal keeps one row per upstream request.
 * * Upstream calls use HTTP/1.1 over `java.net.http.HttpClient` with a connect timeout
 *   ([connectTimeout]) and a whole-response timeout ([responseTimeout]); the latter is generous
 *   because a throttled artifact download legitimately takes minutes.
 * * HTTP/1.1 request headers that describe this hop (`Host`, `Content-Length`,
 *   `Transfer-Encoding`, `Connection`, `Keep-Alive`, `Proxy-Connection`, `Upgrade`, `Expect`,
 *   `TE`) are dropped and rebuilt by the HTTP client.
 *
 * Journal: when [journal] is not `null`, one JSON line per proxied request is appended in the
 * format of "Proxy journal row" in `docs/real-project-testing.md`:
 *
 * ```json
 * {"t":1760000000000,"seq":1,"method":"GET","path":"/api/v4/projects/335/pipelines",
 *  "query":"per_page=1","status":200,"bytesDown":512,"bytesUp":0,"ms":42,
 *  "fault":null,"artifactJobId":null,"authSeen":false}
 * ```
 *
 * `authSeen` records only that a credential header arrived; its value is never stored, read or
 * logged. `fault` is `null`, `"drop"`, `"status"` or `"throttle"`. `artifactJobId` is the `<id>` of
 * a `.../jobs/<id>/artifacts` path, `null` for every other path. `query` is the empty string when
 * the request carries no query string; `status` is `0` when no response could be produced (for
 * example a client that disconnected before the upstream answered).
 *
 * ## Fault injection
 *
 * All fault methods are thread-safe and take effect on the next request; [clearFaults] removes
 * every fault. Faults apply to proxied requests only (not to the control endpoints).
 *
 * | Method | Effect |
 * |---|---|
 * | [throttle] | Paces the response body to `bytesPerSec` for paths matching `pathRegex` |
 * | [dropAfter] | Cuts the connection after `afterBytes` response bytes, `times` times |
 * | [respondStatus] | Answers `status`+`body` without contacting upstream, from matching request `fromRequestN` on, `times` times |
 * | [delay] | Sleeps before an upstream request for paths matching `pathRegex` |
 *
 * A cut ([dropAfter]) writes the declared `Content-Length` only partially and then closes the
 * connection, so the client observes a truncated body (an `IOException` from its HTTP client)
 * exactly like a real network cut. The upstream download is not interrupted inside `covd`: the
 * proxy is the one that stops copying.
 *
 * ## Events and waiting
 *
 * [onEvent] receives [ProxyEvent]s (`REQUEST`, `RESPONSE`, `DROP`). [awaitArtifactStarted],
 * [awaitArtifactCompleted] and [awaitRequests] block until the counter reaches `count` and return
 * `false` on timeout — handy for scenarios that must not poll.
 *
 * ## Control endpoints
 *
 * `GET /__proxy/stats` returns
 * `{"requests":N,"bytesDown":N,"bytesUp":N,"artifactsStarted":N,"artifactsCompleted":N,"byStatus":{"200":N},"upstream":"..."}`.
 *
 * `POST /__proxy/faults` applies a JSON document (every member is optional; `clear` first resets
 * the faults). It answers `200` with the active faults:
 *
 * ```json
 * {"clear":true,
 *  "throttle":{"bytesPerSec":1000000,"pathRegex":"/artifacts"},
 *  "dropAfter":{"pathRegex":"/artifacts","afterBytes":65536,"times":1},
 *  "respondStatus":{"pathRegex":"/pipelines","status":401,"fromRequestN":2,"times":1,
 *                   "body":"{\"message\":\"401 Unauthorized\"}",
 *                   "contentType":"application/json"},
 *  "delay":{"ms":500,"pathRegex":".*"}}
 * ```
 *
 * ## Manual use
 *
 * `scripts/real-project-proxy.sh --port 41000 --journal /tmp/proxy.jsonl` runs [main] through a
 * Gradle `JavaExec` task; see the script for the flags (`--port`, `--upstream`, `--journal`).
 */
class ReflectingProxy(
    upstream: String = DEFAULT_UPSTREAM,
    journal: File? = null,
    listenPort: Int = 0,
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val responseTimeout: Duration = Duration.ofSeconds(300),
) : AutoCloseable {

    private val upstreamBase: String = upstream.trimEnd('/')
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(connectTimeout)
        .build()

    private val executor = Executors.newCachedThreadPool(ProxyThreadFactory)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", listenPort), 64)

    /** Port the server actually listens on (useful when [listenPort] was 0). */
    val port: Int = server.address.port

    private val journalWriter: BufferedWriter? = journal?.let { file ->
        file.absoluteFile.parentFile?.mkdirs()
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8))
    }
    private val journalLock = Any()

    private val requestSeq = AtomicInteger()
    private val requestCount = AtomicInteger()
    private val bytesDownTotal = AtomicLong()
    private val bytesUpTotal = AtomicLong()
    private val artifactsStartedCount = AtomicInteger()
    private val artifactsCompletedCount = AtomicInteger()
    private val byStatus = ConcurrentHashMap<Int, AtomicInteger>()

    private val stateLock = ReentrantLock()
    private val stateChanged = stateLock.newCondition()

    /** Immutable fault snapshot, replaced on every mutation (see [Faults]). */
    private var faults: Faults = Faults()

    /** Every proxied request path, for [awaitRequests]. Guarded by [stateLock]. */
    private val requestPaths = ArrayList<String>()

    private val listeners = CopyOnWriteArrayList<(ProxyEvent) -> Unit>()

    init {
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    /** Base URL to point a client at, e.g. `http://127.0.0.1:41000`. */
    fun baseUrl(): String = "http://127.0.0.1:$port"

    /**
     * Limits response bodies matching [pathRegex] to roughly [bytesPerSec] bytes per second.
     * The default [pathRegex] matches every path, so artifacts and API answers are both paced.
     */
    fun throttle(bytesPerSec: Int, pathRegex: String = ".*") {
        require(bytesPerSec > 0) { "bytesPerSec must be positive" }
        val spec = ThrottleSpec(Regex(pathRegex), bytesPerSec)
        mutateFaults { it.copy(throttles = it.throttles + spec) }
    }

    /**
     * Cuts the connection of a matching response after [afterBytes] body bytes, at most [times]
     * times; later matching requests are served normally.
     */
    fun dropAfter(pathRegex: String, afterBytes: Long, times: Int = 1) {
        require(afterBytes >= 0) { "afterBytes must not be negative" }
        require(times > 0) { "times must be positive" }
        val spec = DropSpec(Regex(pathRegex), afterBytes, times)
        mutateFaults { it.copy(drops = it.drops + spec) }
    }

    /**
     * Answers [status] with [body] instead of forwarding, for matching requests number
     * [fromRequestN] and later, at most [times] times ([Int.MAX_VALUE] = unlimited). Counting is
     * per call of this method, so `fromRequestN = 2` answers only the second matching request.
     */
    fun respondStatus(
        pathRegex: String,
        status: Int,
        fromRequestN: Int = 1,
        times: Int = Int.MAX_VALUE,
        body: String = "",
        contentType: String = "application/json",
    ) {
        require(fromRequestN >= 1) { "fromRequestN must be at least 1" }
        require(times > 0) { "times must be positive" }
        val spec = StatusSpec(
            pattern = Regex(pathRegex),
            status = status,
            fromRequestN = fromRequestN,
            times = times,
            body = body.toByteArray(StandardCharsets.UTF_8),
            contentType = contentType,
        )
        mutateFaults { it.copy(statuses = it.statuses + spec) }
    }

    /** Sleeps [ms] before answering matching requests (before the upstream request). */
    fun delay(ms: Long, pathRegex: String = ".*") {
        require(ms >= 0) { "ms must not be negative" }
        val spec = DelaySpec(Regex(pathRegex), ms)
        mutateFaults { it.copy(delays = it.delays + spec) }
    }

    /** Removes every fault. */
    fun clearFaults() {
        mutateFaults { Faults() }
    }

    /** Registers a listener; [removeListener] unregisters it. */
    fun onEvent(listener: (ProxyEvent) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (ProxyEvent) -> Unit) {
        listeners -= listener
    }

    /** Waits until at least [count] artifact downloads (paths `.../jobs/<id>/artifacts`) started. */
    fun awaitArtifactStarted(count: Int = 1, timeout: Duration = Duration.ofSeconds(60)): Boolean =
        awaitCounter(timeout) { artifactsStartedCount.get() >= count }

    /** Waits until at least [count] artifact downloads completed with a complete body and a 2xx status. */
    fun awaitArtifactCompleted(count: Int = 1, timeout: Duration = Duration.ofSeconds(60)): Boolean =
        awaitCounter(timeout) { artifactsCompletedCount.get() >= count }

    /** Waits until at least [count] requests with a path matching [pathRegex] started. */
    fun awaitRequests(
        pathRegex: String,
        count: Int = 1,
        timeout: Duration = Duration.ofSeconds(60),
    ): Boolean {
        val regex = Regex(pathRegex)
        return awaitCounter(timeout) { requestPaths.count { regex.containsMatchIn(it) } >= count }
    }

    /** Snapshot of the counters. */
    fun stats(): ProxyStats = ProxyStats(
        requests = requestCount.get(),
        bytesDown = bytesDownTotal.get(),
        bytesUp = bytesUpTotal.get(),
        artifactsStarted = artifactsStartedCount.get(),
        artifactsCompleted = artifactsCompletedCount.get(),
        byStatus = byStatus.entries.associate { (status, count) -> status to count.get() }.toSortedMap(),
    )

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        synchronized(journalLock) {
            runCatching { journalWriter?.flush() }
            runCatching { journalWriter?.close() }
        }
    }

    // ---------------------------------------------------------------- request handling

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath?.ifEmpty { "/" } ?: "/"
        if (path == CONTROL_PREFIX || path.startsWith("$CONTROL_PREFIX/")) {
            handleControl(exchange, path)
            return
        }

        val startedAt = System.currentTimeMillis()
        val seq = requestSeq.incrementAndGet()
        val method = exchange.requestMethod.uppercase()
        val query = exchange.requestURI.rawQuery ?: ""
        val authSeen = CREDENTIAL_HEADERS.any { exchange.requestHeaders.getFirst(it) != null }
        val jobId = artifactJobId(path)
        requestCount.incrementAndGet()
        recordRequestStarted(path, jobId)
        emit(ProxyEvent(ProxyEvent.Kind.REQUEST, seq, method, path, null, 0, 0, null, jobId))

        val snapshot = faultsSnapshot()
        val outcome = Outcome()
        try {
            val injected = snapshot.statuses.firstOrNull { it.matches(path) && it.claim() }
            if (injected != null) {
                outcome.fault = FAULT_STATUS
                inject(exchange, injected, outcome)
            } else {
                val pause = snapshot.delays.firstOrNull { it.matches(path) }
                if (pause != null && pause.ms > 0) TimeUnit.MILLISECONDS.sleep(pause.ms)
                forward(exchange, path, query, method, snapshot, outcome)
            }
        } catch (e: IOException) {
            // A client that went away, an unreachable upstream or an injected cut: nothing to do
            // beyond the journal row written below.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { exchange.close() }
            val ms = System.currentTimeMillis() - startedAt
            recordCompleted(seq, method, path, query, startedAt, ms, outcome, jobId, authSeen)
        }
    }

    /** Copies the request upstream and streams the answer back to the client. */
    private fun forward(
        exchange: HttpExchange,
        path: String,
        query: String,
        method: String,
        snapshot: Faults,
        outcome: Outcome,
    ) {
        val target = URI.create(upstreamBase + path + if (query.isEmpty()) "" else "?$query")

        // A small declared body is buffered so that the upstream keeps its Content-Length; a
        // streamed body (chunked client upload, or larger than the limit) is forwarded chunked.
        val declaredRequestLength = exchange.requestHeaders.getFirst("content-length")?.toLongOrNull()
        val hasRequestBody = method in BODY_METHODS && (
            (declaredRequestLength ?: 0L) > 0L ||
                exchange.requestHeaders.getFirst("transfer-encoding") != null
            )
        val bufferedRequest = if (
            hasRequestBody && declaredRequestLength != null && declaredRequestLength in 1L..REQUEST_BUFFER_LIMIT
        ) {
            exchange.requestBody.readNBytes(declaredRequestLength.toInt())
        } else {
            null
        }
        val streamedBody = if (hasRequestBody && bufferedRequest == null) {
            CountingInputStream(exchange.requestBody)
        } else {
            null
        }

        val builder = HttpRequest.newBuilder(target).timeout(responseTimeout)
        exchange.requestHeaders.forEach { (name, values) ->
            if (name.lowercase() in SKIPPED_REQUEST_HEADERS) return@forEach
            for (value in values) builder.header(name, value)
        }
        val request = when {
            bufferedRequest != null ->
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bufferedRequest)).build()

            streamedBody != null ->
                builder.method(method, HttpRequest.BodyPublishers.ofInputStream { streamedBody }).build()

            else -> builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        }

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            outcome.bytesUp = requestBytesUp(bufferedRequest, streamedBody)
            answerGatewayError(exchange, outcome)
            return
        }
        outcome.bytesUp = requestBytesUp(bufferedRequest, streamedBody)
        outcome.status = response.statusCode()

        for ((name, values) in response.headers().map()) {
            val lower = name.lowercase()
            if (lower in SKIPPED_RESPONSE_HEADERS) continue
            val rewrite = lower == "location" || lower == "content-location" || lower == "link"
            for (value in values) {
                exchange.responseHeaders.add(name, if (rewrite) rewriteUpstreamUrls(value) else value)
            }
        }

        val bodyless = method == "HEAD" || outcome.status == 204 || outcome.status == 304
        val declaredLength = response.headers().firstValue("content-length").orElse(null)?.toLongOrNull() ?: -1L
        if (bodyless || declaredLength == 0L) {
            runCatching { response.body().close() }
            exchange.sendResponseHeaders(outcome.status, -1)
            return
        }

        val drop = snapshot.drops.firstOrNull { it.matches(path) && it.claim() }
        val throttle = snapshot.throttles.firstOrNull { it.matches(path) }

        // A positive length keeps the upstream's Content-Length; 0 makes the server use chunked.
        exchange.sendResponseHeaders(outcome.status, if (declaredLength > 0L) declaredLength else 0L)

        val pacer = throttle?.newPacer()
        val buffer = ByteArray(BUFFER_SIZE)
        val limit = drop?.afterBytes ?: Long.MAX_VALUE
        var written = 0L
        val output = exchange.responseBody
        response.body().use { input ->
            while (true) {
                val remaining = limit - written
                if (remaining <= 0L) break
                val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                val read = input.read(buffer, 0, toRead)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                outcome.bytesDown = written
                pacer?.pauseAfter(written)
            }
        }
        outcome.bytesUp = requestBytesUp(bufferedRequest, streamedBody)

        if (drop != null && written >= drop.afterBytes) {
            // The declared length is not satisfied: flushing and closing the exchange makes the
            // platform close the connection, so the client sees a truncated body.
            outcome.fault = FAULT_DROP
            outcome.dropped = true
            runCatching { output.flush() }
            return
        }
        if (throttle != null && written > 0L) outcome.fault = FAULT_THROTTLE
        runCatching { output.flush() }
    }

    private fun inject(exchange: HttpExchange, spec: StatusSpec, outcome: Outcome) {
        outcome.status = spec.status
        if (spec.contentType.isNotEmpty()) exchange.responseHeaders.add("Content-Type", spec.contentType)
        if (spec.body.isEmpty() || spec.status == 204 || spec.status == 304) {
            exchange.sendResponseHeaders(spec.status, -1)
            return
        }
        exchange.sendResponseHeaders(spec.status, spec.body.size.toLong())
        exchange.responseBody.use { it.write(spec.body) }
        outcome.bytesDown = spec.body.size.toLong()
    }

    private fun requestBytesUp(buffered: ByteArray?, streamed: CountingInputStream?): Long =
        (buffered?.size?.toLong() ?: 0L) + (streamed?.count ?: 0L)

    private fun answerGatewayError(exchange: HttpExchange, outcome: Outcome) {
        val body = """{"message":"reflecting proxy: upstream unavailable"}""".toByteArray(StandardCharsets.UTF_8)
        outcome.status = 502
        runCatching {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(502, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            outcome.bytesDown = body.size.toLong()
        }
    }

    // ---------------------------------------------------------------- journal, stats, events

    private fun recordRequestStarted(path: String, jobId: Int?) {
        stateLock.withLock {
            requestPaths += path
            if (jobId != null) artifactsStartedCount.incrementAndGet()
            stateChanged.signalAll()
        }
    }

    private fun recordCompleted(
        seq: Int,
        method: String,
        path: String,
        query: String,
        startedAt: Long,
        ms: Long,
        outcome: Outcome,
        jobId: Int?,
        authSeen: Boolean,
    ) {
        if (outcome.status > 0) byStatus.computeIfAbsent(outcome.status) { AtomicInteger() }.incrementAndGet()
        if (outcome.bytesDown > 0L) bytesDownTotal.addAndGet(outcome.bytesDown)
        if (outcome.bytesUp > 0L) bytesUpTotal.addAndGet(outcome.bytesUp)

        val row = JournalRow(
            t = startedAt,
            seq = seq,
            method = method,
            path = path,
            query = query,
            status = if (outcome.status > 0) outcome.status else 0,
            bytesDown = outcome.bytesDown,
            bytesUp = outcome.bytesUp,
            ms = ms,
            fault = outcome.fault,
            artifactJobId = jobId,
            authSeen = authSeen,
        )
        JOURNAL_JSON.encodeToString(JournalRow.serializer(), row).let { line ->
            synchronized(journalLock) {
                val writer = journalWriter ?: return@synchronized
                runCatching {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                }
            }
        }

        if (jobId != null && !outcome.dropped && outcome.status in 200..299) {
            stateLock.withLock {
                artifactsCompletedCount.incrementAndGet()
                stateChanged.signalAll()
            }
        }
        val kind = if (outcome.dropped) ProxyEvent.Kind.DROP else ProxyEvent.Kind.RESPONSE
        emit(ProxyEvent(kind, seq, method, path, outcome.status, outcome.bytesDown, ms, outcome.fault, jobId))
    }

    private fun emit(event: ProxyEvent) {
        for (listener in listeners) {
            runCatching { listener(event) }
        }
    }

    private fun awaitCounter(timeout: Duration, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        stateLock.withLock {
            while (true) {
                if (condition()) return true
                val left = deadline - System.nanoTime()
                if (left <= 0L) return false
                stateChanged.awaitNanos(left)
            }
        }
    }

    private fun faultsSnapshot(): Faults = stateLock.withLock { faults }

    private fun mutateFaults(change: (Faults) -> Faults) {
        stateLock.withLock { faults = change(faults) }
    }

    // ---------------------------------------------------------------- control endpoints

    private fun handleControl(exchange: HttpExchange, path: String) {
        try {
            val method = exchange.requestMethod.uppercase()
            when {
                path == "$CONTROL_PREFIX/stats" && method == "GET" -> respondJson(exchange, 200, statsJson())

                path == "$CONTROL_PREFIX/faults" && method == "POST" -> {
                    val body = exchange.requestBody.readNBytes(CONTROL_BODY_LIMIT).toString(StandardCharsets.UTF_8)
                    val error = runCatching { applyFaultsJson(body) }.exceptionOrNull()
                    if (error != null) {
                        respondJson(exchange, 400, """{"error":${quote(error.message ?: error.javaClass.simpleName)}}""")
                    } else {
                        respondJson(exchange, 200, faultsJson())
                    }
                }

                else -> respondJson(exchange, 404, """{"error":"unknown control endpoint"}""")
            }
        } catch (e: IOException) {
            // client went away
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun respondJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun statsJson(): String {
        val stats = stats()
        return buildJsonObject {
            put("requests", stats.requests)
            put("bytesDown", stats.bytesDown)
            put("bytesUp", stats.bytesUp)
            put("artifactsStarted", stats.artifactsStarted)
            put("artifactsCompleted", stats.artifactsCompleted)
            putJsonObject("byStatus") {
                for ((status, count) in stats.byStatus) put(status.toString(), count)
            }
            put("upstream", upstreamBase)
            put("port", port)
        }.toString()
    }

    private fun faultsJson(): String {
        val snapshot = faultsSnapshot()
        return buildJsonObject {
            put("throttles", snapshot.throttles.size)
            put("drops", snapshot.drops.size)
            put("statuses", snapshot.statuses.size)
            put("delays", snapshot.delays.size)
            putJsonObject("throttle") {
                snapshot.throttles.lastOrNull()?.let {
                    put("pathRegex", it.pattern.pattern)
                    put("bytesPerSec", it.bytesPerSec)
                }
            }
            putJsonObject("dropAfter") {
                snapshot.drops.lastOrNull()?.let {
                    put("pathRegex", it.pattern.pattern)
                    put("afterBytes", it.afterBytes)
                    put("times", it.times)
                }
            }
            putJsonObject("respondStatus") {
                snapshot.statuses.lastOrNull()?.let {
                    put("pathRegex", it.pattern.pattern)
                    put("status", it.status)
                    put("fromRequestN", it.fromRequestN)
                    put("times", it.times)
                }
            }
            putJsonObject("delay") {
                snapshot.delays.lastOrNull()?.let {
                    put("pathRegex", it.pattern.pattern)
                    put("ms", it.ms)
                }
            }
        }.toString()
    }

    /** Applies the control-endpoint fault document; throws [IllegalArgumentException] on bad input. */
    internal fun applyFaultsJson(json: String) {
        val root = runCatching { Json.parseToJsonElement(json) }
            .getOrElse { throw IllegalArgumentException("invalid JSON: ${it.message}") }
                as? JsonObject ?: throw IllegalArgumentException("fault document must be a JSON object")

        if (root["clear"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true) clearFaults()

        root["throttle"]?.let { element ->
            val spec = element.jsonObject
            throttle(
                bytesPerSec = spec.requireInt("bytesPerSec"),
                pathRegex = spec.optionalString("pathRegex") ?: ".*",
            )
        }
        root["dropAfter"]?.let { element ->
            val spec = element.jsonObject
            dropAfter(
                pathRegex = spec.requireString("pathRegex"),
                afterBytes = spec.requireLong("afterBytes"),
                times = spec.optionalInt("times") ?: 1,
            )
        }
        root["respondStatus"]?.let { element ->
            val spec = element.jsonObject
            respondStatus(
                pathRegex = spec.requireString("pathRegex"),
                status = spec.requireInt("status"),
                fromRequestN = spec.optionalInt("fromRequestN") ?: 1,
                times = spec.optionalInt("times") ?: Int.MAX_VALUE,
                body = spec.optionalString("body") ?: "",
                contentType = spec.optionalString("contentType") ?: "application/json",
            )
        }
        root["delay"]?.let { element ->
            val spec = element.jsonObject
            delay(
                ms = spec.requireLong("ms"),
                pathRegex = spec.optionalString("pathRegex") ?: ".*",
            )
        }
    }

    private fun JsonObject.requireInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("'$name' must be an integer")

    private fun JsonObject.optionalInt(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.requireLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("'$name' must be an integer")

    private fun JsonObject.requireString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("'$name' must be a string")

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    // ---------------------------------------------------------------- helpers

    private fun rewriteUpstreamUrls(value: String): String {
        if (!value.contains(upstreamBase)) return value
        val upstreamHost = runCatching { URI.create(upstreamBase).host }.getOrNull()
        var rewritten = value.replace(upstreamBase, baseUrl())
        // An upstream without an explicit port may also appear with the default port spelled out.
        if (upstreamHost != null && upstreamHost != "127.0.0.1" && upstreamHost != "localhost") {
            if (upstreamBase.startsWith("https://") && !upstreamBase.removePrefix("https://").contains(':')) {
                rewritten = rewritten.replace("https://$upstreamHost:443", baseUrl())
            }
            if (upstreamBase.startsWith("http://") && !upstreamBase.removePrefix("http://").contains(':')) {
                rewritten = rewritten.replace("http://$upstreamHost:80", baseUrl())
            }
        }
        return rewritten
    }

    // ---------------------------------------------------------------- nested types

    /** Outcome of one proxied request, filled in as the answer is produced. */
    private class Outcome {
        var status: Int = 0
        var bytesDown: Long = 0
        var bytesUp: Long = 0
        var fault: String? = null
        var dropped: Boolean = false
    }

    private class CountingInputStream(delegate: InputStream) : InputStream() {
        private val inner = delegate
        var count: Long = 0
            private set

        override fun read(): Int {
            val value = inner.read()
            if (value >= 0) count++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = inner.read(b, off, len)
            if (read > 0) count += read
            return read
        }

        override fun close() = inner.close()
    }

    /** Emits `bytesPerSec` overall for one response, measured from the first byte written. */
    private class Pacer(private val bytesPerSec: Int) {
        private val startNs = System.nanoTime()

        fun pauseAfter(written: Long) {
            val targetNs = written * 1_000_000_000L / bytesPerSec
            val elapsedNs = System.nanoTime() - startNs
            if (elapsedNs < targetNs) TimeUnit.NANOSECONDS.sleep(targetNs - elapsedNs)
        }
    }

    /** Throttle fault. Immutable; one pacer per matching request. */
    private class ThrottleSpec(val pattern: Regex, val bytesPerSec: Int) {
        fun matches(path: String): Boolean = pattern.containsMatchIn(path)
        fun newPacer(): Pacer = Pacer(bytesPerSec)
    }

    private class DropSpec(val pattern: Regex, val afterBytes: Long, val times: Int) {
        private val seen = AtomicInteger()

        fun matches(path: String): Boolean = pattern.containsMatchIn(path)

        fun claim(): Boolean {
            val n = seen.incrementAndGet()
            return times == Int.MAX_VALUE || n <= times
        }
    }

    private class StatusSpec(
        val pattern: Regex,
        val status: Int,
        val fromRequestN: Int,
        val times: Int,
        val body: ByteArray,
        val contentType: String,
    ) {
        private val seen = AtomicInteger()

        fun matches(path: String): Boolean = pattern.containsMatchIn(path)

        fun claim(): Boolean {
            val n = seen.incrementAndGet()
            if (n < fromRequestN) return false
            return times == Int.MAX_VALUE || n < fromRequestN + times
        }
    }

    private class DelaySpec(val pattern: Regex, val ms: Long) {
        fun matches(path: String): Boolean = pattern.containsMatchIn(path)
    }

    /** Immutable set of active faults. */
    private data class Faults(
        val throttles: List<ThrottleSpec> = emptyList(),
        val drops: List<DropSpec> = emptyList(),
        val statuses: List<StatusSpec> = emptyList(),
        val delays: List<DelaySpec> = emptyList(),
    )

    companion object {
        const val DEFAULT_UPSTREAM = "https://gitlab.raketa.in"
        const val CONTROL_PREFIX = "/__proxy"
        const val FAULT_DROP = "drop"
        const val FAULT_STATUS = "status"
        const val FAULT_THROTTLE = "throttle"

        /** Copy chunk for streaming; also the journal granularity of a throttled response. */
        const val BUFFER_SIZE = 64 * 1024

        private const val CONTROL_BODY_LIMIT = 64 * 1024

        /** Request bodies up to this size keep an upstream `Content-Length` instead of going chunked. */
        private const val REQUEST_BUFFER_LIMIT = 4L * 1024 * 1024

        private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")

        private val CREDENTIAL_HEADERS = listOf(
            "authorization",
            "private-token",
            "job-token",
            "cookie",
            "proxy-authorization",
        )

        private val SKIPPED_REQUEST_HEADERS =
            (CREDENTIAL_HEADERS + listOf(
                "host",
                "content-length",
                "transfer-encoding",
                "connection",
                "keep-alive",
                "proxy-connection",
                "upgrade",
                "expect",
                "te",
            )).toSet()

        private val SKIPPED_RESPONSE_HEADERS = setOf(
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-connection",
            "te",
            "trailer",
            "upgrade",
        )

        private val ARTIFACT_PATH = Regex("""/jobs/(\d+)/artifacts""")

        internal val JOURNAL_JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }

        /** `<id>` of `.../jobs/<id>/artifacts`, `null` for every other path. */
        fun artifactJobId(path: String): Int? =
            ARTIFACT_PATH.find(path)?.groupValues?.get(1)?.toIntOrNull()

        private val ProxyThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "reflecting-proxy-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
        private val threadCounter = AtomicInteger()

        private fun quote(value: String): String =
            buildString {
                append('"')
                for (char in value) {
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                    }
                }
                append('"')
            }
    }
}

/** One row of `proxy.jsonl`, see "Proxy journal row" in `docs/real-project-testing.md`. */
@Serializable
internal data class JournalRow(
    val t: Long,
    val seq: Int,
    val method: String,
    val path: String,
    val query: String,
    val status: Int,
    val bytesDown: Long,
    val bytesUp: Long,
    val ms: Long,
    val fault: String?,
    val artifactJobId: Int?,
    val authSeen: Boolean,
)

/** Counters returned by [ReflectingProxy.stats]. */
data class ProxyStats(
    val requests: Int,
    val bytesDown: Long,
    val bytesUp: Long,
    val artifactsStarted: Int,
    val artifactsCompleted: Int,
    val byStatus: Map<Int, Int>,
)

/** Event emitted for every proxied request. */
data class ProxyEvent(
    val kind: Kind,
    val seq: Int,
    val method: String,
    val path: String,
    val status: Int?,
    val bytesDown: Long,
    val ms: Long,
    val fault: String?,
    val artifactJobId: Int?,
) {
    enum class Kind { REQUEST, RESPONSE, DROP }
}

/** Command line entry point used by `scripts/real-project-proxy.sh`. */
fun main(args: Array<String>) {
    var port = 0
    var upstream = ReflectingProxy.DEFAULT_UPSTREAM
    var journal: File? = null
    var index = 0
    while (index < args.size) {
        val name = args[index]
        when (name) {
            "--port" -> port = requireValue(args, ++index, name).toIntOrNull()
                ?: fail("--port needs an integer")

            "--upstream" -> upstream = requireValue(args, ++index, name)
            "--journal" -> journal = File(requireValue(args, ++index, name))
            "--help", "-h" -> {
                println(USAGE)
                return
            }

            else -> {
                System.err.println("unknown argument: $name")
                println(USAGE)
                return
            }
        }
        index++
    }

    val proxy = ReflectingProxy(upstream, journal, port)
    val stop = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { stop.countDown() })
    println("reflecting proxy ${proxy.baseUrl()} -> ${upstream.trimEnd('/')}")
    journal?.let { println("journal: ${it.absolutePath}") }
    stop.await()
    proxy.close()
}

private const val USAGE =
    "usage: real-project-proxy [--port N] [--upstream URL] [--journal FILE]"

private fun requireValue(args: Array<String>, index: Int, name: String): String =
    args.getOrNull(index) ?: fail("$name needs a value")

private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
