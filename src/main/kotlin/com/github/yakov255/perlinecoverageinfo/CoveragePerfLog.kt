package com.github.yakov255.perlinecoverageinfo

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Dedicated, structured performance log for memory and CPU analysis.
 *
 * Writes to `~/.cache/coverage-plugin/perf/run-<timestamp>-<pid>.log`, one line per
 * event, so it never floods `idea.log` or the in-IDE Log tab. Every line is a flat
 * `key=value` list, which makes the file easy to aggregate with awk/grep.
 *
 * What is recorded:
 *  - `ev=span`: one line per instrumented operation with wall time, current-thread CPU,
 *    bytes allocated by the current thread, heap used before/after, and the GC count/ms
 *    delta — so an operation's allocation and CPU cost is attributable to a single line.
 *  - `ev=counter`: aggregated per-call metrics for high-frequency operations (e.g. one
 *    per file read) flushed once per sample tick as count/avg/max/total.
 *  - `ev=sample`: once per second: heap used/committed/max, non-heap, thread count,
 *    process CPU load, system load average, GC totals and a JVM uptime.
 *
 * All methods are allocation- and exception-safe: the logger can never break a load.
 */
object CoveragePerfLog {

    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val SAMPLE_INTERVAL_MS = 1_000L
    private const val IDLE_STOP_MS = 10L * 60L * 1_000L
    private const val RETAIN_LOGS_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val TAG = "CoveragePerf"

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    /** Toggle for tests and local diagnostics; off means every call is a cheap no-op. */
    @Volatile
    internal var enabled: Boolean =
        !"false".equals(System.getProperty("coverage.perf.log"), ignoreCase = true)

    private val logLock = Any()
    private var stream: BufferedOutputStream? = null
    private var logFile: File? = null
    private var writtenBytes = 0L
    private var truncated = false

    private val depth = ThreadLocal.withInitial { 0 }
    private val threadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    private val osBean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()

    private val counters = ConcurrentHashMap<String, Counter>()

    @Volatile
    private var lastActivityMs: Long = 0L

    @Volatile
    private var sampler: Thread? = null

    /** File currently being written, or `null` when disabled/not yet started. */
    fun currentFile(): File? = logFile

    /** Directory that holds the per-run performance logs. */
    fun directory(): File = perfDir()

    fun isEnabled(): Boolean = enabled

    /** Records a point event with arbitrary key/value details. */
    fun event(name: String, vararg details: Pair<String, Any?>) {
        if (!enabled) return
        try {
            writeLine(
                buildList {
                    add("ev=event")
                    add("name=${sanitize(name)}")
                    add("th=${threadName()}")
                    add("heap=${fmt(heapUsedMb())}")
                    details.forEach { (k, v) -> add("${sanitize(k)}=${sanitize(v)}") }
                }
            )
            touch()
        } catch (_: Throwable) {
            // Never break the caller because of diagnostics.
        }
    }

    /**
     * Measures wall time, thread CPU time, thread allocation and heap deltas for [block].
     * Nested spans are supported; [depth] shows the nesting level in the log.
     */
    fun <T> span(name: String, vararg details: Pair<String, Any?>, block: () -> T): T {
        if (!enabled) return block()

        val level = depth.get()
        val start = Measure(
            wallStartNanos = System.nanoTime(),
            cpuStartNanos = threadCpuNanos(),
            allocStartBytes = threadAllocatedBytes(),
            heapStartMb = heapUsedMb(),
            gcStartCount = gcCount(),
            gcStartTimeMs = gcTimeMs(),
        )
        depth.set(level + 1)
        try {
            writeLine(
                buildList {
                    add("ev=span")
                    add("name=${sanitize(name)}")
                    add("ph=start")
                    add("d=$level")
                    add("th=${threadName()}")
                    add("heap=${fmt(start.heapStartMb)}")
                    details.forEach { (k, v) -> add("${sanitize(k)}=${sanitize(v)}") }
                }
            )
            touch()
        } catch (_: Throwable) {
            // Never break the caller because of diagnostics.
        }

        var ok = true
        try {
            return block()
        } catch (e: Throwable) {
            ok = false
            logFailure(name, level, e)
            throw e
        } finally {
            depth.set(level)
            if (ok) logSpanEnd(name, level, start, details)
        }
    }

    private fun logSpanEnd(name: String, level: Int, start: Measure, details: Array<out Pair<String, Any?>>) {
        try {
            val wallNanos = System.nanoTime() - start.wallStartNanos
            val allocEnd = threadAllocatedBytes()
            val allocBytes = if (start.allocStartBytes >= 0 && allocEnd >= 0) allocEnd - start.allocStartBytes else -1L
            val cpuNanos = threadCpuNanos() - start.cpuStartNanos
            writeLine(
                buildList {
                    add("ev=span")
                    add("name=${sanitize(name)}")
                    add("ph=end")
                    add("ok=true")
                    add("d=$level")
                    add("th=${threadName()}")
                    add("wallMs=${fmt(wallNanos / 1_000_000.0)}")
                    add("cpuMs=${fmt(cpuNanos / 1_000_000.0)}")
                    add("allocMB=${if (allocBytes >= 0) fmt(allocBytes / 1_048_576.0) else "n/a"}")
                    add("heapBeforeMB=${fmt(start.heapStartMb)}")
                    add("heapAfterMB=${fmt(heapUsedMb())}")
                    add("gcCount=${gcCount() - start.gcStartCount}")
                    add("gcMs=${gcTimeMs() - start.gcStartTimeMs}")
                    details.forEach { (k, v) -> add("${sanitize(k)}=${sanitize(v)}") }
                }
            )
            touch()
        } catch (_: Throwable) {
            // Never break the caller because of diagnostics.
        }
    }

    /**
     * Aggregated metric for high-frequency operations. Values are flushed by the sampler
     * as `ev=counter` lines (count/avgMs/maxMs/totalAllocMB) and then reset.
     */
    fun count(name: String, wallNanos: Long, allocBytes: Long) {
        if (!enabled) return
        try {
            counters.computeIfAbsent(name) { Counter() }.apply {
                calls.increment()
                totalWallNanos.add(wallNanos)
                if (wallNanos > maxWallNanos) maxWallNanos = wallNanos
                totalAllocBytes.add(allocBytes.coerceAtLeast(0))
            }
            touch()
        } catch (_: Throwable) {
            // Never break the caller because of diagnostics.
        }
    }

    /** Convenience wrapper around [count] measuring a block. */
    fun <T> counted(name: String, block: () -> T): T {
        if (!enabled) return block()
        val wallStart = System.nanoTime()
        val allocStart = threadAllocatedBytes()
        try {
            return block()
        } finally {
            val alloc = threadAllocatedBytes().let { if (allocStart >= 0 && it >= 0) it - allocStart else -1L }
            count(name, System.nanoTime() - wallStart, alloc)
        }
    }

    /** Forces the current process to a heap snapshot line, useful at phase boundaries. */
    fun heapSnapshot(name: String) {
        if (!enabled) return
        try {
            val runtime = Runtime.getRuntime()
            writeLine(
                listOf(
                    "ev=heap",
                    "name=${sanitize(name)}",
                    "usedMB=${fmt(heapUsedMb())}",
                    "committedMB=${fmt((runtime.totalMemory()) / 1_048_576.0)}",
                    "maxMB=${fmt(runtime.maxMemory() / 1_048_576.0)}",
                    "nonHeapMB=${fmt(nonHeapUsedMb())}",
                    "threads=${ManagementFactory.getThreadMXBean().threadCount}",
                    "gcCount=${gcCount()}",
                    "gcMs=${gcTimeMs()}",
                )
            )
            touch()
        } catch (_: Throwable) {
            // Never break the caller because of diagnostics.
        }
    }

    private fun logFailure(name: String, level: Int, error: Throwable) {
        try {
            writeLine(
                listOf(
                    "ev=span",
                    "name=${sanitize(name)}",
                    "ph=end",
                    "ok=false",
                    "d=$level",
                    "th=${threadName()}",
                    "error=${sanitize(error::class.java.simpleName)}",
                    "message=${sanitize(error.message)}",
                )
            )
            touch()
        } catch (_: Throwable) {
            // Never break the caller because of diagnostics.
        }
    }

    // --- internals -------------------------------------------------------------------

    private fun touch() {
        lastActivityMs = System.currentTimeMillis()
        ensureSampler()
    }

    private fun writeLine(parts: List<String>): Boolean {
        if (!enabled) return false
        synchronized(logLock) {
            if (truncated) return false
            val out = stream ?: openStream() ?: return false
            val line = buildString {
                append(System.currentTimeMillis())
                append(' ')
                append(TIME_FORMAT.format(Instant.now()))
                append(' ')
                parts.forEach {
                    append(it)
                    append(' ')
                }
                append('\n')
            }.toByteArray(Charsets.UTF_8)
            if (writtenBytes + line.size > MAX_BYTES) {
                truncated = true
                out.write(
                    "${System.currentTimeMillis()} ${TIME_FORMAT.format(Instant.now())} ev=truncated name=size-limit reached=$writtenBytes\n"
                        .toByteArray(Charsets.UTF_8)
                )
                out.flush()
                return false
            }
            out.write(line)
            out.flush()
            writtenBytes += line.size
            return true
        }
    }

    private fun openStream(): BufferedOutputStream? {
        return try {
            val dir = perfDir()
            dir.mkdirs()
            pruneOldLogs(dir)
            val file = File(dir, "run-${timestampForFile()}-${ProcessHandle.current().pid()}.log")
            logFile = file
            BufferedOutputStream(FileOutputStream(file, true), 64 * 1024).also {
                stream = it
                CoverageLog.get(CoveragePerfLog::class.java)
                    .info("Coverage perf log: writing to ${file.absolutePath}")
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun perfDir(): File {
        val override = System.getProperty("coverage.perf.dir")
        if (!override.isNullOrBlank()) return File(override)
        return File(System.getProperty("user.home"), ".cache/coverage-plugin/perf")
    }

    /** Keeps the newest logs only; the directory otherwise grows by a file per IDE run. */
    private fun pruneOldLogs(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - RETAIN_LOGS_MS
            dir.listFiles { f -> f.isFile && f.name.startsWith("run-") && f.name.endsWith(".log") }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        } catch (_: Throwable) {
            // Diagnostics must never break the caller.
        }
    }

    private fun timestampForFile(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())

    private fun ensureSampler() {
        if (sampler != null) return
        synchronized(logLock) {
            if (sampler != null) return
            val thread = Thread({ sampleLoop() }, "CoveragePerfSampler")
            thread.isDaemon = true
            thread.priority = Thread.MIN_PRIORITY
            sampler = thread
            thread.start()
        }
    }

    private fun sampleLoop() {
        var lastGcCount = gcCount()
        var lastGcTime = gcTimeMs()
        var lastCpuNanos = osBean?.processCpuTime ?: -1L
        var lastCpuWall = System.nanoTime()

        while (true) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }

            try {
                if (System.currentTimeMillis() - lastActivityMs > IDLE_STOP_MS) {
                    synchronized(logLock) { sampler = null }
                    return
                }

                val runtime = Runtime.getRuntime()
                val gcCountNow = gcCount()
                val gcTimeNow = gcTimeMs()
                val cpuNanosNow = osBean?.processCpuTime ?: -1L
                val cpuWallNow = System.nanoTime()
                val cpuPct = if (lastCpuNanos >= 0 && cpuNanosNow >= 0 && cpuWallNow > lastCpuWall) {
                    (cpuNanosNow - lastCpuNanos).toDouble() / (cpuWallNow - lastCpuWall) * 100.0
                } else {
                    -1.0
                }

                writeLine(
                    buildList {
                        add("ev=sample")
                        add("heapUsedMB=${fmt(heapUsedMb())}")
                        add("heapCommittedMB=${fmt(runtime.totalMemory() / 1_048_576.0)}")
                        add("heapMaxMB=${fmt(runtime.maxMemory() / 1_048_576.0)}")
                        add("nonHeapMB=${fmt(nonHeapUsedMb())}")
                        add("threads=${ManagementFactory.getThreadMXBean().threadCount}")
                        add("processCpuPct=${fmt(cpuPct)}")
                        add("loadAvg=%.2f".format(Locale.ROOT, osBean?.systemLoadAverage ?: -1.0))
                        add("gcCountDelta=${gcCountNow - lastGcCount}")
                        add("gcMsDelta=${gcTimeNow - lastGcTime}")
                        add("uptimeMin=${ManagementFactory.getRuntimeMXBean().uptime / 60_000}")
                    }
                )
                flushCounters()

                lastGcCount = gcCountNow
                lastGcTime = gcTimeNow
                lastCpuNanos = cpuNanosNow
                lastCpuWall = cpuWallNow
            } catch (_: Throwable) {
                // Keep sampling; a single failure must not stop the diagnostics.
            }
        }
    }

    private fun flushCounters() {
        if (counters.isEmpty()) return
        val snapshot = counters.entries.toList()
        snapshot.forEach { (name, counter) -> counters.remove(name, counter) }

        snapshot.forEach { (name, counter) ->
            val calls = counter.calls.sum()
            if (calls == 0L) return@forEach
            val totalWall = counter.totalWallNanos.sum()
            writeLine(
                listOf(
                    "ev=counter",
                    "name=${sanitize(name)}",
                    "calls=$calls",
                    "avgMs=${fmt(totalWall.toDouble() / calls / 1_000_000.0)}",
                    "maxMs=${fmt(counter.maxWallNanos / 1_000_000.0)}",
                    "totalWallMs=${fmt(totalWall / 1_000_000.0)}",
                    "totalAllocMB=${fmt(counter.totalAllocBytes.sum() / 1_048_576.0)}",
                )
            )
        }
    }

    private fun threadName(): String = sanitize(Thread.currentThread().name)

    private fun threadCpuNanos(): Long = try {
        ManagementFactory.getThreadMXBean().currentThreadCpuTime
    } catch (_: Throwable) {
        -1L
    }

    private fun threadAllocatedBytes(): Long {
        val bean = threadBean ?: return -1L
        return try {
            if (!bean.isThreadAllocatedMemorySupported) return -1L
            if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
            bean.getThreadAllocatedBytes(Thread.currentThread().id)
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun heapUsedMb(): Double {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576.0
    }

    private fun nonHeapUsedMb(): Double {
        return try {
            ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage.used / 1_048_576.0
        } catch (_: Throwable) {
            -1.0
        }
    }

    private fun gcCount(): Long = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }

    private fun gcTimeMs(): Long = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }

    private fun fmt(value: Double): String = "%.3f".format(Locale.ROOT, value)

    private fun sanitize(value: Any?): String {
        val text = value?.toString() ?: "null"
        if (text.isEmpty()) return "-"
        val builder = StringBuilder(text.length)
        text.forEach { ch ->
            builder.append(
                when {
                    ch == ' ' || ch == '=' || ch == '\n' || ch == '\r' || ch == '\t' -> '_'
                    else -> ch
                }
            )
        }
        return if (builder.length > 300) builder.substring(0, 300) else builder.toString()
    }

    private class Counter {
        val calls = LongAdder()
        val totalWallNanos = LongAdder()
        val totalAllocBytes = LongAdder()

        @Volatile
        var maxWallNanos: Long = 0L
    }

    private class Measure(
        val wallStartNanos: Long,
        val cpuStartNanos: Long,
        val allocStartBytes: Long,
        val heapStartMb: Double,
        val gcStartCount: Long,
        val gcStartTimeMs: Long,
    )
}
