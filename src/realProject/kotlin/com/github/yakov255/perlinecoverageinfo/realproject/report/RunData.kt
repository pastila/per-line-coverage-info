package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import kotlinx.serialization.json.JsonObject

/**
 * One coverage load, assembled from the perf log and/or a saved `covd` answer. Fields stay
 * nullable so a missing phase is reported as "—" instead of a misleading zero.
 *
 * `docs/real-project-testing.md` fixes both sources: the perf `covd.response` line carries the
 * flat numbers, the saved `covd-responses` answers carry the daemon's `trace.timing` and
 * `trace.gitlab`.
 */
internal class LoadRecord(
    var tMs: Long,
    var seq: Long? = null,
    var requestId: String? = null,
    var kind: String = "ensure",
    var state: String = "",
    var code: String? = null,
    var commit: String = "",
    var cached: Boolean? = null,
    var replaced: Boolean? = null,
    var stale: Boolean? = null,
    var joinedFlight: Boolean? = null,
    var httpStatus: Int? = null,
    var totalMs: Double? = null,
    var resolveMs: Double? = null,
    var downloadMs: Double? = null,
    var parseMs: Double? = null,
    var mergeMs: Double? = null,
    var writeMs: Double? = null,
    var baselineMs: Double? = null,
    var gitlabCalls: Long? = null,
    var gitlabBytes: Long? = null,
    var gitlabErrors: Long? = null,
    var source: String = "perf",
    var scenario: String = "",
) {
    val shortCommit: String get() = commit.take(8)

    /** Fills only the still-empty fields, so the perf line stays the primary source. */
    fun mergeMissing(other: LoadRecord) {
        if (seq == null) seq = other.seq
        if (requestId.isNullOrBlank()) requestId = other.requestId
        if (kind.isBlank() || kind == "ensure") kind = other.kind.ifBlank { kind }
        if (state.isBlank()) state = other.state
        if (code.isNullOrBlank()) code = other.code
        if (commit.isBlank()) commit = other.commit
        if (cached == null) cached = other.cached
        if (replaced == null) replaced = other.replaced
        if (stale == null) stale = other.stale
        if (joinedFlight == null) joinedFlight = other.joinedFlight
        if (httpStatus == null) httpStatus = other.httpStatus
        if (totalMs == null) totalMs = other.totalMs
        if (resolveMs == null) resolveMs = other.resolveMs
        if (downloadMs == null) downloadMs = other.downloadMs
        if (parseMs == null) parseMs = other.parseMs
        if (mergeMs == null) mergeMs = other.mergeMs
        if (writeMs == null) writeMs = other.writeMs
        if (baselineMs == null) baselineMs = other.baselineMs
        if (gitlabCalls == null) gitlabCalls = other.gitlabCalls
        if (gitlabBytes == null) gitlabBytes = other.gitlabBytes
        if (gitlabErrors == null) gitlabErrors = other.gitlabErrors
        if (scenario.isBlank()) scenario = other.scenario
    }
}

/**
 * Everything the report reads from a run directory. Missing files produce empty collections, so
 * an empty or partial directory still yields a complete report.
 */
internal class RunData(
    val runDir: File,
    val perfLogs: List<PerfLogFile>,
    val perfRecords: List<PerfRecord>,
    val proxy: ProxyJournal,
    val covdResponses: List<CovdResponseFile>,
    val samples: List<ProcSample>,
    val snapshots: List<Snapshot>,
    val scenarioEvents: List<ScenarioEvent>,
    val loads: List<LoadRecord>,
) {
    val perfSamples: List<PerfRecord> get() = perfRecords.filter { it.ev == "sample" }

    val covdSamples: List<PerfRecord> get() = perfRecords.filter { it.eventName == "covdsample" }

    val spawns: List<PerfRecord> get() = perfRecords.filter { it.eventName == "covd.spawn" }

    val spanFailures: List<PerfRecord>
        get() = perfRecords.filter {
            it.ev == "span" && it.fields["ph"] == "end" && it.fields["ok"] == "false"
        }

    val malformedPerfLines: Int get() = perfLogs.sumOf { it.malformed }

    val malformedProxyRows: Int get() = proxy.malformed

    /** Loads that ended in a daemon error answer, with their code. */
    val errorLoads: List<LoadRecord> get() = loads.filter { it.state == "error" }

    /** All GitLab exchanges recorded in saved answers (`trace.gitlab[]`). */
    val gitlabTraceCalls: List<JsonObject> get() = covdResponses.flatMap { it.gitlabCalls }

    fun scenarioAt(tMs: Long): String = scenarioIndex.at(tMs)

    private val scenarioIndex: ScenarioIndex = ScenarioIndex(scenarioEvents)
}

/** Attributes a point in time to the scenario that was active then (the last event before it). */
internal class ScenarioIndex(events: List<ScenarioEvent>) {

    private val timed = events.filter { it.tMs != null }.sortedBy { it.tMs!! }

    fun at(tMs: Long): String =
        timed.lastOrNull { (it.tMs ?: Long.MAX_VALUE) <= tMs }
            ?.scenario
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
}

internal object RunLoader {

    private const val MERGE_WINDOW_MS = 120_000L

    fun load(runDir: File): RunData {
        val perfDir = File(runDir, "perf")
        val perfLogs = when {
            perfDir.isDirectory -> PerfLogParser.read(perfDir)
            else -> PerfLogParser.read(runDir)
        }
        val perfRecords = perfLogs.flatMap { it.records }.sortedBy { it.tMs }
        val proxy = ProxyJournalParser.parse(File(runDir, "proxy.jsonl"))
        val covdResponses = CovdResponsesParser.read(File(runDir, "covd-responses"))
        val samples = SamplesCsvParser.parse(File(runDir, "samples.csv"))
        val snapshots = SnapshotsParser.parse(File(runDir, "snapshots.jsonl"))
        val scenarioEvents = ScenariosParser.parse(File(runDir, "scenarios.jsonl"))

        val index = ScenarioIndex(scenarioEvents)
        val loads = buildLoads(perfRecords, covdResponses, index)
        return RunData(
            runDir = runDir,
            perfLogs = perfLogs,
            perfRecords = perfRecords,
            proxy = proxy,
            covdResponses = covdResponses,
            samples = samples,
            snapshots = snapshots,
            scenarioEvents = scenarioEvents,
            loads = loads,
        )
    }

    private fun buildLoads(
        perfRecords: List<PerfRecord>,
        covdResponses: List<CovdResponseFile>,
        index: ScenarioIndex,
    ): List<LoadRecord> {
        val fromPerf = perfRecords
            .filter { it.eventName == "covd.response" }
            .map { loadFromPerf(it) }

        // The perf line is the primary source; a saved answer adds the nested trace and can
        // rescue a run whose perf log was not copied.
        val merged = fromPerf.toMutableList()
        for (saved in covdResponses) {
            val candidate = loadFromSaved(saved)
            val existing = merged.firstOrNull { matches(it, candidate) }
            if (existing != null) {
                existing.mergeMissing(candidate)
            } else {
                merged += candidate
            }
        }

        // Runs made before `covd.response` existed still carry one `covd.ensure` span per load.
        // Use them only when no structured answer is available at all, so no load is counted twice.
        val result = if (merged.isEmpty()) {
            perfRecords
                .filter { it.ev == "span" && it.name == "covd.ensure" && it.fields["ph"] == "end" }
                .map { loadFromSpan(it) }
                .toMutableList()
        } else {
            merged
        }

        return result
            .sortedBy { it.tMs }
            .onEach { if (it.scenario.isBlank()) it.scenario = index.at(it.tMs) }
    }

    private fun matches(existing: LoadRecord, other: LoadRecord): Boolean {
        val leftSeq = existing.seq
        val rightSeq = other.seq
        if (leftSeq != null && rightSeq != null) return leftSeq == rightSeq
        val leftRequest = existing.requestId
        val rightRequest = other.requestId
        if (!leftRequest.isNullOrBlank() && !rightRequest.isNullOrBlank()) return leftRequest == rightRequest
        if (existing.commit.isNotBlank() && existing.commit == other.commit) {
            return kotlin.math.abs(existing.tMs - other.tMs) <= MERGE_WINDOW_MS
        }
        return false
    }

    private fun loadFromPerf(record: PerfRecord): LoadRecord = LoadRecord(
        tMs = record.tMs,
        seq = record.long("seq"),
        requestId = record.str("requestId"),
        kind = record.str("kind") ?: "ensure",
        state = record.str("state").orEmpty(),
        code = record.str("code"),
        commit = record.str("commit").orEmpty(),
        cached = record.bool("cached"),
        replaced = record.bool("replaced"),
        stale = record.bool("stale"),
        joinedFlight = record.bool("joinedFlight"),
        httpStatus = record.int("httpStatus"),
        totalMs = record.double("totalMs") ?: record.double("elapsedMs"),
        resolveMs = record.double("resolveMs"),
        downloadMs = record.double("downloadMs"),
        parseMs = record.double("parseMs"),
        mergeMs = record.double("mergeMs"),
        writeMs = record.double("writeMs"),
        baselineMs = record.double("baselineMs"),
        gitlabCalls = record.long("gitlabCalls"),
        gitlabBytes = record.long("gitlabBytes"),
        gitlabErrors = record.long("gitlabErrors"),
        source = "perf",
    )

    /** A load that has only a duration from the `covd.ensure` span. */
    private fun loadFromSpan(record: PerfRecord): LoadRecord = LoadRecord(
        tMs = record.tMs,
        kind = "ensure",
        state = "",
        totalMs = record.double("wallMs"),
        source = "span",
    )

    private fun loadFromSaved(saved: CovdResponseFile): LoadRecord {
        val response = saved.response
        val trace = response?.objOrNull("trace")
        val outcome = trace?.objOrNull("outcome")
        val timing = trace?.objOrNull("timing")
        val gitlab = saved.gitlabCalls
        val request = saved.request

        fun timingMs(key: String): Double? =
            response?.doubleOrNull(key) ?: timing?.doubleOrNull(key)

        return LoadRecord(
            tMs = saved.atMs ?: saved.file.lastModified(),
            seq = saved.seq,
            requestId = trace?.stringOrNull("requestId") ?: response?.stringOrNull("requestId"),
            kind = saved.kind ?: "ensure",
            state = response?.stringOrNull("state") ?: outcome?.stringOrNull("state").orEmpty(),
            code = response?.stringOrNull("code") ?: outcome?.stringOrNull("code"),
            commit = response?.stringOrNull("commit") ?: trace?.stringOrNull("commit").orEmpty(),
            cached = response?.boolOrNull("cached"),
            replaced = response?.boolOrNull("replaced"),
            stale = response?.boolOrNull("stale"),
            joinedFlight = trace?.boolOrNull("joinedFlight") ?: response?.boolOrNull("joinedFlight"),
            httpStatus = saved.httpStatus,
            totalMs = timingMs("totalMs") ?: saved.elapsedMs,
            resolveMs = timingMs("resolveMs"),
            downloadMs = timingMs("downloadMs"),
            parseMs = timingMs("parseMs"),
            mergeMs = timingMs("mergeMs"),
            writeMs = timingMs("writeMs"),
            baselineMs = timingMs("baselineMs"),
            gitlabCalls = if (gitlab.isNotEmpty()) gitlab.size.toLong() else null,
            gitlabBytes = if (gitlab.isNotEmpty()) {
                gitlab.sumOf { it.longOrNull("bytes", "bytesDown") ?: 0L }
            } else {
                null
            },
            gitlabErrors = if (gitlab.isNotEmpty()) {
                gitlab.count { call ->
                    call.stringOrNull("error") != null || (call.intOrNull("status") ?: 0) >= 400
                }.toLong()
            } else {
                null
            },
            source = "covd-responses",
        ).also {
            // The saved request names the component and the commit; the answer usually repeats it.
            if (it.commit.isBlank()) it.commit = request?.stringOrNull("commit").orEmpty()
        }
    }
}
