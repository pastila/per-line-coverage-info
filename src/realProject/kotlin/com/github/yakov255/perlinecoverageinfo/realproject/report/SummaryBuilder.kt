package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `summary.json`: the same numbers as `report.md` in machine-readable form, including the
 * secret-scan result. A missing value is written as `null`, never omitted, so consumers can tell
 * "no data" from "key renamed".
 */
internal object SummaryBuilder {

    fun build(runDir: File, analysis: Analysis, findings: List<SecretFinding>, generatedAtMs: Long): JsonObject {
        val data = analysis.data
        val download = analysis.download
        val memory = analysis.memory
        val cache = analysis.cache
        val daemon = analysis.daemon

        return buildJsonObject {
            put("runDir", JsonPrimitive(runDir.absolutePath))
            put("generatedAt", JsonPrimitive(generatedAtMs))
            put("generatedAtIso", JsonPrimitive(Fmt.iso(generatedAtMs)))

            put(
                "inputs",
                buildJsonObject {
                    put("perfLogs", data.perfLogs.size)
                    put("perfLines", data.perfLogs.sumOf { it.lines })
                    put("malformedPerfLines", data.malformedPerfLines)
                    put("proxyRows", data.proxy.rows.size)
                    put("malformedProxyRows", data.malformedProxyRows)
                    put("covdResponses", data.covdResponses.size)
                    put("samples", data.samples.size)
                    put("snapshots", data.snapshots.size)
                    put("scenarios", data.scenarioEvents.size)
                    put("loads", data.loads.size)
                },
            )

            put(
                "loads",
                buildJsonObject {
                    put("count", data.loads.size)
                    put(
                        "byState",
                        buildJsonObject {
                            data.loads.groupingBy { it.state.ifBlank { "unknown" } }.eachCount()
                                .toSortedMap()
                                .forEach { (state, count) -> put(state, count) }
                        },
                    )
                },
            )

            put(
                "timing",
                buildJsonObject {
                    putNullable("medianTotalMs", analysis.medianTotalMs)
                    putNullable("minTotalMs", analysis.minTotalMs)
                    putNullable("maxTotalMs", analysis.maxTotalMs)
                    put(
                        "byScenario",
                        buildJsonArray {
                            analysis.timingGroups.forEach { group ->
                                add(
                                    buildJsonObject {
                                        put("scenario", group.scenario)
                                        put("commit", group.commit)
                                        put("count", group.count)
                                        putNullable("medianTotalMs", group.medianTotalMs)
                                        putNullable("minTotalMs", group.minTotalMs)
                                        putNullable("maxTotalMs", group.maxTotalMs)
                                        putNullable(
                                            "medianDownloadMs",
                                            Numbers.median(group.loads.mapNotNull { it.downloadMs }),
                                        )
                                    }
                                )
                            }
                        },
                    )
                },
            )

            put(
                "download",
                buildJsonObject {
                    put("proxyPresent", download.proxyPresent)
                    put("requests", download.requests)
                    put("bytesDown", download.bytesDown)
                    put("bytesUp", download.bytesUp)
                    put("artifacts", download.artifacts)
                    put(
                        "errorStatuses",
                        buildJsonObject {
                            download.errorStatuses.entries.sortedBy { it.key }
                                .forEach { (status, count) -> put(status.toString(), count) }
                        },
                    )
                    put(
                        "faults",
                        buildJsonObject {
                            download.faults.entries.sortedBy { it.key }
                                .forEach { (fault, count) -> put(fault, count) }
                        },
                    )
                    put("authSeen", download.authSeenCount)
                    putNullable("windowMs", download.windowMs)
                    putNullable("mbPerSecond", download.mbPerSecond)
                    putNullable("effectiveMbPerSecond", download.effectiveMbPerSecond)
                    put("traceCalls", download.traceCalls)
                    put("traceBytes", download.traceBytes)
                    put("traceErrors", download.traceErrors)
                    putNullable("gitlabCallsFromPerf", download.perfGitlabCalls)
                    putNullable("gitlabBytesFromPerf", download.perfGitlabBytes)
                    putNullable("gitlabErrorsFromPerf", download.perfGitlabErrors)
                },
            )

            put(
                "memory",
                buildJsonObject {
                    put("ideSamples", memory.ideSamples)
                    putNullable("ideHeapMaxMb", memory.heapUsedMaxMb)
                    putNullable("ideHeapAvgMb", memory.heapUsedAvgMb)
                    putNullable("ideHeapLimitMb", memory.heapMaxMb)
                    putNullable("ideGcMs", memory.gcMs)
                    putNullable("ideGcCount", memory.gcCount)
                    putNullable("ideCpuMaxPct", memory.cpuMaxPct)
                    putNullable("ideCpuAvgPct", memory.cpuAvgPct)
                    putNullable("covdPeakRssMb", memory.covdPeakRssMb)
                    putNullable("covdPeakCpuPct", memory.covdPeakCpuPct)
                    put(
                        "covdProcesses",
                        buildJsonArray {
                            memory.covd.forEach { process ->
                                add(
                                    buildJsonObject {
                                        putNullable("pid", process.pid)
                                        put("source", process.source)
                                        put("samples", process.samples)
                                        putNullable("rssMaxMb", process.rssMaxMb)
                                        putNullable("rssAvgMb", process.rssAvgMb)
                                        putNullable("cpuMaxPct", process.cpuMaxPct)
                                    }
                                )
                            }
                        },
                    )
                    put(
                        "peaks",
                        buildJsonArray {
                            memory.phasePeaks.forEach { peak ->
                                add(
                                    buildJsonObject {
                                        put("tMs", peak.tMs)
                                        put("proc", peak.proc)
                                        put("metric", peak.metric)
                                        put("value", peak.value)
                                        put("scenario", peak.scenario)
                                        put("commit", peak.commit)
                                        put("phase", peak.phase)
                                    }
                                )
                            }
                        },
                    )
                },
            )

            put(
                "cache",
                buildJsonObject {
                    put("cached", cache.cachedLoads.size)
                    put("replaced", cache.replacedCount)
                    put("repeatGitlabCalls", cache.repeatGitlabCalls)
                    put("repeatViolations", cache.repeatViolations.size)
                    put("cacheDir", cache.cacheDir?.absolutePath?.let(::JsonPrimitive) ?: JsonNull)
                    putNullable("cacheBytes", cache.cacheBytes)
                    putNullable("cov4Files", cache.cov4Files)
                },
            )

            put(
                "daemon",
                buildJsonObject {
                    put("spawn", daemon.counts["spawn"] ?: 0)
                    put("reuse", daemon.counts["reuse"] ?: 0)
                    put("restart", daemon.counts["restart"] ?: 0)
                    put("uniquePids", daemon.pids.size)
                    put("uniqueInstances", daemon.instances.size)
                    put("joinedFlight", daemon.joinedFlightLoads)
                    put(
                        "pids",
                        buildJsonArray { daemon.pids.forEach { add(JsonPrimitive(it)) } },
                    )
                    put(
                        "instances",
                        buildJsonArray { daemon.instances.forEach { add(JsonPrimitive(it)) } },
                    )
                },
            )

            put(
                "switches",
                buildJsonObject {
                    put("events", analysis.switches.events.size)
                    put("detected", analysis.switches.switches.size)
                    put(
                        "delaysMs",
                        buildJsonArray {
                            analysis.switches.switches.mapNotNull { it.delayMs }
                                .sorted()
                                .forEach { add(JsonPrimitive(it)) }
                        },
                    )
                },
            )

            put(
                "failures",
                buildJsonObject {
                    put(
                        "errorCodes",
                        buildJsonObject {
                            analysis.failures.errorCodes.entries.sortedBy { it.key }
                                .forEach { (code, count) -> put(code, count) }
                        },
                    )
                    put("retries", analysis.failures.retries)
                    put("balloons", analysis.failures.balloons.size)
                    put("spanFailures", analysis.failures.spanFailures.size)
                    put("traceErrors", analysis.failures.traceErrors)
                    put("lastErrors", analysis.failures.lastErrors.size)
                },
            )

            put(
                "secretFindings",
                buildJsonArray {
                    findings.forEach { finding ->
                        add(
                            buildJsonObject {
                                put("file", finding.file)
                                put("line", finding.line)
                                put("type", finding.type)
                            }
                        )
                    }
                },
            )
        }
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Number?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Long?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Boolean?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }
}
