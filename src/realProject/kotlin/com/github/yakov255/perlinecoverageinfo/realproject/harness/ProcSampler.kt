package com.github.yakov255.perlinecoverageinfo.realproject.harness

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1 Hz resource sampler for the processes a scenario drives: the IDE instances and the shared
 * `covd` daemon.
 *
 * It reads Linux `/proc` directly (no external tools): RSS from `/proc/<pid>/status`, CPU from the
 * utime/stime counters of `/proc/<pid>/stat` between two samples, thread count from `Threads`.
 * Rows are appended to [csvFile] as `tMs,proc,pid,rssMb,cpuPct`.
 *
 * `covd` is found by its command line: it is started with `--cache-dir <cacheRoot>`, which is
 * unique per harness run, so a foreign daemon of the user can never be sampled, stopped or
 * continued by mistake.
 */
class ProcSampler(
    private val csvFile: File,
    private val cacheRoot: File,
    private val procRoot: File = File("/proc"),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val hz: Long = DEFAULT_CLOCK_TICKS_PER_SECOND,
) : AutoCloseable {

    private val processes = ConcurrentHashMap<String, Long>()
    private val previous = ConcurrentHashMap<Long, CpuState>()
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    private var thread: Thread? = null

    init {
        csvFile.parentFile?.mkdirs()
        csvFile.writeText(CSV_HEADER + "\n")
    }

    /** Registers [pid] under a short label (`ide-1`, `ide-2`, `covd`). */
    fun add(label: String, pid: Long) {
        require(label.isNotBlank()) { "label is required" }
        processes[label] = pid
        previous.remove(pid)
    }

    fun remove(label: String) {
        processes.remove(label)?.let { previous.remove(it) }
    }

    fun labelOf(pid: Long): String? = processes.entries.firstOrNull { it.value == pid }?.key

    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop(intervalMs) }, "rp-proc-sampler").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.join(5_000)
        thread = null
    }

    override fun close() = stop()

    private fun loop(intervalMs: Long) {
        while (running.get()) {
            try {
                sampleOnce()
            } catch (_: Throwable) {
                // Sampling must never break a scenario: a vanished process is normal.
            }
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Takes one snapshot of every registered process and appends the rows. */
    fun sampleOnce(): List<Sample> {
        val now = clockMs()
        val samples = mutableListOf<Sample>()
        for ((label, pid) in processes) {
            val sample = sampleProcess(label, pid, now) ?: continue
            samples += sample
        }
        if (samples.isNotEmpty()) {
            synchronized(writeLock) {
                csvFile.appendText(samples.joinToString("\n") { formatRow(it) } + "\n")
            }
        }
        return samples
    }

    private fun sampleProcess(label: String, pid: Long, now: Long): Sample? {
        val stat = readProcFile(pid, "stat") ?: return null
        val status = readProcFile(pid, "status") ?: return null
        val counters = parseStatCounters(stat) ?: return null
        val rssKb = parseRssKb(status) ?: return null
        val cpuPct = cpuPercent(previous[pid], counters, now, hz)
        previous[pid] = CpuState(counters, now)
        return Sample(
            tMs = now,
            proc = label,
            pid = pid,
            rssMb = rssKb / 1024.0,
            cpuPct = cpuPct,
            threads = parseThreads(status),
            state = parseState(stat),
        )
    }

    private fun readProcFile(pid: Long, name: String): String? {
        val file = File(File(procRoot, pid.toString()), name)
        return runCatching { file.readText() }.getOrNull()
    }

    // ---- covd discovery -----------------------------------------------------------------

    /** PIDs whose command line names `covd` and this harness `--cache-dir`. */
    fun findCovdPids(): List<Long> = findCovdPids(procRoot, cacheRoot)

    /**
     * Sends SIGSTOP to the scenarios' own covd. Returns the PID, or null when no daemon is
     * running. A PID that is not a covd of this cache root is never signalled.
     */
    fun sigstopCovd(): Long? = covdPidOrNull("sigstop")?.also { signal(it, "STOP") }

    /** Sends SIGCONT after [sigstopCovd]. */
    fun sigcontCovd(): Long? = covdPidOrNull("sigcont")?.also { signal(it, "CONT") }

    private fun covdPidOrNull(action: String): Long? {
        val pids = findCovdPids()
        if (pids.isEmpty()) return null
        check(pids.size == 1) {
            "cannot $action: ${pids.size} covd processes share $cacheRoot: $pids"
        }
        return pids.single()
    }

    private fun signal(pid: Long, signal: String) {
        check(commandLineContains(pid, cacheRoot.absolutePath, procRoot)) {
            "refusing to signal $pid: its command line does not name $cacheRoot"
        }
        val exit = ProcessBuilder("kill", "-$signal", pid.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        check(exit == 0) { "kill -$signal $pid failed with exit code $exit" }
    }

    data class Sample(
        val tMs: Long,
        val proc: String,
        val pid: Long,
        val rssMb: Double,
        val cpuPct: Double,
        val threads: Int? = null,
        val state: String? = null,
    ) {
        /** Header format of `samples.csv`. */
        fun toCsvRow(): String = formatRow(this)
    }

    data class CpuState(val counters: StatCounters, val atMs: Long)

    data class StatCounters(val utimeTicks: Long, val stimeTicks: Long) {
        val totalTicks: Long get() = utimeTicks + stimeTicks
    }

    companion object {
        const val CSV_HEADER = "tMs,proc,pid,rssMb,cpuPct"
        const val DEFAULT_INTERVAL_MS = 1_000L

        /**
         * Linux `USER_HZ` is 100 on every supported distribution; `/proc` has no runtime API
         * for it, and `getconf CLK_TCK` would shell out for a constant.
         */
        const val DEFAULT_CLOCK_TICKS_PER_SECOND = 100L

        /**
         * Reads the utime/stime fields of `/proc/<pid>/stat`. The `comm` field may contain spaces
         * and parentheses, so the fields are counted from the LAST `)`, not by splitting the line.
         */
        fun parseStatCounters(stat: String): StatCounters? {
            val close = stat.lastIndexOf(')')
            if (close < 0 || close + 2 >= stat.length) return null
            // After ") " the first field is state (field 3); utime is field 14, stime field 15.
            val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
            if (fields.size < 13) return null
            val utime = fields[11].toLongOrNull() ?: return null
            val stime = fields[12].toLongOrNull() ?: return null
            return StatCounters(utime, stime)
        }

        /** `state` (field 3) of `/proc/<pid>/stat`: R, S, D, Z, T... */
        fun parseState(stat: String): String? {
            val close = stat.lastIndexOf(')')
            if (close < 0 || close + 2 >= stat.length) return null
            return stat.substring(close + 2).trim().split(Regex("\\s+")).firstOrNull()
        }

        fun parseRssKb(status: String): Long? = statusValue(status, "VmRSS")?.let { value ->
            value.split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
        }

        fun parseThreads(status: String): Int? =
            statusValue(status, "Threads")?.trim()?.toIntOrNull()

        private fun statusValue(status: String, key: String): String? =
            status.lineSequence()
                .firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')
                ?.trim()

        /** CPU percentage between two samples; null for the first sample of a process. */
        internal fun cpuPercent(previous: CpuState?, current: StatCounters, nowMs: Long, hz: Long = DEFAULT_CLOCK_TICKS_PER_SECOND): Double {
            if (previous == null) return 0.0
            val elapsedMs = nowMs - previous.atMs
            if (elapsedMs <= 0) return 0.0
            val ticks = current.totalTicks - previous.counters.totalTicks
            if (ticks < 0) return 0.0
            val seconds = elapsedMs / 1000.0
            return ticks.toDouble() / hz / seconds * 100.0
        }

        fun formatRow(sample: Sample): String = String.format(
            Locale.ROOT,
            "%d,%s,%d,%.1f,%.1f",
            sample.tMs,
            sample.proc,
            sample.pid,
            sample.rssMb,
            sample.cpuPct,
        )

        /** `/proc/<pid>/cmdline` uses NUL separators; a plain string is easier to match. */
        fun normalizeCmdline(raw: String): String = raw.replace('\u0000', ' ').trim()

        fun isCovdCmdline(cmdline: String, cacheRoot: String): Boolean {
            val text = normalizeCmdline(cmdline)
            if (!text.contains("covd")) return false
            val marker = "--cache-dir"
            val index = text.indexOf(marker)
            if (index < 0) return false
            val rest = text.substring(index + marker.length).trimStart()
            val value = rest.substringBefore(' ')
            return File(value).absoluteFile.normalize() == File(cacheRoot).absoluteFile.normalize()
        }

        /** All `/proc/<pid>/cmdline` files that match [isCovdCmdline]. */
        fun findCovdPids(procRoot: File, cacheRoot: File): List<Long> =
            procRoot.listFiles { file -> file.isDirectory && file.name.all(Char::isDigit) }.orEmpty()
                .sortedBy { it.name.toLong() }
                .filter { dir ->
                    val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull()
                    cmdline != null && isCovdCmdline(cmdline, cacheRoot.absolutePath)
                }
                .map { it.name.toLong() }

        /** True when the process command line contains [marker] (used to re-check a PID before signalling). */
        fun commandLineContains(pid: Long, marker: String, procRoot: File = File("/proc")): Boolean {
            val file = File(File(procRoot, pid.toString()), "cmdline")
            val text = runCatching { file.readText() }.getOrNull() ?: return false
            return normalizeCmdline(text).contains(marker)
        }

        /** PID listening on a local TCP port, via /proc/net/tcp + /proc/<pid>/fd. */
        fun pidForListeningPort(port: Int, procRoot: File = File("/proc")): Long? {
            val inode = socketInode(port, procRoot) ?: return null
            val marker = "socket:[$inode]"
            val entries = procRoot.listFiles { file -> file.isDirectory && file.name.all(Char::isDigit) }.orEmpty()
            for (dir in entries) {
                val fds = File(dir, "fd").listFiles().orEmpty()
                if (fds.any { runCatching { java.nio.file.Files.readSymbolicLink(it.toPath()).toString() }.getOrNull() == marker }) {
                    return dir.name.toLongOrNull()
                }
            }
            return null
        }

        private fun socketInode(port: Int, procRoot: File): Long? {
            val hexPort = String.format(Locale.ROOT, "%04X", port)
            for (name in listOf("tcp", "tcp6")) {
                val file = File(procRoot, "net/$name")
                val lines = runCatching { file.readLines() }.getOrNull() ?: continue
                for (line in lines.drop(1)) {
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size < 10) continue
                    val local = fields[1]
                    val state = fields[3]
                    if (!local.endsWith(":$hexPort") || state != "0A") continue // 0A = LISTEN
                    return fields[9].toLongOrNull()
                }
            }
            return null
        }
    }
}
