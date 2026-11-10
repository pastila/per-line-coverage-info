package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunInputsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val t0 = 1_760_000_000_000L

    @Test
    fun `proxy rows tolerate missing fields and malformed lines`() {
        val file = temporary.newFile("proxy.jsonl")
        file.writeText(
            """
            {"t":$t0,"seq":1,"method":"GET","path":"/a","status":200,"bytesDown":10,"ms":5,"fault":null,"authSeen":true}
            {"t":${t0 + 1000},"method":"GET","path":"/b","status":401,"bytesDown":0,"artifactJobId":11}
            {"t":"${t0 + 2000}","method":"GET","path":"/c","bytesDown":20}
            not json
            {"method":"GET","path":"/no-time"}
            """.trimIndent() + "\n",
        )

        val journal = ProxyJournalParser.parse(file)

        assertEquals(2, journal.malformed)
        assertEquals(3, journal.rows.size)
        assertEquals(30L, journal.bytesDown)
        assertEquals(1, journal.artifacts)
        assertEquals(mapOf(401 to 1), journal.errorStatuses)
        assertEquals(true, journal.rows.first().authSeen)
        assertNull(journal.rows[1].fault)
        assertEquals(2000L, journal.windowMs)
    }

    @Test
    fun `samples csv accepts header and positional input`() {
        val withHeader = temporary.newFile("samples.csv")
        withHeader.writeText(
            """
            tMs,proc,pid,rssMb,cpuPct
            ${t0},covd,42,100.0,10.0
            ${t0 + 1000},ide,1,500.0,5.0
            bad,row
            """.trimIndent() + "\n",
        )
        val samples = SamplesCsvParser.parse(withHeader)
        assertEquals(2, samples.size)
        assertEquals("covd", samples.first().proc)
        assertEquals(42L, samples.first().pid)
        assertEquals(100.0, samples.first().rssMb!!, 0.001)

        val positional = temporary.newFile("positional.csv")
        positional.writeText("$t0,covd,42,100.0,10.0\n")
        assertEquals(1, SamplesCsvParser.parse(positional).size)
        assertTrue(SamplesCsvParser.parse(File(withHeader.parentFile, "missing.csv")).isEmpty())
    }

    @Test
    fun `snapshots and scenarios accept truncated rows`() {
        val snapshots = temporary.newFile("snapshots.jsonl")
        snapshots.writeText(
            """
            {"tMs":$t0,"state":"ready","commit":"abcdef12","stale":false,"loading":true,"covdInstanceId":"i1","covdPid":7}
            {"state":"no-time"}
            broken
            """.trimIndent() + "\n",
        )
        val parsedSnapshots = SnapshotsParser.parse(snapshots)
        assertEquals(1, parsedSnapshots.size)
        assertEquals("abcdef12", parsedSnapshots.first().commit)
        assertEquals(7L, parsedSnapshots.first().covdPid)

        val scenarios = temporary.newFile("scenarios.jsonl")
        scenarios.writeText(
            """
            {"t":${t0 + 500},"scenario":"R1","phase":"start","msg":"go","data":{"a":1}}
            {"scenario":"no-time","phase":"x"}
            """.trimIndent() + "\n",
        )
        val events = ScenariosParser.parse(scenarios)
        assertEquals(2, events.size)
        assertEquals("R1", events.first().scenario)
        assertNull(events.last().tMs)
        assertTrue(events.first().dataText.contains("\"a\":1"))
    }

    @Test
    fun `saved covd answer exposes timing and gitlab trace`() {
        val dir = temporary.newFolder("covd-responses")
        File(dir, "0012-20261110-100000-000-ensure-ready.json").writeText(
            """
            {"seq":12,"atMs":$t0,"kind":"ensure","elapsedMs":900,"httpStatus":200,
             "request":{"component":"raketa"},
             "response":{"state":"ready","commit":"cafe1234","cached":true,
               "trace":{"requestId":"r12","joinedFlight":true,
                 "outcome":{"state":"ready","httpStatus":200},
                 "timing":{"resolveMs":10,"downloadMs":20,"parseMs":30,"mergeMs":40,"writeMs":50,"baselineMs":60,"totalMs":900},
                 "gitlab":[{"method":"GET","path":"/a","status":200,"bytes":1234},
                           {"method":"GET","path":"/b","status":0,"error":"timeout"}]}}}
            """.trimIndent(),
        )

        val files = CovdResponsesParser.read(dir)
        assertEquals(1, files.size)
        val saved = files.first()
        assertEquals(12L, saved.seq)
        assertEquals(2, saved.gitlabCalls.size)
        assertEquals("r12", saved.trace!!.stringOrNull("requestId"))
        assertEquals(900L, saved.trace!!.objOrNull("timing")!!.longOrNull("totalMs"))
    }

    @Test
    fun `scenario index attributes the last event before a point`() {
        val events = listOf(
            ScenarioEvent(t0, "R1", "start", "", ""),
            ScenarioEvent(t0 + 1000, "R2", "start", "", ""),
        )
        val index = ScenarioIndex(events)

        assertEquals("R1", index.at(t0 + 500))
        assertEquals("R2", index.at(t0 + 1000))
        assertEquals("", index.at(t0 - 1))
    }
}
