package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import com.github.yakov255.perlinecoverageinfo.realproject.harness.SandboxIde
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.PlanEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cold-start scenarios of the real-project suite: R0 (idle IDE without coverage loading),
 * R1 (cold cache, dual coverage) and R2 (IDE restart on a warm cache).
 *
 * Every scenario runs a real sandbox IDE on the shared raketa worktree, with `covd` in front of
 * the reflecting proxy. Assertions are about facts (commit, cached flag, artifact count, daemon
 * count, errors); time and memory numbers are recorded in `scenarios.jsonl` only.
 */
class ColdStartScenarios : ScenarioBase() {

    /**
     * R0: the worktree at the baseline commit, gutter hidden through the pre-seeded project state,
     * no coverage load at all. Waits for indexing, then idles for a minute and records the
     * baseline memory of an IDE that does no coverage work.
     */
    @Test
    fun r0IdleWithoutCoverage() {
        scenario("R0")
        val baseline = requireNotNull(plan.baseline) { "the plan has no baseline" }
        switchTo(baseline.sha)
        assertEquals("worktree must be at the baseline commit", baseline.sha, head())

        val gutterState = File(worktree, ".idea/coverageGutterVisibility.xml")
        val previous = gutterState.takeIf { it.isFile }?.readText()
        gutterState.parentFile?.mkdirs()
        gutterState.writeText(gutterVisibilityXml(false))
        // Stop the IDE before restoring the file: a graceful project close may write the
        // component state back, and a leftover `visible=false` would affect every other run.
        var started: SandboxIde? = null
        try {
            val launchAt = System.currentTimeMillis()
            val ide = startIde("s1r0", waitSmart = false).also { started = it }
            val readyAt = System.currentTimeMillis()
            val smart = ide.awaitSmartMode(SMART_MODE_TIMEOUT_MS)
            val smartAt = System.currentTimeMillis()
            // DumbService may report "not dumb" before the initial file scan is over; the scan
            // completion line is the honest end of indexing.
            val scanFinished = S1Support.awaitLogMarker(ide.ideaLog, SCAN_FINISHED_MARKER, SCAN_TIMEOUT_MS)
            val scanAt = System.currentTimeMillis()
            event(
                "r0-index",
                "indexing finished",
                eventData(
                    "readyMs" to (readyAt - launchAt),
                    "dumbFalseMs" to (smartAt - launchAt),
                    "dumb" to smart?.dumb?.toString(),
                    "scanMarkerSeen" to scanFinished,
                    "scanFinishedMs" to if (scanFinished) scanAt - launchAt else null,
                ),
            )
            println(
                "R0: ready in ${readyAt - launchAt} ms, dumb=false after ${smartAt - launchAt} ms, " +
                    "file scan finished after ${scanAt - launchAt} ms (marker seen=$scanFinished)",
            )

            pause(60_000, "idle minute after indexing, coverage loading suppressed")

            val bridge = requireNotNull(ide.readBridge()) { "the IDE wrote no bridge snapshot" }
            val stats = proxy.stats()
            assertFalse(
                "the gutter must be hidden by the pre-seeded project state (bridge gutterVisible=${bridge.gutterVisible})",
                bridge.gutterVisible ?: true,
            )
            assertFalse(
                "coverage must not load while the gutter is hidden, but the bridge state is ${bridge.state}",
                bridge.isLoaded,
            )
            assertEquals("a fresh cache root must hold no artifacts", 0, bridge.artifacts ?: -1)
            assertEquals("a suppressed load must produce no GitLab traffic", 0, stats.requests)
            assertTrue(
                "a suppressed load must not start covd, found ${sampler.findCovdPids()}",
                sampler.findCovdPids().isEmpty(),
            )

            val rows = S1Support.samples(ctx.samplesFile)
            val points = S1Support.heapPoints(File(cacheRoot, "perf"))
            val now = System.currentTimeMillis()
            val rss = S1Support.lastRss(rows, ide.name)
            val peakRss = S1Support.peakRss(rows, ide.name)
            val heap = S1Support.heapAtOrBefore(points, now)
            val peakHeap = S1Support.peakHeap(points, scanAt, now)
            event(
                "r0-idle",
                "idle minute recorded",
                eventData(
                    "state" to bridge.state,
                    "gutterVisible" to bridge.gutterVisible,
                    "artifacts" to bridge.artifacts,
                    "proxyRequests" to stats.requests,
                    "covdProcesses" to sampler.findCovdPids().size,
                    "rssMb" to rss?.let(::mb),
                    "peakRssMb" to peakRss?.let(::mb),
                    "heapMb" to heap?.let(::mb),
                    "peakHeapMb" to peakHeap?.let(::mb),
                ),
            )
            println(
                "R0: state=${bridge.state} gutter=${bridge.gutterVisible} rss=${rss?.let(::mb)} MB " +
                    "peakRss=${peakRss?.let(::mb)} MB heap=${heap?.let(::mb)} MB peakHeap=${peakHeap?.let(::mb)} MB",
            )
        } finally {
            started?.let { runCatching { stopIde(it) } }
            if (previous == null) gutterState.delete() else gutterState.writeText(previous)
        }
    }

    /**
     * R1: empty cache root, HEAD on the first tour branch, coverage branch `master` pre-seeded.
     * The daemon must answer `ready` with `cached=false`, a ready baseline and download the
     * coverage shards; exactly one daemon serves the run.
     */
    @Test
    fun r1ColdDualLoad() {
        scenario("R1")
        val branch = firstBranch()
        switchTo(branch.sha)
        assertEquals("worktree must be on the requested branch", branch.sha, head())
        assertTrue("the cache root must start empty: ${cacheRoot.listFiles()?.size}", cacheRoot.listFiles().orEmpty().isEmpty())

        val startedAt = System.currentTimeMillis()
        val ide = startIde("s1r1", waitSmart = false)
        val loaded = awaitLoaded(ide, branch.sha, timeoutMs = LOAD_TIMEOUT_MS)
        val loadedAt = System.currentTimeMillis()

        val ensure = ensureAnswers()
        val ready = S1Support.requireFound(
            ensure.lastOrNull { it.state == "ready" && it.commit == branch.sha },
            "no ready ensure answer for ${branch.sha.take(8)}; answers: ${describe(ensure)}",
        )

        assertEquals("primary load must be ready", "ready", ready.state)
        assertEquals("a cold cache must not be reported as cached", false, ready.cached)
        assertNull("lastError must stay null after a successful load: ${loaded.lastError}", loaded.lastError)
        assertEquals("exactly one covd process may serve this cache root", 1, sampler.findCovdPids().size)
        assertEquals("bridge report must be for the checked-out commit", branch.sha, loaded.commit)
        assertEquals("a cold load must be fresh, not stale", false, loaded.stale)
        assertTrue("coverage must expose files, got ${loaded.files}", (loaded.files ?: 0) > 0)
        assertNotNull("dual mode must attach a baseline commit", loaded.baselineCommit)
        assertEquals(
            "the baseline from the coverage branch must be ready (response: ${describeBaseline(ready)})",
            "ready",
            ready.baselineState,
        )

        val stats = proxy.stats()
        assertTrue(
            "dual load must download at least the 7 primary shards, started=${stats.artifactsStarted}",
            stats.artifactsStarted >= 7,
        )
        assertTrue(
            "dual load must complete at least 7 artifact downloads, completed=${stats.artifactsCompleted}",
            stats.artifactsCompleted >= 7,
        )
        assertTrue(
            "proxy traffic ${stats.bytesDown} bytes must stay within 20..150 MiB",
            stats.bytesDown in MIN_COLD_BYTES..MAX_COLD_BYTES,
        )

        val rows = S1Support.samples(ctx.samplesFile)
        val points = S1Support.heapPoints(File(cacheRoot, "perf"))
        event(
            "r1-loaded",
            "cold dual load finished",
            eventData(
                "branch" to branch.ref,
                "commit" to branch.sha,
                "sizeClass" to branch.sizeClass,
                "loadMs" to (loadedAt - startedAt),
                "state" to ready.state,
                "cached" to ready.cached,
                "replaced" to ready.replaced,
                "stale" to ready.stale,
                "baselineCommit" to loaded.baselineCommit,
                "baselineState" to ready.baselineState,
                "files" to loaded.files,
                "artifactsStarted" to stats.artifactsStarted,
                "artifactsCompleted" to stats.artifactsCompleted,
                "bytesDown" to stats.bytesDown,
                "proxyRequests" to stats.requests,
                "gitlabCalls" to ready.gitlabCalls,
                "ensureAnswers" to ensure.size,
                "timingTotalMs" to ready.timing["totalMs"],
                "timingResolveMs" to ready.timing["resolveMs"],
                "timingDownloadMs" to ready.timing["downloadMs"],
                "timingParseMs" to ready.timing["parseMs"],
                "timingMergeMs" to ready.timing["mergeMs"],
                "timingWriteMs" to ready.timing["writeMs"],
                "timingBaselineMs" to ready.timing["baselineMs"],
                "peakCovdRssMb" to S1Support.peakRss(rows, "covd")?.let(::mb),
                "peakIdeRssMb" to S1Support.peakRss(rows, ide.name)?.let(::mb),
                "peakIdeHeapMb" to S1Support.peakHeap(points, startedAt, loadedAt)?.let(::mb),
            ),
        )
        println(
            "R1: loaded ${branch.ref} in ${loadedAt - startedAt} ms; artifacts=${stats.artifactsCompleted} " +
                "bytes=${stats.bytesDown}; baseline=${ready.baselineState}; phases=${
                    ready.timing.filterKeys { it in PHASES }
                }",
        )
    }

    /**
     * R2: cold load, IDE restart with the same cache root and HEAD. The second run must serve the
     * cached artifact: no artifact downloads and `cached=true`.
     */
    @Test
    fun r2RestartUsesCache() {
        scenario("R2")
        val branch = firstBranch()
        switchTo(branch.sha)

        val cold = startIde("s1r2", waitSmart = false)
        awaitLoaded(cold, branch.sha, timeoutMs = LOAD_TIMEOUT_MS)
        val coldStats = proxy.stats()
        val coldReady = S1Support.requireFound(
            ensureAnswers().lastOrNull { it.state == "ready" && it.commit == branch.sha },
            "the cold run produced no ready answer for ${branch.sha.take(8)}",
        )
        assertTrue("the cold run must download artifacts before the restart", coldStats.artifactsCompleted > 0)
        assertEquals("the cold run must not be cached", false, coldReady.cached)

        stopIde(cold)
        pause(2_000, "settle after the IDE stop")
        val restartAt = System.currentTimeMillis()
        val artifactsBefore = proxy.stats().artifactsCompleted
        val requestsBefore = proxy.stats().requests
        val answersBefore = S1Support.answers(covdResponsesDir()).size

        val startedAt = System.currentTimeMillis()
        val restarted = startIde("s1r2", waitSmart = false)
        val loaded = awaitLoaded(restarted, branch.sha, timeoutMs = LOAD_TIMEOUT_MS)
        val loadedAt = System.currentTimeMillis()
        val statsAfter = proxy.stats()

        assertEquals(
            "the restart must not download any artifact (completed before=$artifactsBefore, after=${statsAfter.artifactsCompleted})",
            artifactsBefore,
            statsAfter.artifactsCompleted,
        )
        val journalRows = S1Support.journalArtifactsSince(File(ctx.dir, "proxy.jsonl"), restartAt)
        assertTrue("journal rows after the restart list artifact downloads: $journalRows", journalRows.isEmpty())

        val answers = S1Support.answers(covdResponsesDir())
        assertTrue(
            "the restart wrote no new covd answer (before=$answersBefore, after=${answers.size})",
            answers.size > answersBefore,
        )
        val second = answers.filter { it.atMs >= restartAt && it.kind == "ensure" }
        val ready = S1Support.requireFound(
            second.lastOrNull { it.state == "ready" && it.commit == branch.sha },
            "no ready ensure answer after the restart; answers: ${describe(second)}",
        )

        assertEquals("the restarted IDE must get the cached artifact", true, ready.cached)
        assertEquals("bridge report must be for the checked-out commit", branch.sha, loaded.commit)
        assertNull("lastError must stay null after the restart: ${loaded.lastError}", loaded.lastError)
        assertTrue("coverage must expose files after the restart, got ${loaded.files}", (loaded.files ?: 0) > 0)
        assertEquals("the restart must be fresh, not stale", false, loaded.stale)

        event(
            "r2-restart",
            "restart on a warm cache",
            eventData(
                "branch" to branch.ref,
                "commit" to branch.sha,
                "loadMs" to (loadedAt - startedAt),
                "cached" to ready.cached,
                "replaced" to ready.replaced,
                "stale" to ready.stale,
                "artifactsBefore" to artifactsBefore,
                "artifactsAfter" to statsAfter.artifactsCompleted,
                "journalArtifactRowsAfterRestart" to journalRows.size,
                "gitlabRequestsAfterRestart" to (statsAfter.requests - requestsBefore),
                "gitlabCallsInAnswer" to ready.gitlabCalls,
                "ensureAnswersAfterRestart" to second.size,
                "files" to loaded.files,
            ),
        )
        println(
            "R2: restart loaded in ${loadedAt - startedAt} ms, cached=${ready.cached}, " +
                "artifacts delta=${statsAfter.artifactsCompleted - artifactsBefore}, " +
                "GitLab requests delta=${statsAfter.requests - requestsBefore}",
        )
    }

    private fun firstBranch(): PlanEntry {
        val branches = tour(1)
        assertTrue("the plan has no local branch", branches.isNotEmpty())
        return branches.single()
    }

    private fun covdResponsesDir(): File = File(cacheRoot, "covd-responses")

    private fun ensureAnswers(): List<S1Support.CovdAnswer> =
        S1Support.answers(covdResponsesDir()).filter { it.kind == "ensure" }

    private fun describe(answers: List<S1Support.CovdAnswer>): String =
        answers.joinToString { "${it.state}/${it.commit?.take(8)}${it.code?.let { c -> "/$c" } ?: ""}" }

    private fun describeBaseline(answer: S1Support.CovdAnswer): String = buildString {
        append("baselineState=${answer.baselineState}")
        append(", baselineCommit=${answer.baselineCommit?.take(8)}")
        answer.reason?.let { append(", reason=$it") }
        answer.code?.let { append(", code=$it") }
        if (answer.errors.isNotEmpty()) append(", errors=${answer.errors}")
    }

    private fun mb(value: Double): String = String.format(java.util.Locale.ROOT, "%.0f", value)

    private fun gutterVisibilityXml(visible: Boolean): String = """
        <project version="4">
          <component name="CoverageGutterVisibility">
            <option name="visible" value="$visible" />
          </component>
        </project>
    """.trimIndent() + "\n"

    private companion object {
        const val LOAD_TIMEOUT_MS = 900_000L
        const val SCAN_TIMEOUT_MS = 600_000L
        const val SCAN_FINISHED_MARKER = "Scanning completed for"
        const val SMART_MODE_TIMEOUT_MS = 900_000L
        const val MIN_COLD_BYTES = 20L * 1024L * 1024L
        const val MAX_COLD_BYTES = 150L * 1024L * 1024L
        val PHASES = setOf("gitMs", "resolveMs", "downloadMs", "parseMs", "mergeMs", "writeMs", "baselineMs", "totalMs")
    }
}

/**
 * Reading of the harness evidence files: `samples.csv`, the perf logs' `ev=sample` heap lines,
 * the saved covd answers and the proxy journal.
 */
internal object S1Support {

    /** Fails the scenario with [message] when [value] is null; keeps the call site readable. */
    fun <T> requireFound(value: T?, message: String): T = value ?: throw AssertionError(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- resource samples ------------------------------------------------------------------

    data class Sample(val tMs: Long, val proc: String, val pid: Long, val rssMb: Double)

    fun samples(file: File): List<Sample> {
        if (!file.isFile) return emptyList()
        return file.readLines().drop(1).mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 4) return@mapNotNull null
            val t = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
            val rss = parts[3].trim().toDoubleOrNull() ?: return@mapNotNull null
            Sample(t, parts[1].trim(), parts[2].trim().toLongOrNull() ?: 0L, rss)
        }
    }

    fun peakRss(rows: List<Sample>, proc: String): Double? =
        rows.filter { it.proc == proc }.maxOfOrNull { it.rssMb }

    fun lastRss(rows: List<Sample>, proc: String): Double? =
        rows.filter { it.proc == proc }.maxByOrNull { it.tMs }?.rssMb

    data class HeapPoint(val tMs: Long, val usedMb: Double)

    fun heapPoints(perfDir: File): List<HeapPoint> {
        if (!perfDir.isDirectory) return emptyList()
        return perfDir.listFiles { file -> file.isFile && file.name.endsWith(".log") }.orEmpty()
            .flatMap { file -> file.readLines().mapNotNull(::parseSampleLine) }
            .sortedBy { it.tMs }
    }

    private fun parseSampleLine(line: String): HeapPoint? {
        if (!line.contains(" ev=sample")) return null
        val tMs = line.substringBefore(' ').toLongOrNull() ?: return null
        val used = line.split(WHITESPACE).firstOrNull { it.startsWith("heapUsedMB=") }
            ?.substringAfter('=')?.toDoubleOrNull() ?: return null
        return HeapPoint(tMs, used)
    }

    fun heapAtOrBefore(points: List<HeapPoint>, tMs: Long): Double? =
        points.lastOrNull { it.tMs <= tMs }?.usedMb

    fun peakHeap(points: List<HeapPoint>, fromMs: Long, toMs: Long): Double? =
        points.filter { it.tMs in fromMs..toMs }.maxOfOrNull { it.usedMb }

    /** Waits until [log] contains [marker]; false on timeout or when the process is gone. */
    fun awaitLogMarker(log: File, marker: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (log.isFile && log.readText().contains(marker)) return true
            Thread.sleep(1_000)
        }
        return log.isFile && log.readText().contains(marker)
    }

    // ---- saved covd answers ----------------------------------------------------------------

    data class CovdAnswer(
        val file: File,
        val seq: Long?,
        val atMs: Long,
        val kind: String,
        val state: String,
        val cached: Boolean?,
        val replaced: Boolean?,
        val stale: Boolean?,
        val commit: String?,
        val baselineCommit: String?,
        val baselineState: String?,
        val files: Int?,
        val reason: String?,
        val code: String?,
        val timing: Map<String, Long>,
        val gitlabCalls: Int,
        val gitlabBytes: Long,
        val errors: List<String>,
    )

    fun answers(dir: File): List<CovdAnswer> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull(::parseAnswer)
            .sortedBy { it.atMs }
    }

    private fun parseAnswer(file: File): CovdAnswer? {
        val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return null
        val response = root["response"] as? JsonObject
        val trace = response?.get("trace") as? JsonObject
        val timing = (trace?.get("timing") as? JsonObject).orEmpty()
            .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }
            .toMap()
        val gitlab = trace?.get("gitlab") as? JsonArray
        val errors = (trace?.get("errors") as? JsonArray).orEmpty().mapNotNull { element ->
            val error = element as? JsonObject ?: return@mapNotNull null
            val code = error.string("code")
            val scope = error.string("scope")
            val message = error.string("message") ?: error.string("detail") ?: error.string("hint")
            listOfNotNull(scope, code, message).joinToString(":").ifEmpty { null }
        }
        val nameState = file.name.removeSuffix(".json").substringAfterLast('-')
        return CovdAnswer(
            file = file,
            seq = root.long("seq"),
            atMs = root.long("atMs") ?: file.lastModified(),
            kind = root.string("kind") ?: "ensure",
            state = response?.string("state") ?: nameState,
            cached = response?.bool("cached"),
            replaced = response?.bool("replaced"),
            stale = response?.bool("stale"),
            commit = response?.string("commit"),
            baselineCommit = response?.string("baselineCommit"),
            baselineState = response?.string("baselineState"),
            files = (response?.get("stats") as? JsonObject)?.int("files"),
            reason = response?.string("reason"),
            code = response?.string("code"),
            timing = timing,
            gitlabCalls = gitlab?.size ?: 0,
            gitlabBytes = gitlab.orEmpty().sumOf { call -> (call as? JsonObject)?.long("bytes") ?: 0L },
            errors = errors,
        )
    }

    // ---- proxy journal ---------------------------------------------------------------------

    data class JournalArtifact(val tMs: Long, val bytesDown: Long)

    /** Artifact requests that started at or after [sinceMs]. The journal flushes every row. */
    fun journalArtifactsSince(journal: File, sinceMs: Long): List<JournalArtifact> {
        if (!journal.isFile) return emptyList()
        return journal.readLines().mapNotNull { line ->
            val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
            val tMs = row.long("t") ?: return@mapNotNull null
            if (tMs < sinceMs) return@mapNotNull null
            val job = row["artifactJobId"] as? JsonPrimitive ?: return@mapNotNull null
            if (job.content == "null") return@mapNotNull null
            JournalArtifact(tMs, row.long("bytesDown") ?: 0L)
        }
    }

    // ---- covd cache index ------------------------------------------------------------------

    data class CacheEntry(
        val commit: String,
        val component: String,
        val branch: String?,
        val pipelineId: Long?,
        val files: Int,
    ) {
        val fileName: String get() = "$commit-${component.replace('/', '_')}.cov4"
    }

    fun cacheIndex(file: File): List<CacheEntry> {
        if (!file.isFile) return emptyList()
        val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return emptyList()
        val entries = root["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val commit = entry.string("commitHash") ?: return@mapNotNull null
            CacheEntry(
                commit = commit,
                component = entry.string("component").orEmpty(),
                branch = entry.string("branch"),
                pipelineId = entry.long("pipelineId"),
                files = entry.int("totalFiles") ?: 0,
            )
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
