package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportGeneratorTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val t0 = 1_760_000_000_000L

    @Test
    fun `empty directory produces a complete report without data`() {
        val runDir = temporary.newFolder("empty-run")

        val output = ReportGenerator(runDir).generate()

        assertTrue(output.reportFile.isFile)
        assertTrue(output.summaryFile.isFile)
        assertTrue(output.timelineFile.isFile)
        val report = output.reportFile.readText()
        for (section in 1..8) {
            assertTrue("section $section missing", report.contains("## $section."))
        }
        assertTrue(report.contains("нет данных"))
        assertTrue(report.contains("находок нет"))
        assertTrue(output.secretFindings.isEmpty())

        val summary = readJson(output.summaryFile)
        assertEquals(0, summary["loads"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(0, summary["secretFindings"]!!.jsonArray.size)
        assertEquals(0, summary["download"]!!.jsonObject["requests"]!!.jsonPrimitive.int)
    }

    @Test
    fun `synthetic run fills every section`() {
        val runDir = temporary.newFolder("full-run")
        writeFullRun(runDir)

        val output = ReportGenerator(runDir).generate()
        val report = output.reportFile.readText()
        val summary = readJson(output.summaryFile)
        val timeline = output.timelineFile.readLines()

        // 1. timing
        assertTrue(report.contains("R1"))
        assertTrue(report.contains("abcdef12"))
        assertTrue(report.contains("Медиана totalMs"))
        assertTrue(report.contains("5000"))

        // 2. download
        assertTrue(report.contains("запросов: 4"))
        assertTrue(report.contains("артефактов"))
        assertTrue(report.contains("500: 1"))
        assertTrue(report.contains("2.33 МБ/с"))
        assertTrue(report.contains("вызовов: 2"))
        assertEquals(4, summary["download"]!!.jsonObject["requests"]!!.jsonPrimitive.int)
        assertEquals(7_341_032L, summary["download"]!!.jsonObject["bytesDown"]!!.jsonPrimitive.content.toLong())
        assertEquals(2, summary["download"]!!.jsonObject["artifacts"]!!.jsonPrimitive.int)
        assertEquals(2, summary["download"]!!.jsonObject["traceCalls"]!!.jsonPrimitive.int)
        assertEquals(1, summary["download"]!!.jsonObject["traceErrors"]!!.jsonPrimitive.int)

        // 3. memory
        assertTrue(report.contains("512.5"))
        assertTrue(report.contains("220.5"))
        assertTrue(report.contains("Пики по фазам"))
        assertTrue(report.contains("download"))
        assertEquals(512.5, summary["memory"]!!.jsonObject["ideHeapMaxMb"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(220.5, summary["memory"]!!.jsonObject["covdPeakRssMb"]!!.jsonPrimitive.content.toDouble(), 0.001)

        // 4. cache
        assertTrue(report.contains("загрузок из кэша (`cached=true`): 1"))
        assertTrue(report.contains("нарушений: 0"))
        assertTrue(report.contains("размер кэша"))
        assertEquals(1, summary["cache"]!!.jsonObject["cached"]!!.jsonPrimitive.int)
        assertEquals(0, summary["cache"]!!.jsonObject["repeatGitlabCalls"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, summary["cache"]!!.jsonObject["cov4Files"]!!.jsonPrimitive.int)
        assertTrue(summary["cache"]!!.jsonObject["cacheBytes"]!!.jsonPrimitive.content.toLong() >= 2048L)

        // 5. daemon
        assertTrue(report.contains("joinedFlight"))
        assertEquals(1, summary["daemon"]!!.jsonObject["spawn"]!!.jsonPrimitive.int)
        assertEquals(1, summary["daemon"]!!.jsonObject["reuse"]!!.jsonPrimitive.int)
        assertEquals(1, summary["daemon"]!!.jsonObject["uniquePids"]!!.jsonPrimitive.int)
        assertEquals(1, summary["daemon"]!!.jsonObject["joinedFlight"]!!.jsonPrimitive.int)

        // 6. switches: event at t0+5200, first ready load at t0+6000
        assertTrue(report.contains("Задержка до нового покрытия"))
        assertTrue(report.contains("800"))
        val delays = summary["switches"]!!.jsonObject["delaysMs"]!!.jsonArray
        assertEquals(1, delays.size)
        assertEquals(800L, delays[0].jsonPrimitive.content.toLong())

        // 7. failures
        assertTrue(report.contains("auth_failed"))
        assertTrue(report.contains("баллун", ignoreCase = true) || report.contains("Баллуны"))
        assertEquals(0, summary["failures"]!!.jsonObject["retries"]!!.jsonPrimitive.int)
        assertEquals(1, summary["failures"]!!.jsonObject["balloons"]!!.jsonPrimitive.int)

        // timeline
        assertEquals("tMs,iso,source,scenario,event,commit,state,detail", timeline.first())
        assertTrue(timeline.any { it.contains("proxy-error") })
        assertTrue(timeline.any { it.contains("state-change") })
        assertTrue(timeline.size >= 10)

        // loads: perf response + raw response + error response
        assertEquals(3, summary["loads"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(0, summary["inputs"]!!.jsonObject["malformedPerfLines"]!!.jsonPrimitive.int)
        assertTrue(output.secretFindings.isEmpty())
    }

    @Test
    fun `malformed lines are counted and never fail the run`() {
        val runDir = temporary.newFolder("broken-run")
        File(runDir, "perf").mkdirs()
        File(runDir, "perf/run-broken.log").writeText(
            """
            not-a-timestamp ev=span name=broken
            ${t0} 10:00:00.000 this-is-not-a-key
            ${t0} 10:00:01.000 ev=covd.response state=ready commit=cafe1234 totalMs=not-a-number
            ${t0} 10:00:02.000 ev=span name=covd.ensure ph=start d=0

            ${t0 + 1000} 10:00:03.000 ev=sample heapUsedMB=100.000 heapMaxMB=1024.000
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        File(runDir, "proxy.jsonl").writeText(
            "{\"t\":$t0,\"status\":200}\n{oops\n\n{\"no\":\"timestamp\"}\n",
            Charsets.UTF_8,
        )
        File(runDir, "samples.csv").writeText("tMs,proc,pid,rssMb,cpuPct\nnope,covd,x,y,z\n")
        File(runDir, "snapshots.jsonl").writeText("not json\n")
        File(runDir, "scenarios.jsonl").writeText("{}\nnot json\n")
        File(runDir, "covd-responses").mkdirs()
        File(runDir, "covd-responses/broken.json").writeText("{not json")

        val output = ReportGenerator(runDir).generate()
        val summary = readJson(output.summaryFile)

        assertTrue(output.reportFile.readText().contains("## 7."))
        val inputs = summary["inputs"]!!.jsonObject
        assertTrue(inputs["malformedPerfLines"]!!.jsonPrimitive.int >= 2)
        assertTrue(inputs["malformedProxyRows"]!!.jsonPrimitive.int >= 2)
        // A load with a broken number stays in the report with "—" instead of a fake zero.
        assertEquals(1, summary["loads"]!!.jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `secret scan reports kind and position but never the value`() {
        val runDir = temporary.newFolder("secret-run")
        File(runDir, "logs").mkdirs()
        File(runDir, "logs/request.log").writeText(
            """
            GET /api/v4/projects/335/pipelines
            Authorization: Bearer SuperSecret42
            PRIVATE-TOKEN: anotherSecret99
            found glpat-ABCDEFGHIJKLMNOP
            the word token alone is fine
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        File(runDir, "notes.md").writeText("Value is SuperSecret42 here\n")

        val output = ReportGenerator(runDir, secrets = listOf("SuperSecret42")).generate()

        val types = output.secretFindings.map { it.type }.toSet()
        assertTrue(types.contains(SecretScanner.TYPE_SECRET_VALUE))
        assertTrue(types.contains(SecretScanner.TYPE_AUTHORIZATION))
        assertTrue(types.contains(SecretScanner.TYPE_BEARER))
        assertTrue(types.contains(SecretScanner.TYPE_PRIVATE_TOKEN))
        assertTrue(types.contains(SecretScanner.TYPE_GITLAB_TOKEN))

        val authorization = output.secretFindings.first {
            it.type == SecretScanner.TYPE_AUTHORIZATION && it.file == "logs/request.log"
        }
        assertEquals(2, authorization.line)

        val summaryText = output.summaryFile.readText()
        val report = output.reportFile.readText()
        for (secret in listOf("SuperSecret42", "anotherSecret99", "ABCDEFGHIJKLMNOP")) {
            assertFalse("summary leaked a secret", summaryText.contains(secret))
            assertFalse("report leaked a secret", report.contains(secret))
            assertFalse("finding leaked a secret", output.secretFindings.toString().contains(secret))
        }
        val findings = readJson(output.summaryFile)["secretFindings"]!!.jsonArray
        assertEquals(output.secretFindings.size, findings.size)
        assertTrue(findings.all { it.jsonObject["file"] != null && it.jsonObject["line"] != null })
    }

    @Test
    fun `second generation does not report its own output as a leak`() {
        val runDir = temporary.newFolder("repeat-run")
        writeFullRun(runDir)

        val first = ReportGenerator(runDir).generate()
        val second = ReportGenerator(runDir).generate()

        assertEquals(first.secretFindings, second.secretFindings)
        assertTrue(second.secretFindings.isEmpty())
    }

    @Test
    fun `repeat load falls back to the proxy journal when the perf line has no gitlabCalls`() {
        val runDir = temporary.newFolder("repeat-proxy")
        File(runDir, "perf").mkdirs()
        File(runDir, "perf/run-3-3.log").writeText(
            perfLine(
                t0,
                "ev=covd.response kind=ensure seq=1 state=ready commit=cafe1234 cached=true " +
                    "replaced=false totalMs=1000 resolveMs=0 downloadMs=0 parseMs=900 writeMs=100",
            ) + "\n",
            Charsets.UTF_8,
        )
        File(runDir, "proxy.jsonl").writeText(
            """{"t":${t0 - 500},"seq":1,"method":"GET","path":"/api/v4/projects/335/pipelines","status":200,"bytesDown":10,"ms":5,"authSeen":true}""" + "\n",
            Charsets.UTF_8,
        )

        val output = ReportGenerator(runDir).generate()
        val cache = readJson(output.summaryFile)["cache"]!!.jsonObject

        assertEquals(1, cache["repeatGitlabCalls"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, cache["repeatViolations"]!!.jsonPrimitive.int)
        assertTrue(output.reportFile.readText().contains("нарушений: 1"))
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private fun writeFullRun(runDir: File) {
        File(runDir, "perf").mkdirs()
        File(runDir, "perf/run-1-1.log").writeText(
            buildString {
                appendLine(perfLine(t0 + 5_000, "ev=event name=covd.response th=main heap=100.000 kind=ensure seq=1 state=ready commit=abcdef1234567890 cached=false replaced=false stale=false joinedFlight=false requestId=req-1 httpStatus=200 elapsedMs=5000 totalMs=5000 resolveMs=1000 downloadMs=2000 parseMs=1500 mergeMs=300 writeMs=200 baselineMs=0 gitlabCalls=7 gitlabBytes=73400320 gitlabErrors=0"))
                appendLine(perfLine(t0 + 5_200, "ev=covd.response kind=ensure seq=3 state=error code=auth_failed commit=deadbeefcafe1234 cached=false replaced=false httpStatus=401 totalMs=300 resolveMs=300 downloadMs=0 parseMs=0 mergeMs=0 writeMs=0 baselineMs=0 gitlabCalls=1 gitlabBytes=0 gitlabErrors=1"))
                appendLine(perfLine(t0 + 1_000, "ev=event name=covd.spawn th=main heap=100.000 how=spawn pid=4242 port=45678 instance=inst1234 startMs=${t0 + 1_000}"))
                appendLine(perfLine(t0 + 5_500, "ev=event name=covd.spawn th=main heap=100.000 how=reuse pid=4242 port=45678 instance=inst1234 startMs=${t0 + 1_000}"))
                appendLine(perfLine(t0 + 2_000, "ev=covdsample pid=4242 rssMb=200.000 cpuPct=40.000 threads=12 vszMb=900.000"))
                appendLine(perfLine(t0 + 3_000, "ev=covdsample pid=4242 rssMb=220.500 cpuPct=60.000 threads=14 vszMb=950.000"))
                appendLine(perfLine(t0 + 1_500, "ev=sample heapUsedMB=400.000 heapCommittedMB=1024.000 heapMaxMB=4096.000 nonHeapMB=300.000 threads=150 processCpuPct=100.000 loadAvg=1.00 gcCountDelta=1 gcMsDelta=10 uptimeMin=1"))
                appendLine(perfLine(t0 + 2_500, "ev=sample heapUsedMB=512.500 heapCommittedMB=1024.000 heapMaxMB=4096.000 nonHeapMB=310.000 threads=160 processCpuPct=140.000 loadAvg=2.00 gcCountDelta=2 gcMsDelta=15 uptimeMin=1"))
                appendLine(perfLine(t0 + 3_500, "ev=span name=covd.ensure ph=end ok=false d=0 th=main error=IOException message=boom"))
            },
            Charsets.UTF_8,
        )
        File(runDir, "perf/run-2-2.log").writeText(
            perfLine(
                t0 + 6_000,
                "ev=covd.response kind=ensure seq=2 state=ready commit=abcdef1234567890 cached=true replaced=false stale=false joinedFlight=true requestId=req-2 httpStatus=200 totalMs=120 resolveMs=0 downloadMs=10 parseMs=80 mergeMs=10 writeMs=20 baselineMs=0 gitlabCalls=0 gitlabBytes=0 gitlabErrors=0",
            ),
            Charsets.UTF_8,
        )

        File(runDir, "proxy.jsonl").writeText(
            buildString {
                appendLine("""{"t":${t0 + 500},"seq":1,"method":"GET","path":"/api/v4/projects/335/pipelines","query":"ref=master","status":200,"bytesDown":1000,"bytesUp":0,"ms":42,"fault":null,"artifactJobId":null,"authSeen":true}""")
                appendLine("""{"t":${t0 + 1_500},"seq":2,"method":"GET","path":"/api/v4/projects/335/jobs/11/artifacts","query":"","status":200,"bytesDown":5242880,"bytesUp":0,"ms":900,"fault":null,"artifactJobId":11,"authSeen":true}""")
                appendLine("""{"t":${t0 + 2_500},"seq":3,"method":"GET","path":"/api/v4/projects/335/jobs/12/artifacts","query":"","status":500,"bytesDown":0,"bytesUp":0,"ms":100,"fault":"status","artifactJobId":12,"authSeen":false}""")
                appendLine("""{"t":${t0 + 3_500},"seq":4,"method":"GET","path":"/api/v4/projects/335/jobs/11/artifacts","query":"","status":200,"bytesDown":2097152,"bytesUp":0,"ms":400,"fault":null,"artifactJobId":11,"authSeen":true}""")
            },
            Charsets.UTF_8,
        )

        File(runDir, "covd-responses").mkdirs()
        File(runDir, "covd-responses/0001-20261110-100000-000-ensure-ready.json").writeText(
            """
            {"seq":1,"atMs":${t0 + 5_000},"kind":"ensure","elapsedMs":5000,"httpStatus":200,
             "request":{"component":"raketa","projectId":"335","gitRoot":"/tmp/raketa-load","coverageBranch":"master","pathMode":"raketa","jobPrefixes":["test:behat:coverage:raketa"],"baseUrl":"http://127.0.0.1:41000","force":false},
             "response":{"state":"ready","commit":"abcdef1234567890","cached":false,"replaced":false,"stale":false,
               "trace":{"requestId":"req-1","joinedFlight":false,"outcome":{"state":"ready","httpStatus":200},
                 "timing":{"resolveMs":1000,"downloadMs":2000,"parseMs":1500,"mergeMs":300,"writeMs":200,"baselineMs":0,"totalMs":5000},
                 "gitlab":[{"atMs":${t0 + 100},"method":"GET","path":"/api/v4/projects/335/pipelines","status":200,"ms":100,"bytes":73400320},
                           {"atMs":${t0 + 200},"method":"GET","path":"/api/v4/projects/335/jobs/11/artifacts","status":500,"ms":50,"bytes":0,"error":"boom"}]}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        File(runDir, "samples.csv").writeText(
            """
            tMs,proc,pid,rssMb,cpuPct
            ${t0 + 1_000},covd,4242,200.0,40.0
            ${t0 + 2_000},covd,4242,220.5,60.0
            ${t0 + 1_500},ide,100,500.0,10.0
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        File(runDir, "snapshots.jsonl").writeText(
            buildString {
                appendLine("""{"tMs":$t0,"state":"idle","commit":"aaa","stale":false,"loading":false,"lastError":null,"baselineCommit":null,"coverageGeneration":0,"dumb":true,"covdInstanceId":"inst1234","covdPid":4242}""")
                appendLine("""{"tMs":${t0 + 4_000},"state":"ready","commit":"abcdef1234567890","stale":false,"loading":false,"lastError":null,"coverageGeneration":1,"dumb":false,"covdInstanceId":"inst1234","covdPid":4242}""")
            },
            Charsets.UTF_8,
        )

        File(runDir, "scenarios.jsonl").writeText(
            buildString {
                appendLine("""{"t":$t0,"scenario":"R1","phase":"start","msg":"cold start","data":{"branch":"master"}}""")
                appendLine("""{"t":${t0 + 5_200},"scenario":"R2","phase":"switch","msg":"git checkout --detach deadbeef","data":{"commit":"deadbeef"}}""")
                appendLine("""{"t":${t0 + 8_000},"scenario":"F2","phase":"balloon","msg":"token expired","data":{"kind":"balloon"}}""")
            },
            Charsets.UTF_8,
        )

        val cacheDir = File(runDir, "cache")
        cacheDir.mkdirs()
        File(cacheDir, "abc-raketa.cov4").writeBytes(ByteArray(2048) { 'a'.code.toByte() })
        File(cacheDir, "cache-index.json").writeText("{}\n")
    }

    private fun perfLine(tMs: Long, rest: String): String = "$tMs 10:00:00.000 $rest"

    private fun readJson(file: File): JsonObject = Json.parseToJsonElement(file.readText()) as JsonObject
}
