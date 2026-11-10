package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
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

    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val PING_TIMEOUT_MS = 3_000
    private const val ENSURE_TIMEOUT_MS = 11 * 60 * 1_000
    private const val HANDSHAKE_TIMEOUT_MS = 15_000L
    private const val BINARY_VERSION = 1

    private val log = CoverageLog.get(CovdClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val handshakeRef = AtomicReference<Handshake?>(null)
    private val processRef = AtomicReference<Process?>(null)

    class Unavailable(message: String) : Exception(message)

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
     */
    fun ensure(request: EnsureRequest): EnsureResponse {
        val handshake = handshake(forceRestart = false)
        return try {
            postEnsure(handshake, request)
        } catch (e: java.io.IOException) {
            // The daemon exited while we were talking to it: start it again and retry once.
            log.info("covd: connection failed (${e.message}), restarting the daemon")
            val restarted = handshake(forceRestart = true)
            postEnsure(restarted, request)
        }
    }

    /** Lists cached artifacts, or null when the daemon is unavailable. */
    fun artifacts(projectId: String): List<Artifact>? = try {
        val handshake = handshake(forceRestart = false)
        val response = request("GET", "/v1/artifacts?projectId=${encode(projectId)}", handshake, null, PING_TIMEOUT_MS)
        if (response.first != 200) null else json.decodeFromString<List<Artifact>>(response.second)
    } catch (e: Throwable) {
        log.info("covd: artifact list unavailable: ${e.message}")
        null
    }

    /** Deletes a cached artifact through the daemon, which owns the cache index. */
    fun deleteArtifact(projectId: String, commit: String, component: String): Boolean = try {
        val handshake = handshake(forceRestart = false)
        val path = "/v1/artifacts?projectId=${encode(projectId)}&commit=${encode(commit)}&component=${encode(component)}"
        request("DELETE", path, handshake, null, PING_TIMEOUT_MS).first == 200
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

    // --- daemon lifecycle --------------------------------------------------------

    private fun handshake(forceRestart: Boolean): Handshake {
        if (!forceRestart) {
            handshakeRef.get()?.let { existing ->
                if (pingMatches(existing)) return existing
                handshakeRef.set(null)
            }
        }

        val binary = binary() ?: throw Unavailable("no covd binary for ${platformId()}")
        val cacheDir = cacheRoot().apply { mkdirs() }
        val process = try {
            ProcessBuilder(binary.absolutePath, "--cache-dir", cacheDir.absolutePath)
                .redirectErrorStream(false)
                .start()
        } catch (e: Throwable) {
            throw Unavailable("cannot start covd: ${e.message}")
        }
        processRef.set(process)

        val line = readHandshakeLine(process)
            ?: throw Unavailable("covd did not print a handshake line")
        val body = try {
            json.decodeFromString(HandshakeLine.serializer(), line).covdReady
        } catch (e: Throwable) {
            null
        }
        if (body == null || body.port <= 0 || body.token.isEmpty()) {
            throw Unavailable("invalid covd handshake: $line")
        }

        val handshake = Handshake(body.port, body.token, body.instanceId)
        handshakeRef.set(handshake)
        if (!pingMatches(handshake)) {
            throw Unavailable("covd handshake points at a process that does not answer")
        }
        log.info("covd: connected on port ${handshake.port} (instance ${handshake.instanceId.take(8)})")
        return handshake
    }

    private fun readHandshakeLine(process: Process): String? {
        val reader = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readLine()
        }
        return try {
            reader.get(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            log.warn("covd: no handshake within ${HANDSHAKE_TIMEOUT_MS} ms")
            null
        }
    }

    private fun pingMatches(handshake: Handshake): Boolean {
        return try {
            val response = request("GET", "/v1/ping", handshake, null, PING_TIMEOUT_MS)
            if (response.first != 200) return false
            val ping = json.decodeFromString(PingResponse.serializer(), response.second)
            ping.protocol == 1 && ping.instanceId == handshake.instanceId
        } catch (_: Throwable) {
            false
        }
    }

    private fun postEnsure(handshake: Handshake, request: EnsureRequest): EnsureResponse {
        val body = json.encodeToString(EnsureRequest.serializer(), request)
        val response = request("POST", "/v1/ensure", handshake, body, ENSURE_TIMEOUT_MS)
        val text = response.second
        if (text.isBlank()) throw Unavailable("empty ensure response (HTTP ${response.first})")
        return json.decodeFromString(EnsureResponse.serializer(), text)
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
            connection.connectTimeout = CONNECT_TIMEOUT_MS
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
        val platform = platformId()
        val exeName = if (platform.startsWith("win")) "covd.exe" else "covd"
        val resource = "/bin/$platform/$exeName"
        val stream = CovdClient::class.java.getResourceAsStream(resource) ?: return null
        return try {
            val dir = File(cacheRoot(), "bin-covd-$BINARY_VERSION")
            dir.mkdirs()
            val target = File(dir, exeName)
            if (!target.isFile || target.length() == 0L) {
                stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                target.setExecutable(true, false)
            }
            target
        } catch (e: Throwable) {
            log.warn("covd: cannot extract bundled binary: ${e.message}")
            null
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

    private fun cacheRoot(): File = File(System.getProperty("user.home"), ".cache/coverage-plugin")

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
