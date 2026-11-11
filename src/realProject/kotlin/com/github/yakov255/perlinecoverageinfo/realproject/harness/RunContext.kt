package com.github.yakov255.perlinecoverageinfo.realproject.harness

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One harness run: its id, its output directory and the JSONL streams a scenario writes into it.
 *
 * Layout (`build/real-project/<run-id>/` by default, see `realProject.outputDir`):
 *
 * ```
 * scenarios.jsonl    {"t","scenario","phase","msg","data"}
 * snapshots.jsonl    bridge snapshots with "tMs", polled every 500 ms
 * perf/              copies of the IDE perf logs
 * covd-responses/    copies of the saved covd answers
 * samples.csv        ProcSampler output
 * machine.json       CPU/RAM/OS/JVM facts, no credentials
 * ```
 */
class RunContext private constructor(
    val runId: String,
    val dir: File,
) : AutoCloseable {

    val scenariosFile: File = File(dir, "scenarios.jsonl")
    val snapshotsFile: File = File(dir, "snapshots.jsonl")
    val machineFile: File = File(dir, "machine.json")
    val perfDir: File = File(dir, "perf")
    val covdResponsesDir: File = File(dir, "covd-responses")
    val samplesFile: File = File(dir, "samples.csv")

    private val writeLock = Any()
    private val polling = AtomicBoolean(false)
    private var poller: Thread? = null

    init {
        dir.mkdirs()
        scenariosFile.createNewFile()
        snapshotsFile.createNewFile()
        perfDir.mkdirs()
        covdResponsesDir.mkdirs()
    }

    /** Appends one scenario event. [data] is free-form; keep it small and secret-free. */
    fun event(scenario: String, phase: String, msg: String = "", data: JsonObject = JsonObject(emptyMap())) {
        val row = buildJsonObject {
            put("t", System.currentTimeMillis())
            put("scenario", scenario)
            put("phase", phase)
            put("msg", msg)
            put("data", data)
        }
        appendLine(scenariosFile, row)
    }

    /** Appends one bridge snapshot with its own sampling timestamp. */
    fun snapshot(snapshot: BridgeSnapshot, atMs: Long = System.currentTimeMillis()) {
        val row = buildJsonObject {
            put("tMs", atMs)
            for ((key, value) in snapshot.raw) put(key, value)
        }
        appendLine(snapshotsFile, row)
    }

    /**
     * Polls the bridge every [intervalMs] on a daemon thread and appends a row per snapshot.
     * Returns the handle that stops the poller; closing it also flushes nothing (writes are
     * atomic appends).
     */
    fun startSnapshotPolling(
        reader: () -> BridgeSnapshot?,
        intervalMs: Long = SNAPSHOT_INTERVAL_MS,
    ): AutoCloseable {
        if (!polling.compareAndSet(false, true)) {
            return AutoCloseable { stopSnapshotPolling() }
        }
        poller = Thread({
            while (polling.get()) {
                try {
                    reader()?.let { snapshot(it) }
                } catch (_: Throwable) {
                    // A killed IDE or a half-written snapshot is expected during a scenario.
                }
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "rp-snapshot-poller").apply {
            isDaemon = true
            start()
        }
        return AutoCloseable { stopSnapshotPolling() }
    }

    fun stopSnapshotPolling() {
        polling.set(false)
        poller?.join(5_000)
        poller = null
    }

    /** Copies `*.log` files of a perf directory; returns the copied names. */
    fun copyPerfLogs(source: File?): List<String> = copyFiles(source, perfDir) { it.endsWith(".log") }

    /** Copies the saved covd answers (`<seq>-<time>-<kind>-<state>.json`). */
    fun copyCovdResponses(source: File?): List<String> =
        copyFiles(source, covdResponsesDir) { it.endsWith(".json") }

    /** Copies [source]'s matching files into [target]; no-op for a missing source. */
    fun copyFiles(source: File?, target: File, filter: (String) -> Boolean = { true }): List<String> {
        if (source == null || !source.isDirectory) return emptyList()
        target.mkdirs()
        val copied = mutableListOf<String>()
        for (file in source.listFiles().orEmpty()) {
            if (!file.isFile || !filter(file.name)) continue
            Files.copy(file.toPath(), File(target, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            copied += file.name
        }
        return copied.sorted()
    }

    /**
     * Machine facts for the report. Explicitly no environment variables and no tokens.
     */
    fun writeMachineInfo(extra: Map<String, String> = emptyMap()): JsonObject {
        val cpu = cpuModel()
        val info = buildJsonObject {
            put("runId", runId)
            put("startedAt", Instant.now().toString())
            put("host", runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown"))
            put("os", System.getProperty("os.name").orEmpty())
            put("osVersion", System.getProperty("os.version").orEmpty())
            put("arch", System.getProperty("os.arch").orEmpty())
            put("cpuModel", cpu)
            put("cpuCores", Runtime.getRuntime().availableProcessors())
            put("memTotalMb", memTotalMb())
            put("javaVersion", System.getProperty("java.version").orEmpty())
            put("javaVendor", System.getProperty("java.vendor").orEmpty())
            put("jvmMaxHeapMb", Runtime.getRuntime().maxMemory() / MB)
            for ((key, value) in extra) put(key, value)
        }
        machineFile.writeText(info.toString())
        return info
    }

    override fun close() = stopSnapshotPolling()

    companion object {
        const val SNAPSHOT_INTERVAL_MS = 500L
        private const val MB = 1_048_576L
        private val RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** `build/real-project` unless the realProjectTest task passes its own output directory. */
        fun defaultOutputRoot(): File {
            val configured = System.getProperty("realProject.outputDir")
            return if (configured.isNullOrBlank()) File("build/real-project") else File(configured)
        }

        /** A sortable, filesystem-safe run id: `<utc timestamp>[-<label>]`. */
        fun newRunId(label: String? = null, atMs: Long = System.currentTimeMillis()): String {
            val stamp = RUN_ID_FORMAT.format(
                Instant.ofEpochMilli(atMs).atZone(ZoneId.of("UTC")),
            )
            val safeLabel = label
                ?.lowercase(Locale.ROOT)
                ?.replace(Regex("[^a-z0-9._-]+"), "-")
                ?.trim('-')
                ?.take(40)
                ?.takeIf { it.isNotEmpty() }
            return if (safeLabel == null) stamp else "$stamp-$safeLabel"
        }

        fun create(
            label: String? = null,
            outputRoot: File = defaultOutputRoot(),
            atMs: Long = System.currentTimeMillis(),
        ): RunContext {
            val runId = newRunId(label, atMs)
            val dir = File(outputRoot, runId)
            check(!dir.exists()) { "run directory already exists: $dir" }
            return RunContext(runId, dir)
        }

        /** Reuses an existing run directory (a scenario appending to a prepared run). */
        fun at(runId: String, outputRoot: File = defaultOutputRoot()): RunContext =
            RunContext(runId, File(outputRoot, runId))

        private fun appendLine(file: File, element: JsonElement) {
            val line = element.toString() + "\n"
            synchronized(RunContext::class.java) {
                Files.write(
                    file.toPath(),
                    line.toByteArray(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
        }
    }

    private fun appendLine(file: File, element: JsonElement) = Companion.appendLine(file, element)

    private fun cpuModel(): String {
        val file = File("/proc/cpuinfo")
        if (!file.isFile) return System.getProperty("os.arch").orEmpty()
        return runCatching {
            file.useLines { lines ->
                lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
            }
        }.getOrNull() ?: System.getProperty("os.arch").orEmpty()
    }

    private fun memTotalMb(): Long? {
        val file = File("/proc/meminfo")
        if (!file.isFile) return null
        return runCatching {
            file.useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                    ?.toLongOrNull()
            }
        }.getOrNull()?.div(1024)
    }
}

/** `JsonObject` from string pairs, for scenario data. */
internal fun jsonObjectOf(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
    for ((key, value) in entries) put(key, JsonPrimitive(value))
}
