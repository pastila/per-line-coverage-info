package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import com.github.yakov255.perlinecoverageinfo.realproject.harness.SandboxIde
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.PlanEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min

/**
 * R7 — two IDEs, one cache root (docs/real-project-testing.md).
 *
 * (a) Both IDEs open the same worktree commit with a cold cache: exactly one covd process with
 * one instance id has to serve them, and the second load must join the running build or hit the
 * cache instead of downloading the shards again.
 * (b) The two IDEs work on two different commits at the same time (the second one in the extra
 * worktree `/tmp/raketa-load-b`): both loads complete, the same daemon serves them, their
 * artifact downloads overlap in time and its peak RSS is recorded.
 * (c) One IDE is stopped: covd stays alive with the same pid, and the surviving IDE switches
 * branch in its own worktree, loads again and keeps the same daemon.
 *
 * The branches come from `plan.json` and each switch is a detached `git checkout` outside the
 * user's checkout; `/tmp/raketa-load-b` is created and removed by the scenario itself.
 */
class TwoIdeScenarios : ScenarioBase() {

    private val tourCount: Int =
        (System.getProperty("realProject.tour")
            ?: System.getenv("REAL_PROJECT_TOUR")
            ?: "8").toIntOrNull() ?: 8

    private val worktreeB = File("/tmp/raketa-load-b")

    private var ideA: SandboxIde? = null
    private var ideB: SandboxIde? = null
    private var worktreeBCreated = false
    private var scenarioStarted = false

    @After
    fun cleanupScenarios() {
        runCatching { ideB?.let { stopIde(it) } }
        runCatching { ideA?.let { stopIde(it) } }
        ideA = null
        ideB = null
        if (worktreeBCreated) runCatching { removeWorktreeB() }
        if (scenarioStarted) {
            runCatching { switchTo(plan.baseline?.sha ?: return@runCatching) }
        }
    }

    @Test
    fun r7TwoIdesOneSharedCache() {
        scenario("R7", "r7-two-ides")
        scenarioStarted = true
        val baseline = plan.baseline
        assumeTrue("plan.json has no baseline", baseline != null)
        baseline!!

        switchTo(baseline.sha)
        removeStaleWorktreeB()

        // ---- (a) same commit, cold cache -------------------------------------------------
        val phaseAStart = System.currentTimeMillis()
        val a1 = startIde("ide-1", worktree, waitSmart = false).also { ideA = it }
        val a2 = startIde("ide-2", worktree, waitSmart = false).also { ideB = it }
        a1.awaitSmartMode()
        a2.awaitSmartMode()
        val a1snap = awaitLoaded(a1, baseline.sha, timeoutMs = 1_200_000)
        val a2snap = awaitLoaded(a2, baseline.sha, timeoutMs = 1_200_000)
        val phaseAEnd = System.currentTimeMillis()

        assertNull("IDE-1 reported lastError=${a1snap.lastError}", a1snap.lastError)
        assertNull("IDE-2 reported lastError=${a2snap.lastError}", a2snap.lastError)
        assertEquals("IDE-1 commit", baseline.sha, a1snap.commit)
        assertEquals("IDE-2 commit", baseline.sha, a2snap.commit)

        val phaseAPids = sampler.findCovdPids()
        assertEquals("exactly one covd must serve both IDEs, got $phaseAPids", 1, phaseAPids.size)
        assertEquals("IDE-1 covd pid", phaseAPids.single(), a1snap.covdPid)
        assertEquals("IDE-2 covd pid", phaseAPids.single(), a2snap.covdPid)
        assertNotNull("IDE-1 has no covd instance id", a1snap.covdInstanceId)
        assertEquals("both IDEs must talk to one covd instance", a1snap.covdInstanceId, a2snap.covdInstanceId)

        val phaseAAnswers = S2Evidence.answers(cacheRoot)
            .filter { it.component == "raketa" && it.state == "ready" && it.atMs in phaseAStart..(phaseAEnd + 5_000) }
        assertTrue("no ready raketa answer in phase A: ${S2Evidence.answers(cacheRoot).map { "${it.component}/${it.state}" }}", phaseAAnswers.isNotEmpty())
        val phaseAArtifacts = S2Evidence.journal(ctx.dir).filter { it.artifactJobId != null && it.t in phaseAStart..phaseAEnd }
        val singleLoad = baseline.shards
        val budget = ceil(singleLoad * 1.3).toInt()
        event(
            "phase-a",
            "same commit, cold cache",
            eventData(
                "wallMs" to (phaseAEnd - phaseAStart),
                "answers" to phaseAAnswers.size,
                "joinedFlight" to phaseAAnswers.count { it.joinedFlight },
                "sharedBuild" to phaseAAnswers.count { it.sharedBuild },
                "cached" to phaseAAnswers.count { it.cached },
                "artifacts" to phaseAArtifacts.size,
                "artifactBytes" to phaseAArtifacts.sumOf { it.bytesDown },
                "instance" to a1snap.covdInstanceId,
                "pid" to phaseAPids.single(),
                "shards" to singleLoad,
                "budget" to budget,
                "states" to phaseAAnswers.joinToString(",") { "j=${it.joinedFlight}/c=${it.cached}/ms=${it.elapsedMs}" },
            ),
        )
        assertTrue(
            "two IDEs on one commit must coalesce or hit the cache: " +
                phaseAAnswers.joinToString(",") { "joined=${it.joinedFlight} cached=${it.cached} shared=${it.sharedBuild}" },
            phaseAAnswers.any { it.joinedFlight || it.sharedBuild || it.cached },
        )
        assertTrue(
            "artifacts of one shared load: ${phaseAArtifacts.size} > $budget (1.3 x $singleLoad shards)",
            phaseAArtifacts.size <= budget,
        )
        assertTrue(
            "a cold load must download the shards, got ${phaseAArtifacts.size} of $singleLoad",
            phaseAArtifacts.size >= singleLoad,
        )
        val indexAfterA = S2Evidence.cacheIndex(cacheRoot)
        assertEquals(
            "phase A must cache exactly one artifact: ${indexAfterA.map { it.key }}",
            1,
            indexAfterA.size,
        )
        assertEquals("phase A cached component", "raketa", indexAfterA.single().component)
        assertEquals("phase A cached commit", baseline.sha, indexAfterA.single().commit)

        val logProblems = mutableListOf<String>()
        logProblems += S2Evidence.pluginErrorLines(a1.ideaLog).map { "phase-a ide-1: $it" }
        logProblems += S2Evidence.pluginErrorLines(a2.ideaLog).map { "phase-a ide-2: $it" }

        // ---- (b) two commits at the same time --------------------------------------------
        val eligible = eligibleTourBranches(baseline.sha)
        assumeTrue(
            "plan.json has fewer than 3 branches whose merge-base is the coverage branch: " +
                eligible.map { it.ref },
            eligible.size >= 3,
        )
        val branchA = eligible[0]
        val branchB = eligible[1]
        val branchC = eligible[2]
        event(
            "branches",
            "A=${branchA.ref} B=${branchB.ref} C=${branchC.ref}",
            eventData(
                "shaA" to branchA.sha,
                "shaB" to branchB.sha,
                "shaC" to branchC.sha,
                "bytesA" to branchA.bytes,
                "bytesB" to branchB.bytes,
                "tour" to tourCount,
            ),
        )

        stopIde(a1)
        stopIde(a2)
        ideA = null
        ideB = null
        switchTo(branchA.sha)
        createWorktreeB(branchB.sha)

        // The local proxy downloads 25 MB of shards in ~3 s, and the second IDE needs ~10 s to
        // reach its `ensure`, so an unassisted run would never show concurrency. A 20 s delay per
        // artifact request stretches both loads over the second IDE's start; the delay is
        // recorded and removed as soon as both are loaded (the traffic itself is unchanged).
        val injectedDelayMs = 20_000L
        proxy.delay(injectedDelayMs, "/jobs/\\d+/artifacts")
        event("fault", "20 s delay on artifact requests to expose concurrency", eventData("delayMs" to injectedDelayMs))

        val phaseBStart = System.currentTimeMillis()
        val b1 = startIde("ide-1", worktree, waitSmart = false).also { ideA = it }
        val b2 = startIde("ide-2", worktreeB, waitSmart = false).also { ideB = it }
        b1.awaitSmartMode()
        b2.awaitSmartMode()
        val b1snap = awaitLoaded(b1, branchA.sha, timeoutMs = 1_500_000)
        val b2snap = awaitLoaded(b2, branchB.sha, timeoutMs = 1_500_000)
        val phaseBEnd = System.currentTimeMillis()
        proxy.clearFaults()
        event("fault", "artifact delay cleared", eventData("t" to phaseBEnd))

        assertNull("IDE-1 reported lastError=${b1snap.lastError}", b1snap.lastError)
        assertNull("IDE-2 reported lastError=${b2snap.lastError}", b2snap.lastError)
        assertEquals("IDE-1 commit", branchA.sha, b1snap.commit)
        assertEquals("IDE-2 commit", branchB.sha, b2snap.commit)
        assertEquals("IDE-1 baseline", baseline.sha, b1snap.baselineCommit)
        assertEquals("IDE-2 baseline", baseline.sha, b2snap.baselineCommit)

        val phaseBPids = sampler.findCovdPids()
        assertEquals("exactly one covd must serve both IDEs, got $phaseBPids", 1, phaseBPids.size)
        assertEquals("IDE-1 covd pid", phaseBPids.single(), b1snap.covdPid)
        assertEquals("IDE-2 covd pid", phaseBPids.single(), b2snap.covdPid)
        assertEquals("instance id must survive the second phase", a1snap.covdInstanceId, b1snap.covdInstanceId)
        assertEquals("IDE-2 must use the same instance", b1snap.covdInstanceId, b2snap.covdInstanceId)

        val phaseBAnswers = S2Evidence.answers(cacheRoot)
            .filter { it.component == "raketa" && it.state == "ready" && it.atMs in phaseBStart..(phaseBEnd + 5_000) }
        // A load answers more than once (the 30 s poller then gets `cached=true`), so the phase
        // window is the union of that commit's answers and the download is the longest one.
        val answersA = phaseBAnswers.filter { it.commit == branchA.sha }
        val answersB = phaseBAnswers.filter { it.commit == branchB.sha }
        val answerA = answersA.maxByOrNull { it.elapsedMs }
        val answerB = answersB.maxByOrNull { it.elapsedMs }
        assertNotNull(
            "no ready answer for branch A (${branchA.sha.take(8)}): " +
                phaseBAnswers.map { "${it.shortCommit}/${it.state}/${it.code}" },
            answerA,
        )
        assertNotNull(
            "no ready answer for branch B (${branchB.sha.take(8)}): " +
                phaseBAnswers.map { "${it.shortCommit}/${it.state}/${it.code}" },
            answerB,
        )
        answerA!!
        answerB!!
        val windowA = loadWindow(answersA)
        val windowB = loadWindow(answersB)
        val loadsOverlap = windowA.first <= windowB.last && windowB.first <= windowA.last

        val jobOwners = HashMap<Int, String>()
        for (answer in phaseBAnswers) {
            val commit = answer.commit ?: continue
            for (jobId in answer.artifactJobIds) jobOwners.putIfAbsent(jobId, commit)
        }
        val phaseBRows = S2Evidence.journal(ctx.dir).filter { it.t in phaseBStart..phaseBEnd }
        val rowsA = phaseBRows.filter { row -> row.artifactJobId != null && jobOwners[row.artifactJobId] == branchA.sha }
        val rowsB = phaseBRows.filter { row -> row.artifactJobId != null && jobOwners[row.artifactJobId] == branchB.sha }
        val downloadsOverlap = overlaps(rowsA, rowsB)
        val phaseBArtifacts = phaseBRows.filter { it.artifactJobId != null }
        val twoLoads = branchA.shards + branchB.shards
        val budgetB = ceil(twoLoads * 1.3).toInt()
        event(
            "phase-b",
            "different commits at the same time",
            eventData(
                "wallMs" to (phaseBEnd - phaseBStart),
                "loadAms" to answerA.elapsedMs,
                "loadBms" to answerB.elapsedMs,
                "answersA" to answersA.joinToString(",") { "el=${it.elapsedMs} cached=${it.cached} joined=${it.joinedFlight} at=${it.atMs}" },
                "answersB" to answersB.joinToString(",") { "el=${it.elapsedMs} cached=${it.cached} joined=${it.joinedFlight} at=${it.atMs}" },
                "loadsOverlap" to loadsOverlap,
                "loadsOverlapMs" to overlapMs(windowA, windowB),
                "injectedDelayMs" to injectedDelayMs,
                "downloadsOverlap" to downloadsOverlap,
                "attributedRowsA" to rowsA.size,
                "attributedRowsB" to rowsB.size,
                "artifacts" to phaseBArtifacts.size,
                "artifactBytes" to phaseBArtifacts.sumOf { it.bytesDown },
                "shards" to twoLoads,
                "budget" to budgetB,
                "baselineDownloads" to phaseBRows.count { row -> row.artifactJobId != null && jobOwners[row.artifactJobId] == baseline.sha },
                "peakCovdRssMb" to S2Evidence.format(S2Evidence.peakRss(ctx.samplesFile, "covd", phaseBStart, phaseBEnd)),
                "peakIde1RssMb" to S2Evidence.format(S2Evidence.peakRss(ctx.samplesFile, "ide-1", phaseBStart, phaseBEnd)),
                "peakIde2RssMb" to S2Evidence.format(S2Evidence.peakRss(ctx.samplesFile, "ide-2", phaseBStart, phaseBEnd)),
            ),
        )
        assertTrue("the two loads did not overlap: A=$windowA B=$windowB", loadsOverlap)
        assertTrue(
            "the artifact downloads of the two IDEs did not overlap in the proxy journal " +
                "(rows A=${rowsA.size}, B=${rowsB.size})",
            downloadsOverlap || (rowsA.isEmpty() && rowsB.isEmpty() && loadsOverlap),
        )
        assertTrue(
            "artifacts of two branch loads: ${phaseBArtifacts.size} > $budgetB (1.3 x $twoLoads shards)",
            phaseBArtifacts.size <= budgetB,
        )
        assertEquals(
            "the baseline must come from the phase-A cache, not a new download; owners=$jobOwners",
            0,
            phaseBRows.count { row -> row.artifactJobId != null && jobOwners[row.artifactJobId] == baseline.sha },
        )
        val indexAfterB = S2Evidence.cacheIndex(cacheRoot)
        assertEquals(
            "duplicate cache keys after two concurrent loads: ${indexAfterB.map { it.key }}",
            indexAfterB.size,
            indexAfterB.distinctBy { it.commit to it.component }.size,
        )
        assertTrue(
            "cache-index after phase B must hold the baseline and both branches: ${indexAfterB.map { it.key }}",
            listOf(baseline.sha, branchA.sha, branchB.sha).all { sha ->
                indexAfterB.any { it.commit == sha && it.component == "raketa" }
            },
        )
        assertTrue("a corrupt cache index was left behind: ${S2Evidence.corruptIndexFiles(cacheRoot)}", S2Evidence.corruptIndexFiles(cacheRoot).isEmpty())

        logProblems += S2Evidence.pluginErrorLines(b1.ideaLog).map { "phase-b ide-1: $it" }
        logProblems += S2Evidence.pluginErrorLines(b2.ideaLog).map { "phase-b ide-2: $it" }

        // ---- (c) one IDE stops, the other switches branch ---------------------------------
        val pidBefore = sampler.findCovdPids()
        stopIde(b1)
        ideA = null
        Thread.sleep(2_000)
        val pidAfterStop = sampler.findCovdPids()
        assertEquals("covd must survive the IDE that spawned it", pidBefore, pidAfterStop)
        assertEquals("exactly one covd after stopping IDE-1, got $pidAfterStop", 1, pidAfterStop.size)
        val surviving = b2.readBridge()
        assertNotNull("IDE-2 lost its bridge snapshot", surviving)
        assertEquals("IDE-2 covd pid after IDE-1 stopped", pidAfterStop.single(), surviving!!.covdPid)

        switchWorktreeB(branchC.sha)
        val phaseCStart = System.currentTimeMillis()
        val c = awaitLoaded(b2, branchC.sha, timeoutMs = 1_500_000)
        val phaseCEnd = System.currentTimeMillis()
        assertNull("IDE-2 reported lastError=${c.lastError}", c.lastError)
        assertEquals("IDE-2 commit", branchC.sha, c.commit)
        assertEquals("IDE-2 baseline", baseline.sha, c.baselineCommit)
        assertEquals("covd must not restart on a branch switch", pidAfterStop.single(), c.covdPid)
        assertEquals("covd instance must not change", b2snap.covdInstanceId, c.covdInstanceId)
        assertNotEquals("the switch must produce fresh coverage, not the old artifact", branchB.sha, c.commit)

        val phaseCArtifacts = S2Evidence.journal(ctx.dir).filter { it.artifactJobId != null && it.t in phaseCStart..phaseCEnd }
        event(
            "phase-c",
            "IDE-2 switched to ${branchC.ref}",
            eventData(
                "wallMs" to (phaseCEnd - phaseCStart),
                "artifacts" to phaseCArtifacts.size,
                "artifactBytes" to phaseCArtifacts.sumOf { it.bytesDown },
                "shards" to branchC.shards,
                "pid" to c.covdPid,
                "instance" to c.covdInstanceId,
                "peakCovdRssMb" to S2Evidence.format(S2Evidence.peakRss(ctx.samplesFile, "covd", phaseCStart, phaseCEnd)),
            ),
        )
        assertTrue(
            "a new branch needs its shards, got ${phaseCArtifacts.size} rows in ${phaseCEnd - phaseCStart} ms",
            phaseCArtifacts.isNotEmpty(),
        )
        assertTrue(
            "phase C must download only the branch shards (baseline is cached): " +
                "${phaseCArtifacts.size} > ${ceil(branchC.shards * 1.3).toInt()}",
            phaseCArtifacts.size <= ceil(branchC.shards * 1.3).toInt(),
        )

        logProblems += S2Evidence.pluginErrorLines(b2.ideaLog).map { "phase-c ide-2: $it" }
        event(
            "log",
            "plugin SEVERE/ERROR lines=${logProblems.size}",
            eventData(
                "lines" to logProblems.joinToString(" ¶ ").take(4_000),
                "severeCount" to (S2Evidence.severeCount(b1.ideaLog) + S2Evidence.severeCount(b2.ideaLog)),
            ),
        )
        assertTrue("plugin logged SEVERE/ERROR lines: $logProblems", logProblems.isEmpty())

        // ---- shared cache index integrity ------------------------------------------------
        val indexAfterC = S2Evidence.cacheIndex(cacheRoot)
        assertEquals(
            "duplicate cache keys at the end: ${indexAfterC.map { it.key }}",
            indexAfterC.size,
            indexAfterC.distinctBy { it.commit to it.component }.size,
        )
        val expected = listOf(baseline.sha, branchA.sha, branchB.sha, branchC.sha)
        expected.forEach { sha ->
            assertTrue(
                "cache-index is missing the raketa artifact of ${sha.take(8)}: ${indexAfterC.map { it.key }}",
                indexAfterC.any { it.commit == sha && it.component == "raketa" },
            )
        }
        assertTrue(
            "cache-index lost entries between phases: before=${indexAfterB.map { it.key }} after=${indexAfterC.map { it.key }}",
            indexAfterC.map { it.commit to it.component }.containsAll(indexAfterB.map { it.commit to it.component }),
        )
        expected.forEach { sha ->
            assertTrue(
                "cache file for ${sha.take(8)} is missing",
                File(File(cacheRoot, S2Evidence.PROJECT_ID), "$sha-raketa.cov4").isFile,
            )
        }
        assertTrue("a corrupt cache index was left behind: ${S2Evidence.corruptIndexFiles(cacheRoot)}", S2Evidence.corruptIndexFiles(cacheRoot).isEmpty())
        event(
            "cache.index",
            "${indexAfterC.size} entries",
            eventData(
                "keys" to indexAfterC.joinToString(",") { "${it.key}(${it.files}f/${it.lines}l)" },
                "generation" to c.coverageGeneration,
            ),
        )

        // ---- cleanup before the report so the next agent finds a clean baseline -----------
        stopIde(b2)
        ideB = null
        removeWorktreeB()
        switchTo(baseline.sha)

        val report = finish()
        assertTrue("no report written", report.isFile)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Tour branches whose merge-base with the coverage branch is that branch itself, so the
     * baseline is the artifact phase A already cached. Capped by `realProject.tour`.
     */
    private fun eligibleTourBranches(coverageBranchSha: String): List<PlanEntry> {
        val limit = tourCount.coerceIn(1, plan.branches.size.coerceAtLeast(1))
        return plan.branches
            .asSequence()
            .filter { it.localSha }
            .filter { mergeBase(it.sha, coverageBranchSha) == coverageBranchSha }
            .take(limit)
            .toList()
    }

    private fun mergeBase(a: String, b: String): String? =
        git(worktree, "merge-base", a, b).trim().takeIf { it.isNotEmpty() }

    private fun createWorktreeB(sha: String) {
        removeStaleWorktreeB()
        git(repoDir(), "worktree", "add", "--detach", worktreeB.absolutePath, sha)
        worktreeBCreated = true
        event("worktree-b", "created at ${sha.take(8)}", eventData("path" to worktreeB.absolutePath))
        check(worktreeB.isDirectory) { "worktree-b was not created: ${worktreeB.absolutePath}" }
    }

    private fun removeStaleWorktreeB() {
        if (!worktreeB.exists()) return
        if (worktreeB.absolutePath !in registeredWorktrees()) {
            error("${worktreeB.absolutePath} exists but is not a worktree of ${repoDir()}; refusing to touch it")
        }
        git(repoDir(), "worktree", "remove", "--force", worktreeB.absolutePath)
        git(repoDir(), "worktree", "prune")
        worktreeBCreated = false
    }

    private fun removeWorktreeB() {
        if (worktreeB.exists() && worktreeB.absolutePath in registeredWorktrees()) {
            git(repoDir(), "worktree", "remove", "--force", worktreeB.absolutePath)
        }
        git(repoDir(), "worktree", "prune")
        worktreeBCreated = false
    }

    private fun switchWorktreeB(sha: String) {
        check(preparer.hasCommit(sha)) { "commit $sha is not in the local repository" }
        val tracked = git(worktreeB, "status", "--porcelain", "--untracked-files=no")
        check(tracked.isBlank()) { "worktree-b has tracked changes, refusing to switch: ${tracked.lines().first()}" }
        git(worktreeB, "checkout", "--detach", "-q", sha)
        event("checkout-b", sha.take(8))
    }

    private fun registeredWorktrees(): List<String> =
        git(repoDir(), "worktree", "list", "--porcelain").lineSequence()
            .filter { it.startsWith("worktree ") }
            .map { File(it.removePrefix("worktree ").trim()).absolutePath }
            .toList()

    private fun repoDir(): File = File(plan.repo)

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(300, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "git ${args.joinToString(" ")} failed: ${output.trim()}"
        }
        return output
    }

    private data class Interval(val from: Long, val to: Long)

    private fun intervals(rows: List<S2Evidence.JournalRow>): List<Interval> =
        rows.map { Interval(it.t, it.endsAt) }

    private fun overlaps(first: List<S2Evidence.JournalRow>, second: List<S2Evidence.JournalRow>): Boolean {
        val a = intervals(first)
        val b = intervals(second)
        return a.any { left -> b.any { right -> left.from <= right.to && right.from <= left.to } }
    }

    private fun overlapMs(windowA: LongRange, windowB: LongRange): Long =
        (min(windowA.last, windowB.last) - maxOf(windowA.first, windowB.first)).coerceAtLeast(0)

    /** Union of one commit's `ensure` answers: from the earliest start to the latest answer. */
    private fun loadWindow(answers: List<S2Evidence.Answer>): LongRange =
        answers.minOf { it.atMs - it.elapsedMs }..answers.maxOf { it.atMs }
}
