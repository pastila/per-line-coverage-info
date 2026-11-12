package com.github.yakov255.perlinecoverageinfo

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** `ev=covdsample`: the daemon's numbers, written by the same perf-log writer. */
class CoveragePerfLogCovdSampleTest {

    private val previousDir = System.getProperty(CoveragePaths.PERF_DIR_PROPERTY)

    @After
    fun tearDown() {
        CoveragePerfLog.resetForTest()
        if (previousDir == null) {
            System.clearProperty(CoveragePaths.PERF_DIR_PROPERTY)
        } else {
            System.setProperty(CoveragePaths.PERF_DIR_PROPERTY, previousDir)
        }
    }

    @Test
    fun `covd sample carries pid rss cpu threads and vsz`() {
        val dir = Files.createTempDirectory("coverage-perf-covd").toFile()
        try {
            CoveragePerfLog.resetForTest()
            System.setProperty(CoveragePaths.PERF_DIR_PROPERTY, dir.absolutePath)
            CoveragePerfLog.enabled = true

            CoveragePerfLog.covdSample(pid = 4242, rssMb = 120.5, cpuPct = 13.25, threads = 9, vszMb = 2048.0)

            val file = CoveragePerfLog.currentFile()
            assertNotNull("perf log file should be created on the first write", file)
            val text = file!!.readText()
            assertTrue(text, "ev=covdsample" in text)
            assertTrue(text, "pid=4242" in text)
            assertTrue(text, "rssMb=120.500" in text)
            assertTrue(text, "cpuPct=13.250" in text)
            assertTrue(text, "threads=9" in text)
            assertTrue(text, "vszMb=2048.000" in text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `covd sample writes nothing while the perf log is disabled`() {
        val dir = Files.createTempDirectory("coverage-perf-covd-off").toFile()
        try {
            CoveragePerfLog.resetForTest()
            System.setProperty(CoveragePaths.PERF_DIR_PROPERTY, dir.absolutePath)
            CoveragePerfLog.enabled = false

            CoveragePerfLog.covdSample(pid = 1, rssMb = 1.0, cpuPct = 1.0, threads = 1, vszMb = 1.0)

            assertTrue(CoveragePerfLog.currentFile() == null || !CoveragePerfLog.currentFile()!!.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
