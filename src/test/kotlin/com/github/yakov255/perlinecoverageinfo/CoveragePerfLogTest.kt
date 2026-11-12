package com.github.yakov255.perlinecoverageinfo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CoveragePerfLogTest {

    private val previousDir = System.getProperty("coverage.perf.dir")

    @After
    fun tearDown() {
        CoveragePerfLog.enabled = false
        if (previousDir == null) {
            System.clearProperty("coverage.perf.dir")
        } else {
            System.setProperty("coverage.perf.dir", previousDir)
        }
    }

    @Test
    fun `span records wall, cpu and allocation metrics`() {
        val dir = Files.createTempDirectory("coverage-perf").toFile()
        System.setProperty("coverage.perf.dir", dir.absolutePath)
        CoveragePerfLog.enabled = true

        assertEquals(42, CoveragePerfLog.span("test.op", "answer" to 42) { 42 })
        CoveragePerfLog.count("test.counted", wallNanos = 2_000_000, allocBytes = 1_048_576)
        CoveragePerfLog.heapSnapshot("test.snapshot")

        assertThrows(IllegalStateException::class.java) {
            CoveragePerfLog.span("test.failing") { error("boom") }
        }

        val file = CoveragePerfLog.currentFile()
        assertNotNull("perf log file should be created on the first write", file)
        val text = file!!.readText()

        assertTrue(text, "name=test.op" in text)
        assertTrue(text, "ph=end" in text)
        assertTrue(text, "wallMs=" in text)
        assertTrue(text, "cpuMs=" in text)
        assertTrue(text, "allocMB=" in text)
        assertTrue(text, "heapBeforeMB=" in text)
        assertTrue(text, "gcCount=" in text)
        assertTrue(text, "answer=42" in text)
        assertTrue(text, "name=test.failing" in text)
        assertTrue(text, "ok=false" in text)
        assertTrue(text, "name=test.snapshot" in text)
        assertTrue(text, "ev=heap" in text)
    }
}
