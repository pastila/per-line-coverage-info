package com.github.yakov255.perlinecoverageinfo

import java.io.File
import java.nio.file.Files

/**
 * 1 Hz samples of the covd daemon process, written to the perf log as `ev=covdsample`.
 *
 * The daemon is shared per cache directory and may be a child of another IDE, so the PID is
 * resolved from the handshake port through `/proc`: the listening socket's inode, then the
 * process that holds a file descriptor for it. Only Linux is supported; elsewhere [start]
 * does nothing. The thread is a daemon and stops with [stop] (or [CovdClient.resetForTest]).
 */
object CovdProcessSampler {

    private const val SAMPLE_INTERVAL_MS = 1_000L

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var port: Int = 0

    @Volatile
    private var pid: Long? = null

    /** PID of the daemon last sampled, or null when it is unknown. */
    fun knownPid(): Long? = pid

    /** Starts, or retargets, the sampler for the daemon listening on [port]. */
    fun start(port: Int) {
        // The samples are only useful in the perf log; without it the thread must not exist.
        if (!CoveragePerfLog.isEnabled()) return
        if (!CovdProc.isSupported()) return
        synchronized(this) {
            if (this.port == port && thread?.isAlive == true) return
            stopLocked()
            this.port = port
            pid = null
            val started = Thread({ sampleLoop(port) }, "CoverageCovdSampler")
            started.isDaemon = true
            started.priority = Thread.MIN_PRIORITY
            thread = started
            started.start()
        }
    }

    /** Stops sampling; the next [start] begins from an unknown PID. */
    fun stop() {
        synchronized(this) { stopLocked() }
    }

    private fun stopLocked() {
        thread?.interrupt()
        thread = null
        pid = null
        port = 0
    }

    private fun sampleLoop(port: Int) {
        val selfPid = ProcessHandle.current().pid()
        var lastTicks = -1L
        var lastNanos = 0L
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            try {
                val currentPid = pid ?: CovdProc.findPidForPort(port)?.also { pid = it }
                if (currentPid == null) continue
                // A stand-in daemon inside this JVM (the fake contract tests) describes us, not covd.
                if (currentPid == selfPid) {
                    pid = null
                    continue
                }
                val memory = CovdProc.readStatus(currentPid)
                if (memory == null) {
                    pid = null
                    lastTicks = -1L
                    continue
                }
                val ticks = CovdProc.readCpuTicks(currentPid) ?: -1L
                val now = System.nanoTime()
                val cpuPct = if (ticks >= 0 && lastTicks >= 0 && now > lastNanos) {
                    CovdProc.cpuPercent(ticks - lastTicks, (now - lastNanos) / 1_000_000, CovdProc.CLOCK_TICKS_PER_SECOND)
                } else {
                    -1.0
                }
                lastTicks = ticks
                lastNanos = now
                CoveragePerfLog.covdSample(currentPid, memory.rssMb, cpuPct, memory.threads, memory.vszMb)
            } catch (_: Throwable) {
                // Diagnostics must never break anything.
            }
        }
    }
}

/**
 * Linux `/proc` access for the sampler. Parsing is separated from file reading so the formats
 * (`/proc/net/tcp`, `/proc/<pid>/status`, `/proc/<pid>/stat`, fd symlinks) are unit-testable.
 */
internal object CovdProc {

    /** `CLK_TCK`; 100 on every Linux kernel the plugin runs on (x86 and arm). */
    const val CLOCK_TICKS_PER_SECOND = 100L

    private val procRoot: File = File("/proc")

    fun isSupported(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    /**
     * Inodes of the sockets in `LISTEN` (`0A`) state on [port] in one `/proc/net/tcp` or
     * `/proc/net/tcp6` table. The local address is hex `address:port`, the inode is field 10.
     */
    fun listeningInodes(table: String, port: Int): Set<String> {
        val result = LinkedHashSet<String>()
        table.lineSequence().drop(1).forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 10) return@forEach
            val localPort = fields[1].substringAfterLast(':').toIntOrNull(16) ?: return@forEach
            if (localPort != port || fields[3] != "0A") return@forEach
            result += fields[9]
        }
        return result
    }

    /** `socket:[12345]` -> `12345`; other descriptors and malformed links -> null. */
    fun inodeOfLink(link: String): String? {
        val trimmed = link.trim()
        if (!trimmed.startsWith("socket:[") || !trimmed.endsWith("]")) return null
        return trimmed.removePrefix("socket:[").removeSuffix("]").takeIf { it.isNotEmpty() }
    }

    /** The smallest PID among [fdLinks] (`pid` to fd target) that holds one of [inodes]. */
    fun pidHolding(fdLinks: Sequence<Pair<Long, String>>, inodes: Set<String>): Long? {
        if (inodes.isEmpty()) return null
        var best: Long? = null
        fdLinks.forEach { (candidatePid, link) ->
            val inode = inodeOfLink(link) ?: return@forEach
            if (inode in inodes && (best == null || candidatePid < best)) best = candidatePid
        }
        return best
    }

    data class Memory(val rssMb: Double, val vszMb: Double, val threads: Int)

    /** `VmRSS`/`VmSize` (kB) and `Threads` from `/proc/<pid>/status`; null when any is missing. */
    fun parseStatus(text: String): Memory? {
        var rssKb: Long? = null
        var vszKb: Long? = null
        var threads: Int? = null
        text.lineSequence().forEach { line ->
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
            when (key) {
                "VmRSS" -> rssKb = value
                "VmSize" -> vszKb = value
                "Threads" -> threads = value?.toInt()
            }
        }
        val rss = rssKb ?: return null
        val threadsNow = threads ?: return null
        return Memory(rssMb = rss / 1024.0, vszMb = (vszKb ?: 0L) / 1024.0, threads = threadsNow)
    }

    /** User plus system CPU ticks (`utime`+`stime`) from `/proc/<pid>/stat`; null when malformed. */
    fun parseCpuTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 1 >= stat.length) return null
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        if (fields.size < 13) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        return utime + stime
    }

    /** CPU use in percent of one core over [elapsedMs]; -1 when a measurement is missing. */
    fun cpuPercent(deltaTicks: Long, elapsedMs: Long, ticksPerSecond: Long): Double {
        if (deltaTicks < 0 || elapsedMs <= 0 || ticksPerSecond <= 0) return -1.0
        return deltaTicks.toDouble() / ticksPerSecond * 1000.0 / elapsedMs * 100.0
    }

    /** Finds the process that listens on [port] via `/proc/net/tcp(6)` and its file descriptors. */
    fun findPidForPort(port: Int, root: File = procRoot): Long? {
        val inodes = LinkedHashSet<String>()
        for (name in listOf("tcp", "tcp6")) {
            val table = File(root, "net/$name")
            if (!table.isFile) continue
            try {
                inodes += listeningInodes(table.readText(), port)
            } catch (_: Throwable) {
                // An unreadable table just means "unknown" this tick.
            }
        }
        if (inodes.isEmpty()) return null

        val links = ArrayList<Pair<Long, String>>()
        val processes = root.listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } } ?: return null
        for (process in processes) {
            val pid = process.name.toLongOrNull() ?: continue
            val fds = File(process, "fd").listFiles() ?: continue
            for (fd in fds) {
                try {
                    links += pid to Files.readSymbolicLink(fd.toPath()).toString()
                } catch (_: Throwable) {
                    // Another user's process, or the descriptor closed meanwhile.
                }
            }
        }
        return pidHolding(links.asSequence(), inodes)
    }

    fun readStatus(pid: Long, root: File = procRoot): Memory? {
        val file = File(root, "$pid/status")
        return try {
            if (file.isFile) parseStatus(file.readText()) else null
        } catch (_: Throwable) {
            null
        }
    }

    fun readCpuTicks(pid: Long, root: File = procRoot): Long? {
        val file = File(root, "$pid/stat")
        return try {
            if (file.isFile) parseCpuTicks(file.readText()) else null
        } catch (_: Throwable) {
            null
        }
    }
}
