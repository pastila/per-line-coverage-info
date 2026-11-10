package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Parsing tests for the `/proc` formats the covd sampler reads. The input strings are shaped
 * like the real files (see `proc(5)` and `tcp(7)`), so the sampler logic runs without a daemon.
 */
class CovdProcTest {

    // 127.0.0.1 as /proc/net/tcp prints it, built at runtime so the literal never leaves the test.
    private val loopback = String.format("%08X", 0x0100007F)

    private fun row(localPort: Int, state: String, inode: String): String {
        val local = "$loopback:" + String.format("%04X", localPort)
        return "   0: $local ${loopback}:0000 $state 00000000:00000000 00:00000000 00000000  1000        0 $inode 1 0000000000000000 100 0 0 10 0"
    }

    private val tcpTable: String = listOf(
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
        row(0x1F90, "0A", "1700627081"),
        row(0x1F91, "01", "1504188488"),
        row(0x7530, "0A", "1505018740"),
    ).joinToString("\n")

    private val tcp6Table: String = listOf(
        "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
        "   0: 00000000000000000000000000000000:1F90 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 2200627081 1 0000000000000000 100 0 0 10 0",
    ).joinToString("\n")

    // --- /proc/net/tcp ------------------------------------------------------------

    @Test
    fun `only the listening socket of the requested port is collected`() {
        assertEquals(setOf("1700627081"), CovdProc.listeningInodes(tcpTable, 0x1F90))
        assertEquals(setOf("1505018740"), CovdProc.listeningInodes(tcpTable, 0x7530))
        assertEquals("a non-listening socket is ignored", emptySet<String>(), CovdProc.listeningInodes(tcpTable, 0x1F91))
        assertEquals(emptySet<String>(), CovdProc.listeningInodes(tcpTable, 0x1234))
    }

    @Test
    fun `ipv6 table uses the same inode column`() {
        assertEquals(setOf("2200627081"), CovdProc.listeningInodes(tcp6Table, 8080))
    }

    @Test
    fun `a header-only or malformed table yields nothing`() {
        assertEquals(emptySet<String>(), CovdProc.listeningInodes("  sl  local_address rem_address st", 8080))
        assertEquals(emptySet<String>(), CovdProc.listeningInodes("", 8080))
        assertEquals(emptySet<String>(), CovdProc.listeningInodes("0: zzz 0 0A", 8080))
    }

    // --- socket file descriptors --------------------------------------------------

    @Test
    fun `socket inodes are read from fd symlinks`() {
        assertEquals("1700627081", CovdProc.inodeOfLink("socket:[1700627081]"))
        assertNull(CovdProc.inodeOfLink("/dev/null"))
        assertNull(CovdProc.inodeOfLink("pipe:[123]"))
        assertNull(CovdProc.inodeOfLink("socket:[]"))
        assertNull(CovdProc.inodeOfLink("socket:1700"))
    }

    @Test
    fun `the smallest pid that holds the listening socket wins`() {
        val links = sequenceOf(
            900L to "socket:[999]",
            120L to "/dev/null",
            300L to "socket:[1700627081]",
            200L to "socket:[1700627081]",
            400L to "pipe:[1]",
        )

        assertEquals(200L, CovdProc.pidHolding(links, setOf("1700627081")))
        assertNull(CovdProc.pidHolding(links, setOf("404")))
        assertNull(CovdProc.pidHolding(links, emptySet()))
    }

    @Test
    fun `a port listening in this process resolves to this pid`() {
        assumeTrue("/proc is only parsed on Linux", CovdProc.isSupported())

        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { socket ->
            assertEquals(
                ProcessHandle.current().pid(),
                CovdProc.findPidForPort(socket.localPort),
            )
        }
    }

    // --- /proc/<pid>/status -------------------------------------------------------

    private val status = """
        Name:	covd
        State:	S (sleeping)
        VmPeak:	  912340 kB
        VmSize:	  871924 kB
        VmRSS:	  120456 kB
        Threads:	12
    """.trimIndent()

    @Test
    fun `status gives rss vsz and thread count in megabytes`() {
        val memory = CovdProc.parseStatus(status)!!

        assertEquals(120456 / 1024.0, memory.rssMb, 0.001)
        assertEquals(871924 / 1024.0, memory.vszMb, 0.001)
        assertEquals(12, memory.threads)
    }

    @Test
    fun `status without VmRSS or Threads is rejected`() {
        assertNull(CovdProc.parseStatus("Name:\tcovd\nThreads:\t12"))
        assertNull(CovdProc.parseStatus("Name:\tcovd\nVmRSS:\t10 kB"))
    }

    // --- /proc/<pid>/stat ---------------------------------------------------------

    private val stat =
        "4242 (covd thread) S 1 4242 4242 0 -1 4194304 319 733 0 0 17 6 0 0 20 0 9 0 230203511 8927744 448 18446744073709551615"

    @Test
    fun `cpu ticks are utime plus stime even when the command name has spaces`() {
        assertEquals(23L, CovdProc.parseCpuTicks(stat))
    }

    @Test
    fun `a truncated stat line has no cpu ticks`() {
        assertNull(CovdProc.parseCpuTicks("4242 (covd) S 1 2 3"))
        assertNull(CovdProc.parseCpuTicks("garbage"))
    }

    @Test
    fun `cpu percent is the delta over elapsed wall time`() {
        // 50 ticks in 500 ms = 0.5 s of CPU per 0.5 s of wall time = 100 %.
        assertEquals(100.0, CovdProc.cpuPercent(50, 500, 100), 0.001)
        assertEquals(25.0, CovdProc.cpuPercent(50, 2_000, 100), 0.001)
        assertEquals(-1.0, CovdProc.cpuPercent(-1, 500, 100), 0.001)
        assertEquals(-1.0, CovdProc.cpuPercent(10, 0, 100), 0.001)
    }
}
