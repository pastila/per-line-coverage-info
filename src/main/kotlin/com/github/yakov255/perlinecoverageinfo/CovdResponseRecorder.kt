package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves the daemon's `ensure`/`import` answers to `-Dcoverage.covdResponses=<dir>`.
 *
 * The daemon keeps no trace after it answers, so a real-project run needs the raw bodies to
 * explain a slow load or a failed one. Nothing is saved unless the property (or the test seam
 * [dirOverride]) is set, and a write error only goes to the log: diagnostics never break a load.
 *
 * The GitLab token is never written: the request snapshot carries only the fields a reader
 * needs, and the token is additionally scrubbed from the finished text as a second barrier.
 */
object CovdResponseRecorder {

    private val log = CoverageLog.get(CovdResponseRecorder::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val sequence = AtomicInteger(0)

    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())

    /** Test seam: a directory that wins over the system property. Production never sets it. */
    @Volatile
    internal var dirOverride: File? = null

    fun isEnabled(): Boolean = dirOverride != null || CoveragePaths.covdResponsesDir() != null

    /**
     * Saves one answer and returns its sequence number (also logged as `covd.response`).
     *
     * [request] must already be free of the GitLab token; [token] is scrubbed defensively.
     * The body is written unmodified so a reader sees exactly what the daemon sent.
     */
    fun record(
        kind: String,
        elapsedMs: Long,
        httpStatus: Int,
        request: JsonObject,
        responseBody: String,
        token: String?,
    ): Int {
        val seq = sequence.incrementAndGet()
        val dir = dirOverride ?: CoveragePaths.covdResponsesDir() ?: return seq
        try {
            val atMs = System.currentTimeMillis()
            var text = buildContent(seq, atMs, kind, elapsedMs, httpStatus, request, responseBody)
            if (!token.isNullOrBlank()) text = text.replace(token, "***")
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                log.info("covd responses: cannot create ${dir.absolutePath}")
                return seq
            }
            File(dir, fileName(seq, atMs, kind, stateOf(responseBody))).writeText(text)
        } catch (t: Throwable) {
            log.info("covd responses: cannot save the $kind answer: ${t.message}")
        }
        return seq
    }

    /** `<seq:04d>-<yyyyMMdd-HHmmss-SSS>-<kind>-<state>.json`. */
    internal fun fileName(seq: Int, atMs: Long, kind: String, state: String): String =
        String.format(
            Locale.ROOT,
            "%04d-%s-%s-%s.json",
            seq,
            timeFormat.format(Instant.ofEpochMilli(atMs)),
            sanitize(kind, "response"),
            sanitize(state, "unknown"),
        )

    /**
     * The file body. The answer is embedded as raw text when it is valid JSON, so it stays
     * byte-for-byte what the daemon sent; anything else is stored as a JSON string.
     */
    internal fun buildContent(
        seq: Int,
        atMs: Long,
        kind: String,
        elapsedMs: Long,
        httpStatus: Int,
        request: JsonObject,
        responseBody: String,
    ): String = buildString(responseBody.length + request.toString().length + 128) {
        append("{\"seq\":").append(seq)
        append(",\"atMs\":").append(atMs)
        append(",\"kind\":\"").append(escape(kind)).append('"')
        append(",\"elapsedMs\":").append(elapsedMs)
        append(",\"httpStatus\":").append(httpStatus)
        append(",\"request\":").append(request.toString())
        append(",\"response\":").append(if (isJson(responseBody)) responseBody else JsonPrimitive(responseBody).toString())
        append('}')
    }

    /** The answer's `state` field when the body is a JSON object, `unknown`/`unparseable` otherwise. */
    internal fun stateOf(responseBody: String): String {
        val element = runCatching { json.parseToJsonElement(responseBody) }.getOrNull() ?: return "unparseable"
        val state = runCatching { element.jsonObject["state"]?.jsonPrimitive?.content }.getOrNull()
        return state?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    internal fun resetForTest() {
        sequence.set(0)
        dirOverride = null
    }

    private fun isJson(text: String): Boolean =
        text.isNotBlank() && runCatching { json.parseToJsonElement(text) }.isSuccess

    private fun sanitize(value: String, fallback: String): String {
        val cleaned = value.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("").trim('_')
        return cleaned.ifEmpty { fallback }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
