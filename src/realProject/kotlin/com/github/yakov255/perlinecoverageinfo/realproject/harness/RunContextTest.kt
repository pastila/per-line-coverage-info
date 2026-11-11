package com.github.yakov255.perlinecoverageinfo.realproject.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Run directory, JSONL writers, snapshot polling and artifact copies. */
class RunContextTest {

    private val temp: File = Files.createTempDirectory("rp-run-test").toFile()

    @After
    fun deleteTemp() {
        temp.deleteRecursively()
    }

    @Test
    fun `run id is sortable and filesystem safe`() {
        val id = RunContext.newRunId("R1 Cold Start/ü", atMs = 1_760_000_000_000)
        assertEquals("20251009-085320-r1-cold-start", id)
        assertEquals("20251009-085320", RunContext.newRunId(null, atMs = 1_760_000_000_000))
    }

    @Test
    fun `create makes the documented layout`() {
        val output = File(temp, "build/real-project")
        val context = RunContext.create("smoke", output, atMs = 1_760_000_000_000)
        try {
            assertTrue(context.dir.isDirectory)
            assertEquals("20251009-085320-smoke", context.runId)
            assertTrue(context.scenariosFile.isFile)
            assertTrue(context.snapshotsFile.isFile)
            assertTrue(context.perfDir.isDirectory)
            assertTrue(context.covdResponsesDir.isDirectory)
            assertTrue(context.machineFile.parentFile.isDirectory)
        } finally {
            context.close()
        }
    }

    @Test
    fun `an existing run directory is never reused silently`() {
        val output = File(temp, "dupes")
        RunContext.create("x", output, atMs = 1_760_000_000_000).close()
        val error = runCatching { RunContext.create("x", output, atMs = 1_760_000_000_000) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `scenario event has the documented contract keys`() {
        val context = RunContext.create("events", File(temp, "out"), atMs = 1_760_000_000_000)
        try {
            context.event("R1", "start", "cold start", jsonObjectOf("branch" to "master"))
            val line = context.scenariosFile.readLines().single()
            val row = Json.parseToJsonElement(line).jsonObject
            assertEquals(
                setOf("t", "scenario", "phase", "msg", "data"),
                row.keys,
            )
            assertEquals("R1", row["scenario"]!!.jsonPrimitive.content)
            assertEquals("start", row["phase"]!!.jsonPrimitive.content)
            assertEquals("cold start", row["msg"]!!.jsonPrimitive.content)
            assertEquals("master", row["data"]!!.jsonObject["branch"]!!.jsonPrimitive.content)
        } finally {
            context.close()
        }
    }

    @Test
    fun `snapshot polling appends rows with tMs and stops cleanly`() {
        val context = RunContext.create("poll", File(temp, "poll-out"), atMs = 1_760_000_000_000)
        val snapshot = BridgeSnapshot.parse("""{"state":"LOADED","commit":"abc","dumb":false}""")
        val handle = context.startSnapshotPolling({ snapshot }, intervalMs = 20)
        try {
            val deadline = System.currentTimeMillis() + 3_000
            while (context.snapshotsFile.readLines().size < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        } finally {
            handle.close()
            context.close()
        }
        val rows = context.snapshotsFile.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertTrue("expected at least two snapshots, got ${rows.size}", rows.size >= 2)
        assertTrue(rows.all { it["tMs"]!!.jsonPrimitive.content.toLong() > 0 })
        assertEquals("LOADED", rows.first()["state"]!!.jsonPrimitive.content)
        assertEquals("abc", rows.first()["commit"]!!.jsonPrimitive.content)
        // No rows are appended after close.
        val afterClose = context.snapshotsFile.readLines().size
        Thread.sleep(100)
        assertEquals(afterClose, context.snapshotsFile.readLines().size)
    }

    @Test
    fun `copies only the expected files into the run directory`() {
        val source = File(temp, "source").apply { mkdirs() }
        File(source, "run-1.log").writeText("ev=span")
        File(source, "notes.txt").writeText("ignore")
        val responses = File(temp, "responses").apply { mkdirs() }
        File(responses, "0001-20260101-000000-ensure-ready.json").writeText("{}")
        File(responses, "0002-20260101-000001-ensure-error.json").writeText("{}")
        File(responses, "readme.txt").writeText("ignore")

        val context = RunContext.create("copy", File(temp, "copy-out"), atMs = 1_760_000_000_000)
        try {
            assertEquals(listOf("run-1.log"), context.copyPerfLogs(source))
            assertEquals(
                listOf("0001-20260101-000000-ensure-ready.json", "0002-20260101-000001-ensure-error.json"),
                context.copyCovdResponses(responses),
            )
            assertEquals("ev=span", File(context.perfDir, "run-1.log").readText())
            assertEquals(emptyList<String>(), context.copyPerfLogs(null))
        } finally {
            context.close()
        }
    }

    @Test
    fun `machine info has no credentials`() {
        val context = RunContext.create("meta", File(temp, "meta-out"), atMs = 1_760_000_000_000)
        try {
            val info = context.writeMachineInfo(mapOf("ideHome" to "/opt/idea"))
            assertEquals(context.runId, info["runId"]!!.jsonPrimitive.content)
            assertEquals("/opt/idea", info["ideHome"]!!.jsonPrimitive.content)
            val text = context.machineFile.readText().lowercase()
            assertFalse(text.contains("token"))
            assertFalse(text.contains("bearer"))
            assertTrue(context.machineFile.isFile)
        } finally {
            context.close()
        }
    }
}
