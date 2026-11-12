package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerfLogParserTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `parses both event forms and typed values`() {
        val event = PerfLogParser.parseLine(
            "1760000000000 10:00:00.000 ev=event name=covd.response th=main heap=1.000 " +
                "state=ready cached=true totalMs=4321.500 resolveMs=100 seq=7"
        )
        assertEquals(1_760_000_000_000L, event!!.tMs)
        assertEquals("covd.response", event.eventName)
        assertEquals("ready", event.str("state"))
        assertEquals(true, event.bool("cached"))
        assertEquals(4321.5, event.double("totalMs")!!, 0.001)
        assertEquals(7L, event.long("seq"))

        val raw = PerfLogParser.parseLine("1760000000000 10:00:01.000 ev=covd.spawn how=spawn pid=42")
        assertEquals("covd.spawn", raw!!.eventName)
        assertEquals(42L, raw.long("pid"))
    }

    @Test
    fun `sanitizer placeholders are absent values`() {
        val record = PerfLogParser.parseLine(
            "1760000000000 10:00:00.000 ev=event name=covd.response code=null cached=- detail="
        )!!
        assertNull(record.str("code"))
        assertNull(record.bool("cached"))
        assertNull(record.str("detail"))
        assertNull(record.double("totalMs"))
    }

    @Test
    fun `malformed lines are counted blank lines are not`() {
        val file = temporary.newFile("run-broken.log")
        file.writeText(
            """
            garbage without timestamp
            1760000000000 10:00:00.000 ev=sample heapUsedMB=10.000

            1760000000001 no-equals-here
            1760000000002 10:00:02.000 ev=span name=x ph=end wallMs=1.000
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        val parsed = PerfLogParser.parse(file)

        assertEquals(4, parsed.lines)
        assertEquals(2, parsed.malformed)
        assertEquals(2, parsed.records.size)
        assertEquals(1_760_000_000_002L, parsed.records.last().tMs)
    }

    @Test
    fun `directory read is sorted and missing directory is empty`() {
        val dir = temporary.newFolder("perf")
        File(dir, "b.log").writeText("1760000000002 10:00:00.000 ev=sample heapUsedMB=2.000\n")
        File(dir, "a.log").writeText("1760000000001 10:00:00.000 ev=sample heapUsedMB=1.000\n")
        File(dir, "ignored.txt").writeText("1760000000003 10:00:00.000 ev=sample heapUsedMB=3.000\n")

        val logs = PerfLogParser.read(dir)

        assertEquals(listOf("a.log", "b.log"), logs.map { it.file.name })
        assertTrue(PerfLogParser.read(File(dir, "missing")).isEmpty())
    }
}
