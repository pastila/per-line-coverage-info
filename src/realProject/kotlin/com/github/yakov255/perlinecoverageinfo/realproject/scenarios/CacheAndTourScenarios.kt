package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import com.github.yakov255.perlinecoverageinfo.realproject.harness.SandboxIde
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.PlanEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Cache and branch-tour scenarios: R3 (two laps over the tour) and R8 (cache growth).
 *
 * Both run a real sandbox IDE on the shared raketa worktree. R3 asserts only correctness — the
 * second lap must be served from the cache and every switch must end on the checked-out commit —
 * and records per-branch time and traffic. R8 measures the on-disk cache and checks the index
 * against the `.cov4` files.
 */
class CacheAndTourScenarios : ScenarioBase() {

    /**
     * R3: a tour of `realProject.tour` (default 8) local branches, two laps. Lap 1 downloads,
     * lap 2 must download no artifact at all; every switch ends with `bridge.commit == HEAD`.
     */
    @Test
    fun r3TourTwoLaps() {
        scenario("R3")
        val requested = System.getProperty("realProject.tour")?.toIntOrNull() ?: DEFAULT_TOUR
        val branches = tour(requested.coerceAtLeast(2))
        assumeTrue("the plan has fewer than 2 local branches: ${branches.size}", branches.size >= 2)

        switchTo(branches.first().sha)
        val ide = startIde("s1r3", waitSmart = false)
        val firstLapEnd = runLap(ide, branches, lap = 1)
        val answersAfterLap1 = ensureAnswerCount()
        assertTrue("lap 1 must download artifacts, saw $firstLapEnd", firstLapEnd.artifacts > 0)
        assertTrue("lap 1 must produce covd answers, saw $answersAfterLap1", answersAfterLap1 > 0)

        val secondLapEnd = runLap(ide, branches, lap = 2)
        val answersAfterLap2 = ensureAnswerCount()
        assertEquals("lap 2 must not download a single artifact", 0L, secondLapEnd.artifacts)
        assertTrue(
            "every switch must produce a covd answer (lap 1=$answersAfterLap1, lap 2=$answersAfterLap2)",
            answersAfterLap2 > answersAfterLap1,
        )

        val points = S1Support.heapPoints(File(cacheRoot, "perf"))
        val heapLap1 = S1Support.heapAtOrBefore(points, firstLapEnd.atMs)
        val heapLap2 = S1Support.heapAtOrBefore(points, secondLapEnd.atMs)
        val ratio = if (heapLap1 != null && heapLap1 > 0 && heapLap2 != null) heapLap2 / heapLap1 else null
        if (ratio != null && ratio > HEAP_GROWTH_LIMIT) {
            event(
                "r3-finding",
                "IDE heap grew by more than 50% between lap 1 and lap 2",
                eventData(
                    "heapAfterLap1Mb" to format(heapLap1!!),
                    "heapAfterLap2Mb" to format(heapLap2!!),
                    "ratio" to String.format(Locale.ROOT, "%.2f", ratio),
                ),
            )
            println("R3: finding — IDE heap ${format(heapLap1!!)} MB -> ${format(heapLap2!!)} MB (ratio $ratio)")
        }
        event(
            "r3-summary",
            "tour finished",
            eventData(
                "branches" to branches.size,
                "lap1Artifacts" to firstLapEnd.artifacts,
                "lap1BytesDown" to firstLapEnd.bytesDown,
                "lap2Artifacts" to secondLapEnd.artifacts,
                "lap2BytesDown" to secondLapEnd.bytesDown,
                "answersAfterLap1" to answersAfterLap1,
                "answersAfterLap2" to answersAfterLap2,
                "heapAfterLap1Mb" to heapLap1?.let(::format),
                "heapAfterLap2Mb" to heapLap2?.let(::format),
                "heapRatio" to ratio?.let { String.format(Locale.ROOT, "%.2f", it) },
            ),
        )
        println(
            "R3: lap1 artifacts=${firstLapEnd.artifacts} bytes=${firstLapEnd.bytesDown}, " +
                "lap2 artifacts=${secondLapEnd.artifacts} bytes=${secondLapEnd.bytesDown}, " +
                "answers $answersAfterLap1 -> $answersAfterLap2, heap ${heapLap1?.let(::format)} -> ${heapLap2?.let(::format)} MB",
        )
    }

    /**
     * R8: measure the cache of project 335 after a short tour (`realProject.r8.branches`, default
     * 2): directory size, `.cov4` count, consistency with `cache-index.json`, size per branch and
     * how many branches fit into the daemon's default `--max-cache-bytes` (5 GiB).
     */
    @Test
    fun r8CacheGrowth() {
        scenario("R8")
        val requested = System.getProperty("realProject.r8.branches")?.toIntOrNull() ?: DEFAULT_R8_BRANCHES
        val branches = tour(requested.coerceAtLeast(1))
        assumeTrue("the plan has no local branch", branches.isNotEmpty())

        switchTo(branches.first().sha)
        val ide = startIde("s1r8", waitSmart = false)
        for ((index, branch) in branches.withIndex()) {
            if (index > 0) switchTo(branch.sha)
            awaitLoaded(ide, branch.sha, timeoutMs = LOAD_TIMEOUT_MS)
            assertEquals("bridge commit must follow HEAD for ${branch.ref}", branch.sha, head())
        }
        pause(2_000, "let the daemon finish the cache-index write")

        val projectDir = File(cacheRoot, PROJECT_ID)
        assertTrue("cache directory is missing: $projectDir", projectDir.isDirectory)
        val indexFile = File(projectDir, "cache-index.json")
        assertTrue("cache-index.json is missing: $indexFile", indexFile.isFile)

        val entries = S1Support.cacheIndex(indexFile)
        val cov4 = projectDir.listFiles { file -> file.isFile && file.name.endsWith(".cov4") }.orEmpty()
        assertTrue("cache-index.json lists no entry", entries.isNotEmpty())
        assertTrue("the cache holds no .cov4 file", cov4.isNotEmpty())

        val missing = entries.filter { !File(projectDir, it.fileName).isFile }
        assertTrue(
            "index entries without a .cov4 file: ${missing.map { it.fileName }}",
            missing.isEmpty(),
        )
        val indexed = entries.map { it.fileName }.toSet()
        val extra = cov4.map { it.name }.filter { it !in indexed }
        if (extra.isNotEmpty()) {
            event("r8-finding", "cov4 files without an index entry (the daemon never deletes them)", eventData("files" to extra.joinToString(",")))
        }

        val sizes = entries.associate { it.fileName to File(projectDir, it.fileName).length() }
        val totalCov4 = sizes.values.sum()
        val directoryBytes = projectDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val branchSizes = branches.map { branch ->
            val entry = entries.firstOrNull { it.commit == branch.sha && it.component == RAKETA_COMPONENT }
            val file = entry?.let { File(projectDir, it.fileName) }
            assertTrue("no cache entry for the loaded commit ${branch.sha.take(8)} (${branch.ref})", file != null && file.isFile)
            Triple(branch, file!!.length(), entry)
        }
        val baselineEntry = entries.firstOrNull { it.commit == plan.baseline?.sha && it.component == RAKETA_COMPONENT }
        val averageBranchBytes = branchSizes.sumOf { it.second } / branchSizes.size
        val baselineShare = (baselineEntry?.let { File(projectDir, it.fileName).length() } ?: 0L) / branchSizes.size
        val perBranchWithShare = averageBranchBytes + baselineShare
        val capacity = CACHE_CAPACITY_BYTES

        val data = eventData(
            "projectDir" to projectDir.absolutePath,
            "indexEntries" to entries.size,
            "cov4Files" to cov4.size,
            "cov4FilesWithoutEntry" to extra.size,
            "indexEntriesWithoutFile" to missing.size,
            "totalCov4Bytes" to totalCov4,
            "directoryBytes" to directoryBytes,
            "indexFileBytes" to indexFile.length(),
            "branches" to branchSizes.size,
            "averageBranchBytes" to averageBranchBytes,
            "baselineCommit" to plan.baseline?.sha,
            "baselineBytes" to baselineEntry?.let { File(projectDir, it.fileName).length() },
            "perBranchWithBaselineShare" to perBranchWithShare,
            "maxCacheBytes" to capacity,
            "branchesFittingPrimaryOnly" to (capacity / averageBranchBytes),
            "branchesFittingWithBaselineShare" to (capacity / perBranchWithShare),
        )
        event("r8-cache", "cache measured", data)
        for ((branch, bytes, entry) in branchSizes) {
            event(
                "r8-branch",
                branch.ref,
                eventData(
                    "commit" to branch.sha,
                    "sizeClass" to branch.sizeClass,
                    "bytes" to bytes,
                    "plannedBytes" to branch.bytes,
                    "files" to entry.files,
                    "pipelineId" to entry.pipelineId,
                    "realBranch" to entry.branch,
                ),
            )
        }
        println(
            "R8: ${entries.size} index entries, ${cov4.size} .cov4 files, cov4=${totalCov4} B, dir=${directoryBytes} B; " +
                "avg branch=${averageBranchBytes} B, baseline=${baselineEntry?.let { File(projectDir, it.fileName).length() }} B; " +
                "5 GiB fits ${capacity / averageBranchBytes} branches (primary only) or ${capacity / perBranchWithShare} with the baseline share",
        )
    }

    // ---- helpers ---------------------------------------------------------------------------

    private class LapResult(val atMs: Long, val artifacts: Long, val bytesDown: Long)

    /** Runs one lap over [branches] and returns the traffic the lap caused. */
    private fun runLap(ide: SandboxIde, branches: List<PlanEntry>, lap: Int): LapResult {
        var artifacts = 0L
        var bytes = 0L
        for ((index, branch) in branches.withIndex()) {
            if (lap > 1 || index > 0) switchTo(branch.sha)
            val before = proxy.stats()
            val startedAt = System.currentTimeMillis()
            val snapshot = awaitLoaded(ide, branch.sha, timeoutMs = LOAD_TIMEOUT_MS)
            val elapsedMs = System.currentTimeMillis() - startedAt
            val after = proxy.stats()
            val artifactsDelta = (after.artifactsCompleted - before.artifactsCompleted).toLong()
            val bytesDelta = after.bytesDown - before.bytesDown
            artifacts += artifactsDelta
            bytes += bytesDelta

            assertEquals("lap $lap: bridge commit must equal HEAD for ${branch.ref}", branch.sha, snapshot.commit)
            assertEquals("lap $lap: worktree HEAD must stay at ${branch.sha.take(8)}", branch.sha, head())
            assertNull("lap $lap: lastError must stay null for ${branch.ref}: ${snapshot.lastError}", snapshot.lastError)
            if (lap > 1) {
                assertEquals("lap $lap: no artifact may be downloaded for ${branch.ref}", 0L, artifactsDelta)
            }
            event(
                "r3-branch",
                "lap $lap ${branch.ref}",
                eventData(
                    "lap" to lap,
                    "commit" to branch.sha,
                    "sizeClass" to branch.sizeClass,
                    "loadMs" to elapsedMs,
                    "artifacts" to artifactsDelta,
                    "bytesDown" to bytesDelta,
                    "files" to snapshot.files,
                    "baselineCommit" to snapshot.baselineCommit,
                ),
            )
            event(
                "switch",
                "lap $lap ${branch.ref} ${branch.sha.take(8)}",
                eventData("lap" to lap, "loadMs" to elapsedMs),
            )
            println(
                "R3 lap $lap ${branch.ref}: ${elapsedMs} ms, artifacts=$artifactsDelta, bytes=$bytesDelta, files=${snapshot.files}",
            )
        }
        val at = System.currentTimeMillis()
        val rss = S1Support.peakRss(S1Support.samples(ctx.samplesFile), "covd")
        event(
            "r3-lap",
            "lap $lap finished",
            eventData(
                "lap" to lap,
                "branches" to branches.size,
                "artifacts" to artifacts,
                "bytesDown" to bytes,
                "peakCovdRssMbSeen" to rss?.let(::format),
            ),
        )
        return LapResult(at, artifacts, bytes)
    }

    private fun ensureAnswerCount(): Int =
        S1Support.answers(File(cacheRoot, "covd-responses")).count { it.kind == "ensure" }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.0f", value)

    private companion object {
        const val LOAD_TIMEOUT_MS = 1_200_000L
        const val DEFAULT_TOUR = 8
        const val DEFAULT_R8_BRANCHES = 2
        const val HEAP_GROWTH_LIMIT = 1.5
        const val PROJECT_ID = "335"
        const val RAKETA_COMPONENT = "raketa"
        const val CACHE_CAPACITY_BYTES = 5L * 1024L * 1024L * 1024L
    }
}
