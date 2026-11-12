package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import java.io.IOException

/**
 * One line of the plugin performance log, written by
 * `com.github.yakov255.perlinecoverageinfo.CoveragePerfLog`:
 *
 * ```
 * <epochMs> <HH:mm:ss.SSS> ev=span name=load.offlineFirst ph=end ...
 * ```
 *
 * `CoveragePerfLog` replaces spaces and `=` inside values, so the line is a flat list of
 * whitespace-separated `key=value` tokens and needs no quoting.
 */
internal data class PerfRecord(val tMs: Long, val fields: Map<String, String>) {

    /** Value of `ev=`; empty when the line has no event marker. */
    val ev: String get() = fields["ev"].orEmpty()

    /** Value of `name=`; empty when absent. */
    val name: String get() = fields["name"].orEmpty()

    /**
     * `CoveragePerfLog.event(name)` writes `ev=event name=<name>`, a direct writer may emit
     * `ev=<name>`; both forms name the same event, so callers use this property only.
     */
    val eventName: String get() = if (ev == "event") name else ev

    /** The counter treats the literal `null` and `-` (written by the sanitizer) as absent. */
    fun str(key: String): String? =
        fields[key]?.takeIf { it.isNotEmpty() && it != "-" && it != "null" }

    fun long(key: String): Long? = str(key)?.toLongOrNull()

    fun int(key: String): Int? = str(key)?.toIntOrNull()

    fun double(key: String): Double? = str(key)?.toDoubleOrNull()

    fun bool(key: String): Boolean? = when (str(key)?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** All parsed lines of one perf log plus the number of lines that could not be parsed. */
internal data class PerfLogFile(
    val file: File,
    val records: List<PerfRecord>,
    val lines: Int,
    val malformed: Int,
    val readError: String? = null,
)

/**
 * Tolerant reader for the perf log. A malformed line never fails the report: it is counted and
 * skipped, because a truncated or concurrently written file is a normal real-run artefact.
 */
internal object PerfLogParser {

    private val WHITESPACE = Regex("\\s+")

    fun parse(file: File): PerfLogFile {
        val records = ArrayList<PerfRecord>()
        var lines = 0
        var malformed = 0
        val readError = try {
            file.bufferedReader(Charsets.UTF_8).useLines { sequence ->
                for (raw in sequence) {
                    if (raw.isBlank()) continue
                    lines++
                    val record = parseLine(raw)
                    if (record == null) {
                        malformed++
                    } else {
                        records += record
                    }
                }
            }
            null
        } catch (e: IOException) {
            e.message
        } catch (e: SecurityException) {
            e.message
        }
        return PerfLogFile(file, records, lines, malformed, readError)
    }

    /**
     * Parses one line; returns `null` when the epoch prefix or every `key=value` token is
     * missing. The timestamp is the first token, so a line without it cannot be placed on the
     * timeline and is useless for the report.
     */
    fun parseLine(raw: String): PerfRecord? {
        val tokens = raw.trim().split(WHITESPACE)
        val tMs = tokens.firstOrNull()?.toLongOrNull() ?: return null
        val fields = LinkedHashMap<String, String>()
        for (token in tokens.drop(1)) {
            val eq = token.indexOf('=')
            if (eq <= 0 || eq == token.length - 1) continue
            fields[token.substring(0, eq)] = token.substring(eq + 1)
        }
        if (fields.isEmpty()) return null
        return PerfRecord(tMs, fields)
    }

    /**
     * Reads every `*.log` under [dir], sorted by name so that the report is deterministic.
     * A single file may be passed as well.
     */
    fun read(dir: File): List<PerfLogFile> {
        if (dir.isFile) return listOf(parse(dir))
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedBy { it.name }
            ?.map { parse(it) }
            .orEmpty()
    }
}
