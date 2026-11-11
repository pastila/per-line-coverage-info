package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Client for the `covd` local daemon.
 *
 * The daemon owns the whole coverage cycle: it resolves the pipeline in GitLab, downloads
 * the COVT shards, merges them and writes the `.cov4` cache. This client only starts it
 * (or reuses the running instance), sends `ensure`, and hands the resulting file to
 * [CoverageDataService].
 *
 * Lifecycle rules from the covd integration guide:
 *  - the daemon is shared between IDE instances; never call `/v1/shutdown`;
 *  - `connection refused` means the daemon exited after its idle timeout: start it again
 *    and retry once;
 *  - the daemon token is read from the startup handshake line, not from a state file;
 *  - the GitLab token is sent in the request body and is never stored by the client.
 */
object CovdClient {

    private const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000
    private const val DEFAULT_PING_TIMEOUT_MS = 3_000
    private const val DEFAULT_ENSURE_TIMEOUT_MS = 11 * 60 * 1_000
    private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 15_000L

    private val log = CoverageLog.get(CovdClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val handshakeRef = AtomicReference<Handshake?>(null)
    private val processRef = AtomicReference<Process?>(null)

    // Test seams. Production code never touches them; tests replace the daemon binary, the
    // cache directory, the extra command-line flags and the timeouts, then call
    // resetForTest() to drop the connection state.
    @Volatile internal var binaryOverride: File? = null
    @Volatile internal var cacheRootOverride: File? = null
    @Volatile internal var extraDaemonArgs: List<String> = emptyList()
    @Volatile internal var connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    @Volatile internal var pingTimeoutMs: Int = DEFAULT_PING_TIMEOUT_MS
    @Volatile internal var ensureTimeoutMs: Int = DEFAULT_ENSURE_TIMEOUT_MS
    @Volatile internal var handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS
    /** How many times the daemon process was started since the last [resetForTest]. */
    internal val startCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** Forgets the connection, stops the child process and restores every seam to its default. */
    internal fun resetForTest() {
        processRef.getAndSet(null)?.let { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        handshakeRef.set(null)
        CovdProcessSampler.stop()
        CovdResponseRecorder.resetForTest()
        binaryOverride = null
        cacheRootOverride = null
        extraDaemonArgs = emptyList()
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS
        pingTimeoutMs = DEFAULT_PING_TIMEOUT_MS
        ensureTimeoutMs = DEFAULT_ENSURE_TIMEOUT_MS
        handshakeTimeoutMs = DEFAULT_HANDSHAKE_TIMEOUT_MS
        startCount.set(0)
    }

    /** The current connection, or null before the first request. */
    internal fun currentHandshakeForTest(): Handshake? = handshakeRef.get()

    /** The current connection, or null before the first request. */
    fun currentConnection(): Handshake? = handshakeRef.get()

    /**
     * PID of the daemon this IDE talks to: our own child process, or the process that owns the
     * handshake port when the daemon belongs to another IDE. Null when it cannot be resolved.
     */
    fun daemonPid(): Long? = processRef.get()?.takeIf { it.isAlive }?.pid() ?: CovdProcessSampler.knownPid()

    /** The child process started by this client, or null when it reused a running daemon. */
    internal fun currentProcessForTest(): Process? = processRef.get()

    /**
     * The daemon cannot be talked to. It stays an [java.io.IOException] so callers that treat a
     * dead daemon like any other I/O failure keep working.
     */
    class Unavailable(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

    data class Handshake(val port: Int, val token: String, val instanceId: String)

    @Serializable
    data class Settings(
        val baseUrl: String,
        val projectId: String,
        val coverageBranch: String = "",
        val token: String,
        val proxy: String = "",
        val caBundlePath: String = "",
    )

    @Serializable
    data class EnsureRequest(
        val gitRoot: String,
        val component: String,
        val pathMode: String = "",
        val jobPrefixes: List<String>,
        val artifactPath: String = "",
        val gitPath: String = "",
        val settings: Settings,
        val force: Boolean = false,
        val dedupKey: String = "",
    )

    @Serializable
    data class Stats(
        val files: Int = 0,
        val lines: Int = 0,
        val covered: Int = 0,
    )

    @Serializable
    data class EnsureResponse(
        val state: String = "",
        val commit: String? = null,
        val pipelineId: Long = 0,
        val cov4Path: String? = null,
        val cached: Boolean = false,
        val replaced: Boolean = false,
        val stale: Boolean = false,
        val staleReason: String? = null,
        val baselineCommit: String? = null,
        val baselineCov4Path: String? = null,
        val baselineState: String? = null,
        val reason: String? = null,
        val retryAfterMs: Long = 0,
        val stats: Stats? = null,
        val code: String? = null,
        val cause: String? = null,
        val retryable: Boolean = false,
        val detail: String? = null,
        val hint: String? = null,
        val message: String? = null,
        val trace: Trace? = null,
    )

    /** The daemon's account of one `ensure`: why this pipeline, where the time went. */
    @Serializable
    data class Trace(
        val requestId: String = "",
        val joinedFlight: Boolean = false,
        val leaderRequestId: String? = null,
        val request: TraceRequest? = null,
        val outcome: TraceOutcome? = null,
        val timing: TraceTiming? = null,
        val gitlab: List<TraceGitlabCall> = emptyList(),
        val decisions: List<TraceDecision> = emptyList(),
        val errors: List<TraceError> = emptyList(),
    )

    /** The request as the daemon saw it. `hasToken` is a flag: the token itself is never echoed. */
    @Serializable
    data class TraceRequest(
        val projectId: String = "",
        val component: String = "",
        val gitRoot: String = "",
        val head: String = "",
        val coverageBranch: String = "",
        val jobPrefixes: List<String> = emptyList(),
        val hasToken: Boolean = false,
        val proxy: String = "",
    )

    @Serializable
    data class TraceOutcome(val state: String = "", val httpStatus: Int = 0)

    @Serializable
    data class TraceTiming(
        val gitMs: Long = 0,
        val resolveMs: Long = 0,
        val downloadMs: Long = 0,
        val parseMs: Long = 0,
        val mergeMs: Long = 0,
        val writeMs: Long = 0,
        val saveMs: Long = 0,
        val baselineMs: Long = 0,
        val totalMs: Long = 0,
        val resolveAttempts: Int = 0,
    )

    /** One GitLab API call: no `status` means no answer came back; the reason is in [errors]. */
    @Serializable
    data class TraceGitlabCall(
        val atMs: Long = 0,
        val method: String = "",
        val path: String = "",
        val status: Int? = null,
        val ms: Long = 0,
        val bytes: Long = 0,
        val attempt: Int = 0,
        val error: String? = null,
    )

    @Serializable
    data class TraceError(
        val scope: String = "",
        val code: String = "",
        val message: String = "",
        val detail: String? = null,
        val retryable: Boolean = false,
    )

    @Serializable
    data class TraceDecision(
        val scope: String = "",
        val pipelineId: Long = 0,
        val sha: String = "",
        val source: String = "",
        val code: String = "",
        val reason: String? = null,
    )

    @Serializable
    private data class HandshakeLine(val covdReady: HandshakeBody? = null)

    @Serializable
    private data class HandshakeBody(
        val port: Int = 0,
        val token: String = "",
        val instanceId: String = "",
    )

    @Serializable
    private data class PingResponse(
        val protocol: Int = 0,
        val build: String = "",
        val instanceId: String = "",
    )

    @Serializable
    data class Artifact(
        val commit: String = "",
        val component: String = "",
        val path: String? = null,
        val pipelineId: Long = 0,
        val files: Int = 0,
        val lines: Int = 0,
        val covered: Int = 0,
        val timestampMs: Long = 0,
        val lastUsedMs: Long? = null,
    )

    /** True when a bundled `covd` exists for this platform. */
    fun isAvailable(): Boolean = binary() != null

    /**
     * Runs one `ensure`. Throws [Unavailable] when the daemon cannot be started or does not
     * answer; any daemon-side problem is returned as an `error` state instead.
     *
     * The daemon is restarted and the request repeated once only when the connection failed
     * before any answer arrived (refused, reset). A read timeout means the daemon is still
     * working on a long download: restarting it would double the wait and leave a second
     * process behind, so it is reported as [Unavailable] right away.
     */
    fun ensure(request: EnsureRequest): EnsureResponse {
        val handshake = handshake(forceRestart = false)
        return try {
            postEnsure(handshake, request)
        } catch (e: Unavailable) {
            throw e
        } catch (e: java.io.IOException) {
            if (!connectionLostBeforeAnswer(e)) {
                throw Unavailable(
                    "covd did not answer the ensure request within ${ensureTimeoutMs} ms " +
                        "(${e.javaClass.simpleName}: ${e.message})",
                    e,
                )
            }
            log.info("covd: connection failed before an answer (${e.message}), restarting the daemon")
            val restarted = handshake(forceRestart = true, stale = handshake)
            try {
                postEnsure(restarted, request)
            } catch (e2: Unavailable) {
                throw e2
            } catch (e2: java.io.IOException) {
                throw Unavailable("covd did not answer after a restart (${e2.javaClass.simpleName}: ${e2.message})", e2)
            }
        }
    }

    @Serializable
    data class ImportRequest(
        val covtPath: String,
        val projectId: String,
        val component: String,
        val pathMode: String = "",
        val gitRoot: String = "",
    )

    @Serializable
    data class ImportResponse(
        val state: String = "",
        val commit: String? = null,
        val cov4Path: String? = null,
        val stats: Stats? = null,
        val code: String? = null,
        val message: String? = null,
    )

    /**
     * Imports a local `.covt`/`.covt.gz` file into the daemon cache and returns the COV4
     * path. Throws [Unavailable] when the daemon cannot be reached.
     */
    fun import(request: ImportRequest): ImportResponse {
        val handshake = handshake(forceRestart = false)
        val body = json.encodeToString(ImportRequest.serializer(), request)
        val startedNs = System.nanoTime()
        val response = try {
            request("POST", "/v1/import", handshake, body, ensureTimeoutMs)
        } catch (e: java.io.IOException) {
            if (!connectionLostBeforeAnswer(e)) {
                throw Unavailable(
                    "covd did not answer the import request within ${ensureTimeoutMs} ms " +
                        "(${e.javaClass.simpleName}: ${e.message})",
                    e,
                )
            }
            log.info("covd: connection failed before an import answer (${e.message}), restarting the daemon")
            val restarted = handshake(forceRestart = true, stale = handshake)
            try {
                request("POST", "/v1/import", restarted, body, ensureTimeoutMs)
            } catch (e2: java.io.IOException) {
                throw Unavailable("covd did not answer the import after a restart (${e2.javaClass.simpleName}: ${e2.message})", e2)
            }
        }
        return completeImport(request, response.first, response.second, elapsedMs(startedNs))
    }

    /**
     * True only when the failure happened before any answer byte arrived: a refused connection,
     * a reset or a broken pipe. A [SocketTimeoutException] is a read timeout — the daemon is
     * alive and busy — and must not restart it.
     */
    private fun connectionLostBeforeAnswer(e: java.io.IOException): Boolean = when (e) {
        is SocketTimeoutException -> false
        is ConnectException -> true
        is NoRouteToHostException -> true
        is SocketException -> true
        else -> when (val cause = e.cause) {
            is SocketTimeoutException -> false
            is SocketException -> true
            else -> false
        }
    }

    /** Lists cached artifacts, or null when the daemon is unavailable. */
    fun artifacts(projectId: String): List<Artifact>? = try {
        val handshake = handshake(forceRestart = false)
        val response = request("GET", "/v1/artifacts?projectId=${encode(projectId)}", handshake, null, pingTimeoutMs)
        if (response.first != 200) null else json.decodeFromString<List<Artifact>>(response.second)
    } catch (e: Throwable) {
        log.info("covd: artifact list unavailable: ${e.message}")
        null
    }

    /** Deletes a cached artifact through the daemon, which owns the cache index. */
    fun deleteArtifact(projectId: String, commit: String, component: String): Boolean = try {
        val handshake = handshake(forceRestart = false)
        val path = "/v1/artifacts?projectId=${encode(projectId)}&commit=${encode(commit)}&component=${encode(component)}"
        request("DELETE", path, handshake, null, pingTimeoutMs).first == 200
    } catch (e: Throwable) {
        log.warn("covd: delete failed: ${e.message}")
        false
    }

    internal fun parseHandshakeForTest(line: String): Handshake? =
        json.decodeFromString(HandshakeLine.serializer(), line).covdReady?.let {
            Handshake(it.port, it.token, it.instanceId)
        }

    internal fun parseEnsureForTest(text: String): EnsureResponse =
        json.decodeFromString(EnsureResponse.serializer(), text)

    internal fun encodeRequestForTest(request: EnsureRequest): String =
        json.encodeToString(EnsureRequest.serializer(), request)

    internal fun jsonImportForTest(text: String): ImportResponse =
        json.decodeFromString(ImportResponse.serializer(), text)

    // --- daemon lifecycle --------------------------------------------------------

    /** Serializes daemon starts: concurrent callers on a cold client must share one process. */
    private val startLock = Any()

    /**
     * Returns a live connection, starting the daemon when there is none.
     *
     * The fast path (known handshake that still answers `ping`) takes no lock. Starting is
     * serialized: a caller that waited for the lock reuses the connection another thread has
     * just established instead of spawning a process of its own. [stale] is the connection the
     * caller saw fail; a different live one means somebody else already restarted the daemon.
     */
    private fun handshake(forceRestart: Boolean, stale: Handshake? = null): Handshake {
        if (!forceRestart) {
            handshakeRef.get()?.let { existing ->
                if (pingMatches(existing)) return existing
            }
        }
        val startedNs = System.nanoTime()
        synchronized(startLock) {
            val current = handshakeRef.get()
            if (current != null && current != stale && pingMatches(current)) {
                // Another caller (this IDE or none) already has a live connection: adopt it.
                logSpawn("reuse", current, elapsedMs(startedNs))
                CovdProcessSampler.start(current.port)
                return current
            }
            handshakeRef.compareAndSet(current, null)
            return startDaemon(if (forceRestart || stale != null) "restart" else "spawn", startedNs)
        }
    }

    private fun startDaemon(how: String, startedNs: Long): Handshake {
        val binary = binary() ?: throw Unavailable("no covd binary for ${platformId()}")
        val cacheDir = cacheRoot().apply { mkdirs() }
        val process = try {
            ProcessBuilder(listOf(binary.absolutePath, "--cache-dir", cacheDir.absolutePath) + extraDaemonArgs)
                .redirectErrorStream(false)
                .start()
        } catch (e: Throwable) {
            throw Unavailable("cannot start covd: ${e.message}", e)
        }
        processRef.set(process)
        startCount.incrementAndGet()

        // stdout: the single handshake line, then drained. stderr: the last line matters,
        // covd prints {"covdStartError":{...}} there when it cannot start at all.
        val output = DaemonOutput(process)
        output.start()

        val line = output.awaitHandshake(handshakeTimeoutMs)
        if (line == null) {
            output.kill()
            processRef.compareAndSet(process, null)
            throw Unavailable("covd did not print a handshake line${output.failureDetail()}")
        }
        val body = try {
            json.decodeFromString(HandshakeLine.serializer(), line).covdReady
        } catch (e: Throwable) {
            null
        }
        if (body == null || body.port <= 0 || body.token.isEmpty()) {
            output.kill()
            processRef.compareAndSet(process, null)
            throw Unavailable("invalid covd handshake: ${redactToken(line)}${output.failureDetail()}")
        }

        val handshake = Handshake(body.port, body.token, body.instanceId)
        handshakeRef.set(handshake)
        if (!pingMatches(handshake)) {
            handshakeRef.compareAndSet(handshake, null)
            output.kill()
            processRef.compareAndSet(process, null)
            throw Unavailable("covd handshake points at a process that does not answer${output.failureDetail()}")
        }
        // A losing start prints the live daemon's line and exits: that start was a reuse, not a spawn.
        val effectiveHow = if (how == "spawn" && !process.isAlive) "reuse" else how
        logSpawn(effectiveHow, handshake, elapsedMs(startedNs))
        CovdProcessSampler.start(handshake.port)
        log.info("covd: connected on port ${handshake.port} (instance ${handshake.instanceId.take(8)})")
        return handshake
    }

    /**
     * stdout/stderr of one daemon process.
     *
     * Both pipes are read on their own daemon threads. The first stdout line is the handshake;
     * everything after it is drained so the child can never block on a full stdout buffer.
     * Only the last stderr line is kept: covd writes its start error there exactly once.
     */
    private class DaemonOutput(private val process: Process) {

        private val firstLine = AtomicReference<String?>(null)
        private val ready = CountDownLatch(1)
        private val stderrTail = AtomicReference<String?>(null)
        private val threads = ArrayList<Thread>(2)

        fun start() {
            threads += Thread({
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        firstLine.set(reader.readLine())
                        ready.countDown()
                        while (reader.readLine() != null) {
                            // Drain the remaining output; the handshake is the only line we use.
                        }
                    }
                } catch (_: java.io.IOException) {
                    // The process was killed or exited: nothing left to read.
                } finally {
                    ready.countDown()
                }
            }, "covd-stdout").apply {
                isDaemon = true
                start()
            }

            threads += Thread({
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isNotBlank()) stderrTail.set(line)
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Same as stdout: the process is gone.
                }
            }, "covd-stderr").apply {
                isDaemon = true
                start()
            }
        }

        fun awaitHandshake(timeoutMs: Long): String? {
            ready.await(timeoutMs, TimeUnit.MILLISECONDS)
            return firstLine.get()
        }

        /** The daemon's own start error, or its last stderr line, for the [Unavailable] message. */
        fun failureDetail(): String {
            val line = stderrTail.get()?.trim().orEmpty()
            if (line.isEmpty()) return ""
            val startError = parseStartError(line)
            return if (startError != null) {
                " (${startError.first}: ${startError.second})"
            } else {
                " (stderr: ${redactToken(line)})"
            }
        }

        /** Destroys the process and waits for both reader threads, so no thread outlives it. */
        fun kill() {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            threads.forEach { thread -> runCatching { thread.join(1_000) } }
        }

        /** `{"covdStartError":{"code":"LOCK","message":"..."}}` → code to message. */
        private fun parseStartError(line: String): Pair<String, String>? = try {
            Json.parseToJsonElement(line).jsonObject["covdStartError"]?.jsonObject?.let { error ->
                val code = error["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (code.isEmpty() && message.isEmpty()) null else code.ifEmpty { "START" } to message
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Masks the daemon token in a line that is about to be logged or shown. The token is a
     * secret of the running daemon; a malformed handshake must not publish it.
     */
    internal fun redactToken(line: String): String =
        TOKEN_PATTERN.replace(line) { match -> "${match.groupValues[1]}***${match.groupValues[2]}" }

    private val TOKEN_PATTERN = Regex("(\"token\"\\s*:\\s*\")[^\"]*(\")")

    private fun pingMatches(handshake: Handshake): Boolean {
        return try {
            val response = request("GET", "/v1/ping", handshake, null, pingTimeoutMs)
            if (response.first != 200) return false
            val ping = json.decodeFromString(PingResponse.serializer(), response.second)
            ping.protocol == 1 && ping.instanceId == handshake.instanceId
        } catch (_: Throwable) {
            false
        }
    }

    private fun postEnsure(handshake: Handshake, request: EnsureRequest): EnsureResponse {
        val body = json.encodeToString(EnsureRequest.serializer(), request)
        val startedNs = System.nanoTime()
        val response = request("POST", "/v1/ensure", handshake, body, ensureTimeoutMs)
        return completeEnsure(request, response.first, response.second, elapsedMs(startedNs))
    }

    /** Saves the raw answer, then decodes it and records the answer in the perf log. */
    private fun completeEnsure(request: EnsureRequest, status: Int, text: String, elapsedMs: Long): EnsureResponse {
        val seq = CovdResponseRecorder.record(
            "ensure",
            elapsedMs,
            status,
            ensureRequestSnapshot(request),
            text,
            request.settings.token,
        )
        val parsed = try {
            parseEnsure(status, text)
        } catch (e: Unavailable) {
            logCovdResponse("ensure", seq, null, null, status, elapsedMs)
            throw e
        }
        logCovdResponse("ensure", seq, parsed, parsed.trace, status, elapsedMs)
        return parsed
    }

    private fun completeImport(request: ImportRequest, status: Int, text: String, elapsedMs: Long): ImportResponse {
        val seq = CovdResponseRecorder.record(
            "import",
            elapsedMs,
            status,
            importRequestSnapshot(request),
            text,
            null,
        )
        val parsed = try {
            parseImport(status, text)
        } catch (e: Unavailable) {
            logCovdResponse("import", seq, null, null, status, elapsedMs)
            throw e
        }
        logCovdResponse("import", seq, parsed, null, status, elapsedMs)
        return parsed
    }

    /** The request as it is saved next to the answer: enough to reproduce it, never the token. */
    private fun ensureRequestSnapshot(request: EnsureRequest): JsonObject = buildJsonObject {
        put("component", request.component)
        put("projectId", request.settings.projectId)
        put("gitRoot", request.gitRoot)
        put("coverageBranch", request.settings.coverageBranch)
        put("pathMode", request.pathMode)
        put("jobPrefixes", buildJsonArray { request.jobPrefixes.forEach { add(JsonPrimitive(it)) } })
        put("baseUrl", request.settings.baseUrl)
        put("force", request.force)
    }

    private fun importRequestSnapshot(request: ImportRequest): JsonObject = buildJsonObject {
        put("covtPath", request.covtPath)
        put("projectId", request.projectId)
        put("component", request.component)
        put("pathMode", request.pathMode)
        put("gitRoot", request.gitRoot)
    }

    /**
     * `covd.response`: one line per answer with the state, the flags and the phase timings the
     * daemon reported. [parsed] is null when the body could not be decoded at all.
     */
    private fun logCovdResponse(
        kind: String,
        seq: Int,
        parsed: Any?,
        trace: Trace?,
        httpStatus: Int,
        elapsedMs: Long,
    ) {
        if (!CoveragePerfLog.isEnabled()) return
        val ensure = parsed as? EnsureResponse
        val import = parsed as? ImportResponse
        val timing = trace?.timing
        val gitlab = trace?.gitlab.orEmpty()
        CoveragePerfLog.event(
            "covd.response",
            "kind" to kind,
            "seq" to seq,
            "state" to (ensure?.state ?: import?.state ?: "-"),
            "code" to (ensure?.code ?: import?.code ?: "-"),
            "commit" to ((ensure?.commit ?: import?.commit)?.take(8) ?: "-"),
            "cached" to (ensure?.cached == true),
            "replaced" to (ensure?.replaced == true),
            "stale" to (ensure?.stale == true),
            "joinedFlight" to (trace?.joinedFlight == true),
            "requestId" to (trace?.requestId?.takeIf { it.isNotBlank() } ?: "-"),
            "httpStatus" to httpStatus,
            "elapsedMs" to elapsedMs,
            "totalMs" to (timing?.totalMs ?: -1L),
            "resolveMs" to (timing?.resolveMs ?: -1L),
            "downloadMs" to (timing?.downloadMs ?: -1L),
            "parseMs" to (timing?.parseMs ?: -1L),
            "mergeMs" to (timing?.mergeMs ?: -1L),
            "writeMs" to (timing?.writeMs ?: -1L),
            "baselineMs" to (timing?.baselineMs ?: -1L),
            "gitlabCalls" to gitlab.size,
            "gitlabBytes" to gitlab.sumOf { it.bytes },
            "gitlabErrors" to (trace?.errors?.size ?: 0),
        )
    }

    /** `covd.spawn`: how this client obtained its connection, and to which process. */
    private fun logSpawn(how: String, handshake: Handshake, startMs: Long) {
        if (!CoveragePerfLog.isEnabled()) return
        // Another IDE's daemon is not our child: resolve its PID from the handshake port once.
        val pid = daemonPid() ?: try {
            CovdProc.findPidForPort(handshake.port)
        } catch (_: Throwable) {
            null
        }
        CoveragePerfLog.event(
            "covd.spawn",
            "how" to how,
            "pid" to (pid ?: "-"),
            "port" to handshake.port,
            "instance" to handshake.instanceId.take(8),
            "startMs" to startMs,
        )
    }

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    /**
     * Decodes an `ensure` answer. Daemon-side problems come as JSON with a non-2xx status;
     * a non-2xx answer without a `state` (for example `401 {"code":"UNAUTHORIZED"}`) is
     * normalized to an `error` state so callers handle it like every other daemon error.
     * A body that is not an ensure answer at all (a proxy page, a truncated reply) means the
     * daemon cannot be talked to.
     */
    private fun parseEnsure(status: Int, text: String): EnsureResponse {
        if (text.isBlank()) throw Unavailable("empty ensure response (HTTP $status)")
        val parsed = try {
            json.decodeFromString(EnsureResponse.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw Unavailable("covd answered HTTP $status with a body that is not an ensure response")
        }
        if (parsed.state.isNotBlank()) return parsed
        if (status in 200..299) throw Unavailable("covd answered HTTP $status without a state")
        return parsed.copy(state = "error", code = parsed.code ?: "HTTP_$status")
    }

    private fun parseImport(status: Int, text: String): ImportResponse {
        if (text.isBlank()) throw Unavailable("empty import response (HTTP $status)")
        val parsed = try {
            json.decodeFromString(ImportResponse.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw Unavailable("covd answered HTTP $status with a body that is not an import response")
        }
        if (parsed.state.isNotBlank()) return parsed
        if (status in 200..299) throw Unavailable("covd answered HTTP $status without a state")
        return parsed.copy(state = "error", code = parsed.code ?: "HTTP_$status")
    }

    // --- HTTP ---------------------------------------------------------------------

    private fun request(
        method: String,
        path: String,
        handshake: Handshake,
        body: String?,
        readTimeoutMs: Int,
    ): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${handshake.port}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer ${handshake.token}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            connection.disconnect()
        }
    }

    // --- binary distribution -----------------------------------------------------

    private fun binary(): File? {
        binaryOverride?.let { return it.takeIf { file -> file.isFile } }
        val platform = platformId()
        val exeName = if (platform.startsWith("win")) "covd.exe" else "covd"
        return try {
            extractBundledBinary("/bin/$platform/$exeName", exeName, cacheRoot())
        } catch (e: Throwable) {
            log.warn("covd: cannot extract bundled binary: ${e.message}")
            null
        }
    }

    /** Extracted binaries by resource and cache root, so the 8 MB resource is read once per run. */
    private val extractedBinaries = java.util.concurrent.ConcurrentHashMap<String, Pair<File, Long>>()
    private val extractLock = Any()

    /**
     * Copies the bundled daemon binary out of the plugin jar and returns it, or null when this
     * build carries none for the platform.
     *
     * The target directory is named after the SHA-256 of the binary (`bin-covd-<16 hex>`), so a
     * plugin update that ships another covd never reuses an older extraction, and two plugin
     * versions can live side by side. The copy goes to a temporary file in the same directory and is
     * moved into place atomically: a half-written file (a crash, or a second IDE extracting at the
     * same moment) can never be taken for a complete binary. A file that exists with the wrong size
     * is replaced.
     */
    internal fun extractBundledBinary(resource: String, exeName: String, root: File): File? {
        val key = "${root.absolutePath}|$resource"
        extractedBinaries[key]?.let { (file, size) ->
            if (file.isFile && file.length() == size) return file
        }
        synchronized(extractLock) {
            extractedBinaries[key]?.let { (file, size) ->
                if (file.isFile && file.length() == size) return file
            }
            val bytes = CovdClient::class.java.getResourceAsStream(resource)?.use { it.readBytes() } ?: return null
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            val id = digest.joinToString("") { "%02x".format(it) }.take(16)
            val dir = File(root, "bin-covd-$id").apply { mkdirs() }
            val target = File(dir, exeName)
            if (!target.isFile || target.length() != bytes.size.toLong()) {
                val temp = File(dir, "$exeName.tmp-${ProcessHandle.current().pid()}-${System.nanoTime()}")
                try {
                    temp.writeBytes(bytes)
                    temp.setExecutable(true, false)
                    try {
                        java.nio.file.Files.move(
                            temp.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        java.nio.file.Files.move(
                            temp.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                } finally {
                    temp.delete()
                }
            }
            if (!target.canExecute()) target.setExecutable(true, false)
            extractedBinaries[key] = target to bytes.size.toLong()
            return target
        }
    }

    private fun platformId(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return when {
            os.contains("win") -> "win-x64"
            os.contains("mac") || os.contains("darwin") -> if (isArm(arch)) "mac-arm64" else "mac-x64"
            isArm(arch) -> "linux-arm64"
            else -> "linux-x64"
        }
    }

    private fun isArm(arch: String) = arch == "aarch64" || arch == "arm64"

    private fun cacheRoot(): File = CoveragePaths.cacheRoot(cacheRootOverride)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
