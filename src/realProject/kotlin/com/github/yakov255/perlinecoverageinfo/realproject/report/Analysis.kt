package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Median and friends over an empty list return `null`, which the report renders as "—". */
internal object Numbers {

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

    fun max(values: List<Double>): Double? = values.maxOrNull()

    fun min(values: List<Double>): Double? = values.minOrNull()
}

internal data class TimingGroup(
    val scenario: String,
    val commit: String,
    val loads: List<LoadRecord>,
) {
    val count: Int get() = loads.size

    private fun values(selector: (LoadRecord) -> Double?): List<Double> = loads.mapNotNull(selector)

    val medianTotalMs: Double? get() = Numbers.median(values { it.totalMs })
    val minTotalMs: Double? get() = Numbers.min(values { it.totalMs })
    val maxTotalMs: Double? get() = Numbers.max(values { it.totalMs })
}

internal data class CovdProcessMemory(
    val pid: Long?,
    val samples: Int,
    val rssMaxMb: Double?,
    val rssAvgMb: Double?,
    val cpuMaxPct: Double?,
    val threadsMax: Int?,
    val source: String,
    val peakRssAtMs: Long?,
)

internal data class PeakOnPhase(
    val tMs: Long,
    val proc: String,
    val metric: String,
    val value: Double,
    val scenario: String,
    val commit: String,
    val phase: String,
)

internal data class DownloadStats(
    val proxyPresent: Boolean,
    val requests: Int,
    val bytesDown: Long,
    val bytesUp: Long,
    val artifacts: Int,
    val errorStatuses: Map<Int, Int>,
    val faults: Map<String, Int>,
    val authSeenCount: Int,
    val windowMs: Long?,
    val mbPerSecond: Double?,
    val effectiveMbPerSecond: Double?,
    val topPaths: List<Pair<String, Long>>,
    val traceCalls: Int,
    val traceBytes: Long,
    val traceErrors: Int,
    val tracePresent: Boolean,
    val perfGitlabCalls: Long?,
    val perfGitlabBytes: Long?,
    val perfGitlabErrors: Long?,
)

internal data class MemoryStats(
    val ideSamples: Int,
    val heapUsedMaxMb: Double?,
    val heapUsedAvgMb: Double?,
    val heapMaxMb: Double?,
    val gcMs: Long?,
    val gcCount: Long?,
    val threadsMax: Int?,
    val cpuMaxPct: Double?,
    val cpuAvgPct: Double?,
    val durationMs: Long?,
    val peakHeapAtMs: Long?,
    val covd: List<CovdProcessMemory>,
    val covdPeakRssMb: Double?,
    val covdPeakCpuPct: Double?,
    val phasePeaks: List<PeakOnPhase>,
)

internal data class CacheStats(
    val cachedLoads: List<LoadRecord>,
    val replacedCount: Int,
    val repeatGitlabCalls: Long?,
    val repeatViolations: List<LoadRecord>,
    val cacheDir: File?,
    val cacheBytes: Long?,
    val cov4Files: Int?,
    val artifactsOnDisk: List<Pair<String, Long>>,
)

internal data class DaemonStats(
    val spawns: List<PerfRecord>,
    val counts: Map<String, Int>,
    val pids: List<Long>,
    val instances: List<String>,
    val joinedFlightLoads: Int,
    val snapshotInstances: List<String>,
    val snapshotPids: List<Long>,
)

internal data class SwitchDetection(
    val event: ScenarioEvent,
    val newLoad: LoadRecord?,
    val delayMs: Long?,
)

internal data class SwitchStats(
    val events: List<ScenarioEvent>,
    val switches: List<SwitchDetection>,
)

internal data class FailureStats(
    val errorLoads: List<LoadRecord>,
    val errorCodes: Map<String, Int>,
    val retries: Int,
    val balloons: List<ScenarioEvent>,
    val spanFailures: List<PerfRecord>,
    val proxyStatusErrors: Map<Int, Int>,
    val lastErrors: List<Snapshot>,
    val traceErrors: Int,
)

/**
 * All section numbers, computed once from [data] and shared by the Markdown and JSON writers.
 * Every property may be empty/null; the writers turn that into "нет данных", never an exception.
 */
internal class Analysis(val data: RunData) {

    val proxyPresent: Boolean = File(data.runDir, "proxy.jsonl").isFile

    val covdResponsesPresent: Boolean = File(data.runDir, "covd-responses").isDirectory

    val samplesPresent: Boolean = File(data.runDir, "samples.csv").isFile

    val snapshotsPresent: Boolean = File(data.runDir, "snapshots.jsonl").isFile

    val scenariosPresent: Boolean = File(data.runDir, "scenarios.jsonl").isFile

    // ---- 1. timing -------------------------------------------------------------------------

    val timingGroups: List<TimingGroup> = data.loads
        .groupBy { it.scenario to it.shortCommit }
        .map { (key, loads) -> TimingGroup(key.first, key.second, loads.sortedBy { it.tMs }) }
        .sortedWith(compareBy({ it.scenario }, { it.commit }))

    val medianTotalMs: Double? get() = Numbers.median(data.loads.mapNotNull { it.totalMs })

    val minTotalMs: Double? get() = Numbers.min(data.loads.mapNotNull { it.totalMs })

    val maxTotalMs: Double? get() = Numbers.max(data.loads.mapNotNull { it.totalMs })

    // ---- 2. download -----------------------------------------------------------------------

    val download: DownloadStats = buildDownload()

    private fun buildDownload(): DownloadStats {
        val proxy = data.proxy
        val trace = data.gitlabTraceCalls
        val windowMs = proxy.windowMs
        val mbPerSecond = windowMs?.takeIf { it > 0 }?.let {
            proxy.bytesDown.toDouble() / 1_048_576.0 / (it / 1_000.0)
        }
        val effectiveMbPerSecond = proxy.latencyMs.takeIf { it > 0 }?.let {
            proxy.bytesDown.toDouble() / 1_048_576.0 / (it / 1_000.0)
        }
        val topPaths = proxy.rows
            .filter { it.bytesDown > 0 }
            .groupBy { it.path }
            .map { (path, rows) -> path to rows.sumOf { it.bytesDown } }
            .sortedByDescending { it.second }
            .take(5)
        return DownloadStats(
            proxyPresent = proxyPresent,
            requests = proxy.rows.size,
            bytesDown = proxy.bytesDown,
            bytesUp = proxy.bytesUp,
            artifacts = proxy.artifacts,
            errorStatuses = proxy.errorStatuses,
            faults = proxy.faults,
            authSeenCount = proxy.rows.count { it.authSeen == true },
            windowMs = windowMs,
            mbPerSecond = mbPerSecond,
            effectiveMbPerSecond = effectiveMbPerSecond,
            topPaths = topPaths,
            traceCalls = trace.size,
            traceBytes = trace.sumOf { it.longOrNull("bytes", "bytesDown") ?: 0L },
            traceErrors = trace.count { call ->
                call.stringOrNull("error") != null || (call.intOrNull("status") ?: 0) >= 400
            },
            tracePresent = trace.isNotEmpty(),
            perfGitlabCalls = sumOrNull(data.loads.map { it.gitlabCalls }),
            perfGitlabBytes = sumOrNull(data.loads.map { it.gitlabBytes }),
            perfGitlabErrors = sumOrNull(data.loads.map { it.gitlabErrors }),
        )
    }

    private fun sumOrNull(values: List<Long?>): Long? =
        if (values.any { it != null }) values.filterNotNull().sum() else null

    // ---- 3. memory -------------------------------------------------------------------------

    val memory: MemoryStats = buildMemory()

    private fun buildMemory(): MemoryStats {
        val samples = data.perfSamples
        val heapUsed = samples.mapNotNull { it.double("heapUsedMB") }
        val peakHeapRecord = samples
            .mapNotNull { record -> record.double("heapUsedMB")?.let { record.tMs to it } }
            .maxByOrNull { it.second }
        val gcMs = samples.sumOf { it.long("gcMsDelta") ?: 0L }.takeIf { samples.isNotEmpty() }
        val gcCount = samples.sumOf { it.long("gcCountDelta") ?: 0L }.takeIf { samples.isNotEmpty() }
        val cpu = samples.mapNotNull { it.double("processCpuPct") }.filter { it >= 0 }

        val covd = buildCovdMemory()
        val peaks = ArrayList<PeakOnPhase>()
        peakHeapRecord?.let { (tMs, value) ->
            peaks += attributePeak(tMs, "IDE", "heap", value)
        }
        covd.forEach { process ->
            val at = process.peakRssAtMs
            val value = process.rssMaxMb
            if (at != null && value != null) {
                peaks += attributePeak(at, "covd${process.pid?.let { " (pid $it)" }.orEmpty()}", "rss", value)
            }
        }

        return MemoryStats(
            ideSamples = samples.size,
            heapUsedMaxMb = Numbers.max(heapUsed),
            heapUsedAvgMb = Numbers.mean(heapUsed),
            heapMaxMb = Numbers.max(samples.mapNotNull { it.double("heapMaxMB") }),
            gcMs = gcMs,
            gcCount = gcCount,
            threadsMax = samples.mapNotNull { it.int("threads") }.maxOrNull(),
            cpuMaxPct = Numbers.max(cpu),
            cpuAvgPct = Numbers.mean(cpu),
            durationMs = if (samples.size < 2) null else samples.last().tMs - samples.first().tMs,
            peakHeapAtMs = peakHeapRecord?.first,
            covd = covd,
            covdPeakRssMb = Numbers.max(covd.mapNotNull { it.rssMaxMb }),
            covdPeakCpuPct = Numbers.max(covd.mapNotNull { it.cpuMaxPct }),
            phasePeaks = peaks.sortedBy { it.tMs },
        )
    }

    private fun buildCovdMemory(): List<CovdProcessMemory> {
        val fromPerf = data.covdSamples
            .groupBy { it.long("pid") }
            .map { (pid, records) ->
                val rss = records.mapNotNull { it.double("rssMb") }
                val cpu = records.mapNotNull { it.double("cpuPct") }.filter { it >= 0 }
                val peak = records
                    .mapNotNull { record -> record.double("rssMb")?.let { record.tMs to it } }
                    .maxByOrNull { it.second }
                CovdProcessMemory(
                    pid = pid,
                    samples = records.size,
                    rssMaxMb = Numbers.max(rss),
                    rssAvgMb = Numbers.mean(rss),
                    cpuMaxPct = Numbers.max(cpu),
                    threadsMax = records.mapNotNull { it.int("threads") }.maxOrNull(),
                    source = "perf covdsample",
                    peakRssAtMs = peak?.first,
                )
            }
        if (fromPerf.isNotEmpty()) return fromPerf.sortedBy { it.pid ?: Long.MAX_VALUE }

        // A run may sample the daemon only through samples.csv (`tMs,proc,pid,rssMb,cpuPct`).
        return data.samples
            .filter { it.proc.contains("covd", ignoreCase = true) }
            .groupBy { it.pid }
            .map { (pid, rows) ->
                val rss = rows.mapNotNull { it.rssMb }
                val peak = rows
                    .mapNotNull { row -> row.rssMb?.let { row.tMs to it } }
                    .maxByOrNull { it.second }
                CovdProcessMemory(
                    pid = pid,
                    samples = rows.size,
                    rssMaxMb = Numbers.max(rss),
                    rssAvgMb = Numbers.mean(rss),
                    cpuMaxPct = Numbers.max(rows.mapNotNull { it.cpuPct }),
                    threadsMax = null,
                    source = "samples.csv",
                    peakRssAtMs = peak?.first,
                )
            }
            .sortedBy { it.pid ?: Long.MAX_VALUE }
    }

    /** Finds the load whose window contains [tMs] and names the phase at that offset. */
    private fun attributePeak(tMs: Long, proc: String, metric: String, value: Double): PeakOnPhase {
        val load = data.loads
            .filter { it.totalMs != null }
            .firstOrNull { (it.tMs - (it.totalMs ?: 0.0)).toLong() <= tMs && tMs <= it.tMs }
            ?: data.loads.minByOrNull { abs(it.tMs - tMs) }?.takeIf { abs(it.tMs - tMs) <= 300_000L }
        val phase = load?.let { phaseAt(it, tMs) }.orEmpty()
        return PeakOnPhase(
            tMs = tMs,
            proc = proc,
            metric = metric,
            value = value,
            scenario = load?.scenario.orEmpty(),
            commit = load?.shortCommit.orEmpty(),
            phase = phase,
        )
    }

    private fun phaseAt(load: LoadRecord, tMs: Long): String {
        val total = load.totalMs ?: return ""
        var offset = tMs - (load.tMs - total.toLong())
        val phases = listOf(
            "resolve" to load.resolveMs,
            "download" to load.downloadMs,
            "parse" to load.parseMs,
            "merge" to load.mergeMs,
            "write" to load.writeMs,
        )
        if (phases.all { it.second == null }) return "фазы не детализированы"
        for ((name, duration) in phases) {
            if (duration == null) continue
            if (offset <= duration) return name
            offset -= duration.toLong()
        }
        return "end"
    }

    // ---- 4. cache --------------------------------------------------------------------------

    val cache: CacheStats = buildCache()

    private fun buildCache(): CacheStats {
        val cachedLoads = data.loads.filter { it.cached == true }
        val calls = sumOrNull(cachedLoads.map { it.gitlabCalls })
        // When the perf line has no `gitlabCalls`, the proxy journal still shows whether the
        // repeat load touched the network.
        val proxyDuringRepeat = cachedLoads.mapNotNull { proxyRequestsDuring(it) }
        val repeatCalls = calls ?: proxyDuringRepeat.takeIf { it.isNotEmpty() }?.sum()?.toLong()
        val cacheDir = listOf("cache", "cacheRoot", "covd-cache")
            .map { File(data.runDir, it) }
            .firstOrNull { it.isDirectory }
        var cacheBytes: Long? = null
        var cov4Files: Int? = null
        if (cacheDir != null) {
            var bytes = 0L
            var files = 0
            cacheDir.walkTopDown()
                .onEnter { it.name != "bin-covd" }
                .filter { it.isFile }
                .forEach { file ->
                    bytes += file.length()
                    if (file.name.endsWith(".cov4")) files++
                }
            cacheBytes = bytes
            cov4Files = files
        }
        val artifactsOnDisk = data.covdResponses.flatMap { saved ->
            val response = saved.response ?: return@flatMap emptyList()
            listOfNotNull(
                response.stringOrNull("cov4Path"),
                response.stringOrNull("baselineCov4Path"),
            ).mapNotNull { path ->
                val file = File(path)
                if (file.isFile) path to file.length() else null
            }
        }.distinctBy { it.first }

        return CacheStats(
            cachedLoads = cachedLoads,
            replacedCount = data.loads.count { it.replaced == true },
            repeatGitlabCalls = repeatCalls,
            repeatViolations = cachedLoads.filter {
                (it.gitlabCalls ?: 0L) > 0L || (proxyRequestsDuring(it) ?: 0) > 0
            },
            cacheDir = cacheDir,
            cacheBytes = cacheBytes,
            cov4Files = cov4Files,
            artifactsOnDisk = artifactsOnDisk,
        )
    }

    private fun proxyRequestsDuring(load: LoadRecord): Int? {
        if (!proxyPresent) return null
        val total = load.totalMs ?: return null
        val from = load.tMs - total.toLong()
        return data.proxy.rows.count { it.tMs in from..load.tMs }
    }

    // ---- 5. daemon -------------------------------------------------------------------------

    val daemon: DaemonStats = buildDaemon()

    private fun buildDaemon(): DaemonStats {
        val spawns = data.spawns.sortedBy { it.tMs }
        val counts = spawns.mapNotNull { it.str("how") }
            .groupingBy { it }
            .eachCount()
        val pids = spawns.mapNotNull { it.long("pid") }.distinct().sorted()
            .ifEmpty { data.covdSamples.mapNotNull { it.long("pid") }.distinct().sorted() }
        val instances = (spawns.mapNotNull { it.str("instance") } +
            data.snapshots.mapNotNull { it.covdInstanceId })
            .distinct()
            .sorted()
        return DaemonStats(
            spawns = spawns,
            counts = counts,
            pids = pids,
            instances = instances,
            joinedFlightLoads = data.loads.count { it.joinedFlight == true },
            snapshotInstances = data.snapshots.mapNotNull { it.covdInstanceId }.distinct().sorted(),
            snapshotPids = data.snapshots.mapNotNull { it.covdPid }.distinct().sorted(),
        )
    }

    // ---- 6. branch switches ----------------------------------------------------------------

    val switches: SwitchStats = buildSwitches()

    private fun buildSwitches(): SwitchStats {
        val switchEvents = data.scenarioEvents.filter { isSwitchEvent(it) }
        return SwitchStats(
            events = data.scenarioEvents,
            switches = switchEvents.map { event -> detectSwitch(event) },
        )
    }

    private fun isSwitchEvent(event: ScenarioEvent): Boolean {
        val phase = event.phase.lowercase()
        val text = "${event.msg} ${event.dataText}".lowercase()
        if (listOf("switch", "checkout", "head").any { phase.contains(it) }) return true
        return listOf("checkout", "switch to", "switched to", "switch branch", "head change", "head=")
            .any { text.contains(it) }
    }

    private fun detectSwitch(event: ScenarioEvent): SwitchDetection {
        val t = event.tMs ?: return SwitchDetection(event, null, null)
        val after = data.loads.filter { it.tMs >= t }
        val load = after.firstOrNull { it.state == "ready" } ?: after.firstOrNull()
        return SwitchDetection(event, load, load?.let { it.tMs - t })
    }

    // ---- 7. failures -----------------------------------------------------------------------

    val failures: FailureStats = buildFailures()

    private fun buildFailures(): FailureStats {
        val errorLoads = data.errorLoads
        val codes = errorLoads
            .map { it.code?.takeIf { code -> code.isNotBlank() } ?: "без кода" }
            .groupingBy { it }
            .eachCount()
        val retries = data.loads
            .groupBy { it.scenario to it.shortCommit }
            .values
            .sumOf { max(0, it.size - 1) }
        val balloons = data.scenarioEvents.filter { it.searchable.contains("balloon") }
        val traceErrors = data.covdResponses.sumOf { saved ->
            saved.trace?.arrayOrNull("errors")?.size ?: 0
        }
        return FailureStats(
            errorLoads = errorLoads,
            errorCodes = codes,
            retries = retries,
            balloons = balloons,
            spanFailures = data.spanFailures,
            proxyStatusErrors = data.proxy.errorStatuses,
            lastErrors = data.snapshots.filter { !it.lastError.isNullOrBlank() },
            traceErrors = traceErrors,
        )
    }
}
