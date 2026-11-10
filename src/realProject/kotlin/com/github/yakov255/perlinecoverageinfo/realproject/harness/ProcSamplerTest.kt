package com.github.yakov255.perlinecoverageinfo.realproject.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `/proc` parsing and the covd discovery guard. Signals are never sent to a real process here;
 * the scenarios use [ProcSampler.sigstopCovd] against the daemon they started themselves.
 */
class ProcSamplerTest {

    private val temp: File = Files.createTempDirectory("rp-proc-test").toFile()

    /** `comm` contains spaces and parentheses, utime=11+1? see assertion in the test. */
    private val stat = "4242 (java thread (pool-1)) S 1 4242 4242 0 -1 4194560 " +
        "100 0 0 0 120 30 0 0 20 0 25 0 900 123456789 1234 18446744073709551615"

    @Test
    fun `stat counters ignore parentheses and spaces in comm`() {
        val counters = ProcSampler.parseStatCounters(stat)
        assertEquals(120L, counters!!.utimeTicks)
        assertEquals(30L, counters.stimeTicks)
        assertEquals(150L, counters.totalTicks)
        assertEquals("S", ProcSampler.parseState(stat))
    }

    @Test
    fun `stat parser rejects truncated input`() {
        assertNull(ProcSampler.parseStatCounters("4242 (java) S 1 2 3"))
        assertNull(ProcSampler.parseStatCounters("garbage"))
    }

    @Test
    fun `status parser reads rss and threads`() {
        val status = """
            Name:	java
            State:	S (sleeping)
            VmRSS:	   10240 kB
            Threads:	42
        """.trimIndent()
        assertEquals(10240L, ProcSampler.parseRssKb(status))
        assertEquals(42, ProcSampler.parseThreads(status))
        assertNull(ProcSampler.parseRssKb("Name: java"))
    }

    @Test
    fun `cpu percent is computed from ticks between samples`() {
        val first = ProcSampler.StatCounters(100, 100)
        val second = ProcSampler.StatCounters(250, 150)
        val previous = ProcSampler.CpuState(first, 1_000)
        // 200 ticks in 1 s at 100 Hz = 2 CPUs = 200 % (a JVM with GC threads can do that).
        assertEquals(200.0, ProcSampler.cpuPercent(previous, second, 2_000), 0.001)
        assertEquals(0.0, ProcSampler.cpuPercent(null, second, 2_000), 0.001)
        assertEquals(0.0, ProcSampler.cpuPercent(previous, second, 1_000), 0.001)
        assertEquals(0.0, ProcSampler.cpuPercent(previous, ProcSampler.StatCounters(1, 1), 2_000), 0.001)
    }

    @Test
    fun `csv row has the documented shape`() {
        val row = ProcSampler.Sample(
            tMs = 1_760_000_000_000,
            proc = "ide-1",
            pid = 4242,
            rssMb = 512.25,
            cpuPct = 12.34,
        ).toCsvRow()
        assertEquals("1760000000000,ide-1,4242,512.3,12.3", row)
        assertEquals("tMs,proc,pid,rssMb,cpuPct", ProcSampler.CSV_HEADER)
    }

    @Test
    fun `sampler writes one row per registered process per tick`() {
        val procRoot = File(temp, "proc")
        writeProc(procRoot, 100, stat = stat, status = "VmRSS:\t  10240 kB\nThreads:\t7\n")
        var now = 1_000L
        val csv = File(temp, "samples.csv")
        val sampler = ProcSampler(csv, File(temp, "cache"), procRoot, clockMs = { now })
        sampler.add("ide-1", 100)
        sampler.sampleOnce()
        // Second sample with +200 ticks over 1 s.
        writeProc(
            procRoot,
            100,
            stat = stat.replace(" 120 30 ", " 270 80 "),
            status = "VmRSS:\t  10240 kB\nThreads:\t7\n",
        )
        now = 2_000L
        val rows = sampler.sampleOnce()

        assertEquals(1, rows.size)
        assertEquals(200.0, rows.single().cpuPct, 0.001)
        assertEquals(10.0, rows.single().rssMb, 0.001)
        val lines = csv.readLines()
        assertEquals(ProcSampler.CSV_HEADER, lines[0])
        assertEquals(3, lines.size)
        assertEquals("1000,ide-1,100,10.0,0.0", lines[1])
    }

    @Test
    fun `vanished process is skipped instead of failing the tick`() {
        val csv = File(temp, "samples-empty.csv")
        val sampler = ProcSampler(csv, File(temp, "cache"), File(temp, "empty-proc"))
        sampler.add("covd", 999_999)
        assertTrue(sampler.sampleOnce().isEmpty())
        assertEquals(listOf(ProcSampler.CSV_HEADER), csv.readLines())
    }

    @Test
    fun `covd cmdline matches only the harness cache root`() {
        val cache = File(temp, "cache-root").absoluteFile
        assertTrue(ProcSampler.isCovdCmdline("/opt/bin/covd\u0000--cache-dir\u0000${cache.path}\u0000", cache.path))
        assertFalse("another cache root must never be sampled", ProcSampler.isCovdCmdline("/opt/bin/covd\u0000--cache-dir\u0000/tmp/other\u0000", cache.path))
        assertFalse(ProcSampler.isCovdCmdline("/usr/bin/java\u0000--cache-dir\u0000${cache.path}", cache.path))
        assertFalse(ProcSampler.isCovdCmdline("/opt/bin/covd", cache.path))
    }

    @Test
    fun `covd discovery scans proc and ignores other pids`() {
        val procRoot = File(temp, "proc-scan")
        val cache = File(temp, "cache-scan").absoluteFile
        File(procRoot, "100").mkdirs()
        File(procRoot, "100/cmdline").writeText("/opt/covd\u0000--cache-dir\u0000${cache.path}\u0000")
        File(procRoot, "200").mkdirs()
        File(procRoot, "200/cmdline").writeText("/usr/bin/java\u0000-jar\u0000app.jar\u0000")
        File(procRoot, "self").mkdirs()
        File(procRoot, "self/cmdline").writeText("/opt/covd\u0000--cache-dir\u0000${cache.path}\u0000")

        assertEquals(listOf(100L), ProcSampler.findCovdPids(procRoot, cache))
        assertTrue(ProcSampler.commandLineContains(100, cache.path, procRoot))
        assertFalse(ProcSampler.commandLineContains(200, cache.path, procRoot))
    }

    @Test
    fun `no covd means no signal is sent`() {
        val csv = File(temp, "no-covd-samples.csv")
        val sampler = ProcSampler(csv, File(temp, "no-covd-cache"), File(temp, "no-covd-proc"))
        assertNull(sampler.sigstopCovd())
        assertNull(sampler.sigcontCovd())
    }

    private fun writeProc(root: File, pid: Long, stat: String, status: String) {
        val dir = File(root, pid.toString()).apply { mkdirs() }
        File(dir, "stat").writeText("$pid $stat")
        File(dir, "status").writeText(status)
    }
}
