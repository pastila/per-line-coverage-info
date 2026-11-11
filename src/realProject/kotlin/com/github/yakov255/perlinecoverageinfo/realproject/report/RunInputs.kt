package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// ---------------------------------------------------------------------------------------------
// JSON helpers. The run files are written by several work items (proxy, harness, scenarios), so
// the reader tolerates missing and extra fields instead of binding to one exact shape.
// ---------------------------------------------------------------------------------------------

internal val RUN_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun parseJsonObject(text: String): JsonObject? = try {
    RUN_JSON.parseToJsonElement(text) as? JsonObject
} catch (_: Exception) {
    null
}

internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.stringOrNull(vararg keys: String): String? {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        if (element is JsonNull || element.isString && element.content.isEmpty()) continue
        if (element.isString) return element.content
        element.contentOrNull?.let { return it }
    }
    return null
}

internal fun JsonObject.longOrNull(vararg keys: String): Long? {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        element.longOrNull?.let { return it }
        element.contentOrNull?.toLongOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.intOrNull(vararg keys: String): Int? =
    longOrNull(*keys)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

internal fun JsonObject.doubleOrNull(vararg keys: String): Double? {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        element.doubleOrNull?.let { return it }
        element.contentOrNull?.toDoubleOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.boolOrNull(vararg keys: String): Boolean? {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        if (element.isString) {
            when (element.content.lowercase()) {
                "true" -> return true
                "false" -> return false
            }
        } else {
            element.contentOrNull?.let { return it.toBoolean() }
        }
    }
    return null
}

/** Epoch millis from a number, a numeric string or an ISO-8601 instant. */
internal fun JsonElement.epochMsOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { return normaliseEpoch(it) }
    val text = primitive.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return normaliseEpoch(it) }
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/** Handles seconds as well as milliseconds: a 10-digit value cannot be a current millisecond. */
private fun normaliseEpoch(value: Long): Long =
    if (value in 1..99_999_999_999L) value * 1_000L else value

private fun readLines(file: File): List<String> = try {
    file.bufferedReader(Charsets.UTF_8).useLines { it.toList() }
} catch (_: IOException) {
    emptyList()
} catch (_: SecurityException) {
    emptyList()
}

// ---------------------------------------------------------------------------------------------
// proxy.jsonl
// ---------------------------------------------------------------------------------------------

/**
 * One proxied request, as documented in `docs/real-project-testing.md`. Only `authSeen` is kept
 * from the credential headers: the journal never stores their value, and the report never prints
 * anything but this flag.
 */
internal data class ProxyRow(
    val tMs: Long,
    val seq: Long?,
    val method: String,
    val path: String,
    val query: String?,
    val status: Int?,
    val bytesDown: Long,
    val bytesUp: Long,
    val ms: Double?,
    val fault: String?,
    val artifactJobId: Long?,
    val authSeen: Boolean?,
)

internal data class ProxyJournal(val rows: List<ProxyRow>, val malformed: Int) {

    val bytesDown: Long get() = rows.sumOf { it.bytesDown }

    val bytesUp: Long get() = rows.sumOf { it.bytesUp }

    /** Artifacts are identified by the GitLab job id; listing requests have no id. */
    val artifacts: Int get() = rows.mapNotNull { it.artifactJobId }.distinct().size

    val firstMs: Long? get() = rows.minOfOrNull { it.tMs }

    val lastMs: Long? get() = rows.maxOfOrNull { it.tMs }

    val windowMs: Long? get() = if (rows.isEmpty()) null else (lastMs ?: 0L) - (firstMs ?: 0L)

    val errorStatuses: Map<Int, Int>
        get() = rows.mapNotNull { row -> row.status?.takeIf { it >= 400 } }
            .groupingBy { it }
            .eachCount()

    val faults: Map<String, Int>
        get() = rows.mapNotNull { it.fault }
            .groupingBy { it }
            .eachCount()

    val latencyMs: Double get() = rows.sumOf { it.ms ?: 0.0 }
}

internal object ProxyJournalParser {

    fun parse(file: File): ProxyJournal {
        if (!file.isFile) return ProxyJournal(emptyList(), 0)
        val rows = ArrayList<ProxyRow>()
        var malformed = 0
        for (raw in readLines(file)) {
            if (raw.isBlank()) continue
            val row = parseRow(raw)
            if (row == null) {
                malformed++
            } else {
                rows += row
            }
        }
        return ProxyJournal(rows.sortedBy { it.tMs }, malformed)
    }

    fun parseRow(raw: String): ProxyRow? {
        val json = parseJsonObject(raw) ?: return null
        val tMs = (json["t"] ?: json["tMs"] ?: json["ts"])?.epochMsOrNull() ?: return null
        val status = json.intOrNull("status")
        val faultText = json.stringOrNull("fault")
        return ProxyRow(
            tMs = tMs,
            seq = json.longOrNull("seq"),
            method = json.stringOrNull("method") ?: "GET",
            path = json.stringOrNull("path").orEmpty(),
            query = json.stringOrNull("query"),
            status = status,
            bytesDown = json.longOrNull("bytesDown", "bytes") ?: 0L,
            bytesUp = json.longOrNull("bytesUp") ?: 0L,
            ms = json.doubleOrNull("ms", "elapsedMs"),
            fault = faultText?.takeIf { it.isNotEmpty() && it != "null" },
            artifactJobId = json.longOrNull("artifactJobId", "jobId"),
            authSeen = json.boolOrNull("authSeen"),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// samples.csv (`tMs,proc,pid,rssMb,cpuPct`)
// ---------------------------------------------------------------------------------------------

internal data class ProcSample(
    val tMs: Long,
    val proc: String,
    val pid: Long?,
    val rssMb: Double?,
    val cpuPct: Double?,
)

internal object SamplesCsvParser {

    fun parse(file: File): List<ProcSample> {
        if (!file.isFile) return emptyList()
        val lines = readLines(file)
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().removePrefix("\uFEFF").split(',').map { it.trim().lowercase() }
        val hasHeader = header.contains("tms") || header.contains("proc")
        val index = if (hasHeader) {
            header.withIndex().associate { (i, name) -> name to i }
        } else {
            mapOf("tms" to 0, "proc" to 1, "pid" to 2, "rssmb" to 3, "cpupct" to 4)
        }
        val result = ArrayList<ProcSample>()
        for (line in if (hasHeader) lines.drop(1) else lines) {
            if (line.isBlank()) continue
            val cells = line.split(',')
            fun cell(name: String): String? =
                index[name]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

            val tMs = cell("tms")?.toLongOrNull() ?: cell("tms")?.toDoubleOrNull()?.toLong() ?: continue
            result += ProcSample(
                tMs = tMs,
                proc = cell("proc").orEmpty(),
                pid = cell("pid")?.toLongOrNull(),
                rssMb = cell("rssmb")?.toDoubleOrNull(),
                cpuPct = cell("cpupct")?.toDoubleOrNull(),
            )
        }
        return result.sortedBy { it.tMs }
    }
}

// ---------------------------------------------------------------------------------------------
// snapshots.jsonl (CoverageUiTestBridge snapshot with tMs)
// ---------------------------------------------------------------------------------------------

internal data class Snapshot(
    val tMs: Long,
    val state: String?,
    val commit: String?,
    val baselineCommit: String?,
    val stale: Boolean?,
    val loading: Boolean?,
    val lastError: String?,
    val coverageGeneration: Long?,
    val dumb: Boolean?,
    val covdInstanceId: String?,
    val covdPid: Long?,
)

internal object SnapshotsParser {

    fun parse(file: File): List<Snapshot> {
        if (!file.isFile) return emptyList()
        val result = ArrayList<Snapshot>()
        for (raw in readLines(file)) {
            if (raw.isBlank()) continue
            val json = parseJsonObject(raw) ?: continue
            val tMs = (json["tMs"] ?: json["t"])?.epochMsOrNull() ?: continue
            result += Snapshot(
                tMs = tMs,
                state = json.stringOrNull("state"),
                commit = json.stringOrNull("commit"),
                baselineCommit = json.stringOrNull("baselineCommit"),
                stale = json.boolOrNull("stale"),
                loading = json.boolOrNull("loading"),
                lastError = json.stringOrNull("lastError"),
                coverageGeneration = json.longOrNull("coverageGeneration", "generation"),
                dumb = json.boolOrNull("dumb"),
                covdInstanceId = json.stringOrNull("covdInstanceId"),
                covdPid = json.longOrNull("covdPid"),
            )
        }
        return result.sortedBy { it.tMs }
    }
}

// ---------------------------------------------------------------------------------------------
// scenarios.jsonl (`{"t","scenario","phase","msg","data"}`)
// ---------------------------------------------------------------------------------------------

internal data class ScenarioEvent(
    val tMs: Long?,
    val scenario: String,
    val phase: String,
    val msg: String,
    val dataText: String,
) {
    /** The whole event as searchable text, used to spot balloons and branch switches. */
    val searchable: String get() = "$scenario $phase $msg $dataText".lowercase()
}

internal object ScenariosParser {

    fun parse(file: File): List<ScenarioEvent> {
        if (!file.isFile) return emptyList()
        val result = ArrayList<ScenarioEvent>()
        for (raw in readLines(file)) {
            if (raw.isBlank()) continue
            val json = parseJsonObject(raw) ?: continue
            val dataElement = json["data"]
            result += ScenarioEvent(
                tMs = (json["t"] ?: json["tMs"])?.epochMsOrNull(),
                scenario = json.stringOrNull("scenario").orEmpty(),
                phase = json.stringOrNull("phase").orEmpty(),
                msg = json.stringOrNull("msg", "message").orEmpty(),
                dataText = when (dataElement) {
                    null, JsonNull -> ""
                    else -> dataElement.toString()
                },
            )
        }
        return result.sortedWith(compareBy({ it.tMs ?: Long.MAX_VALUE }))
    }
}

// ---------------------------------------------------------------------------------------------
// covd-responses/<seq>-<timestamp>-<kind>-<state>.json
// ---------------------------------------------------------------------------------------------

internal data class CovdResponseFile(
    val file: File,
    val seq: Long?,
    val atMs: Long?,
    val kind: String?,
    val elapsedMs: Double?,
    val httpStatus: Int?,
    val request: JsonObject?,
    val response: JsonObject?,
) {
    val trace: JsonObject? get() = response?.objOrNull("trace")

    /** Daemon GitLab exchanges; empty for builds that predate the trace. */
    val gitlabCalls: List<JsonObject>
        get() {
            val traceObject = trace ?: return emptyList()
            val array = traceObject.arrayOrNull("gitlab") ?: return emptyList()
            return array.mapNotNull { it as? JsonObject }
        }
}

internal object CovdResponsesParser {

    private val SEQ_PREFIX = Regex("^(\\d+)-")

    fun read(dir: File): List<CovdResponseFile> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.mapNotNull { parse(it) }
            .orEmpty()
    }

    fun parse(file: File): CovdResponseFile? {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        val json = parseJsonObject(text) ?: return null
        val filenameSeq = SEQ_PREFIX.find(file.name)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val response = json.objOrNull("response")
        val traceOutcome = response?.objOrNull("trace")?.objOrNull("outcome")
        return CovdResponseFile(
            file = file,
            seq = json.longOrNull("seq") ?: filenameSeq,
            atMs = (json["atMs"] ?: json["at"])?.epochMsOrNull(),
            kind = json.stringOrNull("kind"),
            elapsedMs = json.doubleOrNull("elapsedMs"),
            httpStatus = json.intOrNull("httpStatus") ?: traceOutcome?.intOrNull("httpStatus"),
            request = json.objOrNull("request"),
            response = response,
        )
    }
}
