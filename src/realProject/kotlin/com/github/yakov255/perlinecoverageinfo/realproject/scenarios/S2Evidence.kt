package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Reading of the run evidence both hotels/two-IDE scenarios need: the saved covd answers,
 * `proxy.jsonl`, `cache-index.json`, `samples.csv` and the COV4 path index.
 *
 * All names carry the `s2` prefix because several scenarios of the suite live in this package and
 * the helper names of one wave must not collide with another's.
 */
internal object S2Evidence {

    const val PROJECT_ID = "335"

    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val COV_MAGIC = 0x434F5633
    private const val INDEX_MARKER = 0x494E4458

    /** Token-shaped text never leaves a log; a scenario only ever records the cleaned line. */
    private val CREDENTIAL_TEXT = Regex(
        "(?i)(glpat-\\S+|bearer\\s+\\S+|authorization\\s*[:=]\\s*\\S+|private-token\\s*[:=]\\s*\\S+)",
    )

    // ---- saved covd answers ------------------------------------------------------------

    /** One `ensure`/`import` answer as `CovdResponseRecorder` wrote it. */
    data class Answer(
        val atMs: Long,
        val elapsedMs: Long,
        val httpStatus: Int,
        val component: String?,
        val gitRoot: String?,
        val commit: String?,
        val state: String,
        val code: String?,
        val cause: String?,
        val cached: Boolean,
        val replaced: Boolean,
        val stale: Boolean,
        val pathMode: String?,
        val jobPrefixes: List<String>,
        val cov4Path: String?,
        val pipelineId: Long?,
        val files: Long?,
        val lines: Long?,
        val covered: Long?,
        val baselineCommit: String?,
        val baselineCov4Path: String?,
        val baselineState: String?,
        val joinedFlight: Boolean,
        val sharedBuild: Boolean,
        val leaderRequestId: String?,
        val traceComponent: String?,
        /** Job ids whose artifact this answer's trace downloaded. */
        val artifactJobIds: Set<Int>,
        val totalMs: Long?,
        val downloadMs: Long?,
    ) {
        val shortCommit: String get() = commit?.take(8) ?: "--------"
    }

    /** Every saved answer of the run directory's cache, oldest first. */
    fun answers(cacheRoot: File): List<Answer> {
        val dir = File(cacheRoot, "covd-responses")
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            .mapNotNull { file -> runCatching { parseAnswer(file.readText()) }.getOrNull() }
            .sortedWith(compareBy({ it.atMs }, { it.component }))
    }

    fun parseAnswer(text: String): Answer {
        val root = json.parseToJsonElement(text).jsonObject
        val response = root["response"] as? JsonObject ?: JsonObject(emptyMap())
        val request = root["request"] as? JsonObject ?: JsonObject(emptyMap())
        val trace = response["trace"] as? JsonObject
        val traceRequest = trace?.get("request") as? JsonObject
        val timing = trace?.get("timing") as? JsonObject
        val jobIds = (trace?.get("gitlab") as? JsonArray).orEmpty().mapNotNull { call ->
            val path = (call as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Regex("""/jobs/(\d+)/artifacts""").find(path)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        val stats = response["stats"] as? JsonObject
        return Answer(
            atMs = root.s2Long("atMs") ?: 0L,
            elapsedMs = root.s2Long("elapsedMs") ?: 0L,
            httpStatus = (root.s2Long("httpStatus") ?: 0L).toInt(),
            component = request.s2String("component"),
            gitRoot = request.s2String("gitRoot"),
            commit = response.s2String("commit"),
            state = response.s2String("state") ?: "unknown",
            code = response.s2String("code"),
            cause = response.s2String("cause"),
            cached = response.s2Bool("cached") ?: false,
            replaced = response.s2Bool("replaced") ?: false,
            stale = response.s2Bool("stale") ?: false,
            pathMode = request.s2String("pathMode"),
            jobPrefixes = (request["jobPrefixes"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            cov4Path = response.s2String("cov4Path"),
            pipelineId = response.s2Long("pipelineId"),
            files = stats?.s2Long("files"),
            lines = stats?.s2Long("lines"),
            covered = stats?.s2Long("covered"),
            baselineCommit = response.s2String("baselineCommit"),
            baselineCov4Path = response.s2String("baselineCov4Path"),
            baselineState = response.s2String("baselineState"),
            joinedFlight = (trace?.get("joinedFlight") as? JsonPrimitive)?.booleanOrNull ?: false,
            sharedBuild = (trace?.get("sharedBuild") as? JsonPrimitive)?.booleanOrNull ?: false,
            leaderRequestId = trace?.s2String("leaderRequestId"),
            traceComponent = traceRequest?.s2String("component"),
            artifactJobIds = jobIds,
            totalMs = timing?.s2Long("totalMs"),
            downloadMs = timing?.s2Long("downloadMs"),
        )
    }

    // ---- proxy journal -----------------------------------------------------------------

    /** One row of `proxy.jsonl`. */
    data class JournalRow(
        val t: Long,
        val seq: Int,
        val method: String,
        val path: String,
        val status: Int,
        val bytesDown: Long,
        val ms: Long,
        val fault: String?,
        val artifactJobId: Int?,
    ) {
        /** Response interval the row describes. */
        val endsAt: Long get() = t + ms
    }

    fun journal(runDir: File): List<JournalRow> {
        val file = File(runDir, "proxy.jsonl")
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val root = json.parseToJsonElement(line).jsonObject
                JournalRow(
                    t = root.s2Long("t") ?: 0L,
                    seq = (root.s2Long("seq") ?: 0L).toInt(),
                    method = root.s2String("method") ?: "",
                    path = root.s2String("path") ?: "",
                    status = (root.s2Long("status") ?: 0L).toInt(),
                    bytesDown = root.s2Long("bytesDown") ?: 0L,
                    ms = root.s2Long("ms") ?: 0L,
                    fault = root.s2String("fault"),
                    artifactJobId = root.s2Long("artifactJobId")?.toInt(),
                )
            }.getOrNull()
        }.sortedWith(compareBy({ it.t }, { it.seq }))
    }

    // ---- covd cache index --------------------------------------------------------------

    data class CacheEntry(
        val commit: String,
        val component: String,
        val files: Long?,
        val lines: Long?,
        val covered: Long?,
        val pipelineId: Long?,
        val branch: String?,
    ) {
        val key: String get() = "$component@${commit.take(8)}"
    }

    fun cacheIndex(cacheRoot: File): List<CacheEntry> {
        val file = File(File(cacheRoot, PROJECT_ID), "cache-index.json")
        if (!file.isFile) return emptyList()
        val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return emptyList()
        return (root["entries"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val commit = entry.s2String("commitHash") ?: return@mapNotNull null
            CacheEntry(
                commit = commit,
                component = entry.s2String("component") ?: "",
                files = entry.s2Long("totalFiles"),
                lines = entry.s2Long("totalLines"),
                covered = entry.s2Long("coveredLines"),
                pipelineId = entry.s2Long("pipelineId"),
                branch = entry.s2String("branch"),
            )
        }
    }

    /** Files next to `cache-index.json` that end in `.corrupt`: the daemon moved a broken index. */
    fun corruptIndexFiles(cacheRoot: File): List<String> =
        File(cacheRoot, PROJECT_ID).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".corrupt") }
            .map { it.name }

    // ---- COV4 -------------------------------------------------------------------------

    /**
     * Paths of a COV4 file through its file index (`coverage_storage_format_v4.md`).
     * Diagnostics only: a failure message names what the plugin actually has.
     */
    fun cov4Paths(file: File): List<String> = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(28)
        raf.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        require(magic == COV_MAGIC) { "bad magic 0x${Integer.toHexString(magic)}" }
        val version = buffer.int
        require(version == 4) { "unsupported COV version $version" }
        buffer.int // files
        buffer.int // tests
        buffer.int // directories
        val indexOffset = buffer.int.toLong() and 0xFFFFFFFFL
        raf.seek(indexOffset)
        val marker = raf.readInt()
        require(marker == INDEX_MARKER) { "bad index marker 0x${Integer.toHexString(marker)}" }
        val count = raf.readInt()
        require(count in 0..5_000_000) { "implausible file count $count" }
        val paths = ArrayList<String>(count.coerceAtMost(10_000))
        repeat(count) {
            raf.readLong() // path hash
            val pathOffset = raf.readInt().toLong() and 0xFFFFFFFFL
            raf.readInt() // data offset
            raf.readInt() // data size
            raf.readInt() // crc
            val here = raf.filePointer
            raf.seek(pathOffset)
            val length = raf.readInt()
            require(length in 0..1_000_000) { "implausible path length $length" }
            val bytes = ByteArray(length)
            raf.readFully(bytes)
            paths += String(bytes, Charsets.UTF_8)
            raf.seek(here)
        }
        paths
    }

    // ---- samples and logs --------------------------------------------------------------

    /** Peak RSS (MiB) of one sampled process inside `[fromMs, toMs]`. */
    fun peakRss(samplesCsv: File, proc: String, fromMs: Long, toMs: Long): Double? {
        if (!samplesCsv.isFile) return null
        return samplesCsv.useLines { lines ->
            lines.drop(1)
                .map { it.split(',') }
                .filter { it.size >= 4 && it[1] == proc }
                .filter { row -> (row[0].toLongOrNull() ?: 0L) in fromMs..toMs }
                .mapNotNull { it[3].toDoubleOrNull() }
                .maxOrNull()
        }
    }

    /**
     * SEVERE/ERROR lines that mention the plugin or covd, cleaned of token-shaped text.
     * Returns at most 20 lines: a report needs the fact, not the log.
     */
    fun pluginErrorLines(logFile: File): List<String> {
        if (!logFile.isFile) return emptyList()
        return runCatching {
            logFile.readLines().filter { line ->
                (line.contains("SEVERE") || line.contains("ERROR")) &&
                    (line.contains("covd") || line.contains("Coverage", ignoreCase = true) ||
                        line.contains("perlinecoverageinfo"))
            }.map { sanitizeLogLine(it) }.take(20)
        }.getOrDefault(emptyList())
    }

    /** Number of SEVERE/ERROR lines of any origin: recorded, never a failure by itself. */
    fun severeCount(logFile: File): Int {
        if (!logFile.isFile) return 0
        return runCatching {
            logFile.useLines { lines -> lines.count { it.contains("SEVERE") || it.contains(" ERROR ") } }
        }.getOrDefault(0)
    }

    fun sanitizeLogLine(line: String): String =
        line.replace(CREDENTIAL_TEXT, "<credential>").take(300)

    fun format(value: Double?): String =
        if (value == null) "—" else String.format(Locale.ROOT, "%.1f", value)
}

// ---- small JSON helpers ----------------------------------------------------------------

internal fun JsonObject.s2String(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.contentOrNull != null }?.content

internal fun JsonObject.s2Long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.s2Bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
