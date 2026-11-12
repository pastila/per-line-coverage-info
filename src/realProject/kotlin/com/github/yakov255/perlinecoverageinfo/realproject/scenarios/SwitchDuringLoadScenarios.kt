package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import com.github.yakov255.perlinecoverageinfo.realproject.harness.BridgeSnapshot
import com.github.yakov255.perlinecoverageinfo.realproject.harness.ProcSampler
import com.github.yakov255.perlinecoverageinfo.realproject.harness.SandboxIde
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.PlanEntry
import com.github.yakov255.perlinecoverageinfo.realproject.proxy.ProxyEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R5/R6: a branch switch in the middle of the coverage work the previous HEAD started.
 *
 * R5 switches while the shards of branch A are still coming through the throttled proxy. The
 * expectation is that the late answer for A does not damage anything and that the plugin reloads
 * for the new HEAD B (`CoverageLoadService.pendingReload`): final commit B, fresh, no error.
 * R5b does A -> B -> C during the download of A.
 *
 * R6 freezes the daemon with SIGSTOP right after the last shard of A left the proxy, switches
 * the worktree to B while covd is still parsing/merging/writing A, and resumes it. The old answer
 * must never overwrite the newer one.
 *
 * The proxy never sees a credential (token `dummy`), the worktree is the dedicated
 * `/tmp/raketa-load`, and every branch comes from `plan.json`. Wasted work is reported in bytes
 * because covd does not cancel an `ensure` when the client is gone (`context.WithoutCancel`).
 */
class SwitchDuringLoadScenarios : ScenarioBase() {

    // ------------------------------------------------------------------ R5

    @Test
    fun r5SwitchDuringDownload() {
        scenario("R5", "r5-switch-during-download")
        ensureWorktreeAtBaseline()
        val branches = tourBranches(2, "R5")
        val a = branches.firstOrNull { it.sizeClass == "large" } ?: branches[0]
        val b = branches.first { it.sha != a.sha }

        // Pace the artifact bodies so the download lasts long enough to switch inside it.
        proxy.throttle(THROTTLE_BYTES_PER_SEC, ARTIFACT_PATH)
        watchArtifacts()

        switchTo(a.sha)
        event("plan", "R5: A=${a.ref} (${a.sizeClass}, ${a.bytes} B, ${a.shards} shards), B=${b.ref}",
            eventData("a" to a.sha, "b" to b.sha, "throttleBytesPerSec" to THROTTLE_BYTES_PER_SEC))

        val tRun = now()
        val ide = startIde("ide-r5", waitSmart = false)
        val downloadWindow = awaitDownloadStarted(ide, 2, Duration.ofSeconds(START_TIMEOUT_S))
        event("download-started", "artifact downloads are running on ${a.sha.take(8)}",
            eventData("started" to downloadWindow.started, "secondStartedEarly" to downloadWindow.secondStarted))

        val beforeSwitch = ide.waitFor(SWITCH_WINDOW_TIMEOUT_MS, "load of A is not in progress before the switch") {
            it != null && it.loading == true
        }!!
        event("pre-switch", "bridge before the switch",
            eventData(
                "state" to beforeSwitch.state, "commit" to beforeSwitch.commit,
                "loading" to beforeSwitch.loading, "stale" to beforeSwitch.stale,
                "atMs" to now(),
            ))

        val tSwitch = now()
        switchTo(b.sha)
        val tSwitchDone = now()

        // A's answer lands first; the plugin must then reload for the new HEAD.
        val aApplied = waitForCommit(ide, a.sha, ANSWER_TIMEOUT_MS, "A (${a.sha.take(8)}) was never applied")
        val tAApplied = now()
        event("old-commit-applied", "A applied before the switch-back reload",
            eventData("commit" to a.sha, "atMs" to tAApplied, "sinceSwitchMs" to (tAApplied - tSwitch)))

        val loadedB = awaitLoaded(ide, b.sha, LOAD_TIMEOUT_MS)
        val tLoadedB = now()
        event("loaded", "B is loaded and fresh",
            eventData("commit" to b.sha, "atMs" to tLoadedB, "sinceSwitchMs" to (tLoadedB - tSwitch), "state" to loadedB.state))

        val answers = awaitEnsureCount(2, ANSWER_TIMEOUT_MS)
        val ensureA = answers.firstOrNull { it.commit == a.sha }
        val ensureB = answers.firstOrNull { it.commit == b.sha }
        val tAAnswer = ensureA?.atMs ?: tAApplied

        // ---- facts -------------------------------------------------------------------------
        val artifactBytes = artifactWindows.sumOf { it.bytes }
        val aWindows = artifactWindows.filter { it.startedMs < tAAnswer }
        val bWindows = artifactWindows.filter { it.startedMs >= tAAnswer }
        val wastedBytes = aWindows.sumOf { it.bytes }
        val wastedAfterSwitch = aWindows.sumOf { bytesAfter(it, tSwitch) }
        val overlapPairs = aWindows.sumOf { aWindow -> bWindows.count { bWindow -> aWindow.overlaps(bWindow) } }
        val covdPeak = peakRssMb("covd", tRun, tLoadedB)
        val idePeak = peakRssMb("ide-r5", tRun, tLoadedB)

        // ---- assertions --------------------------------------------------------------------
        assertTrue("no artifact window was recorded: the scenario downloaded nothing", artifactWindows.isNotEmpty())
        assertTrue("A's ensure answer is missing from covd-responses", ensureA != null)
        assertTrue("B's ensure answer is missing from covd-responses", ensureB != null)
        assertTrue("A was never observed as the loaded commit before B", aApplied.commit == a.sha)
        assertEquals("the final coverage commit is not B", b.sha, loadedB.commit)
        assertEquals("the final commit must be fresh (not stale)", false, loadedB.stale)
        assertNull("lastError must be null after the switch-back load, was '${loadedB.lastError}'", loadedB.lastError)
        assertTrue("B's coverage loaded no files", (loadedB.files ?: 0) > 0)
        assertEquals("the worktree must end on B", b.sha, head())
        assertNoRegressionAfter(b.sha, tLoadedB, forbidden = a.sha)

        event("summary", "R5 result",
            eventData(
                "result" to "pass",
                "switchAtMs" to tSwitch,
                "switchCheckoutMs" to (tSwitchDone - tSwitch),
                "switchToLoadedBMs" to (tLoadedB - tSwitch),
                "switchToOldAppliedMs" to (tAApplied - tSwitch),
                "ensureAnswers" to answers.size,
                "ensureAms" to ensureA?.elapsedMs,
                "ensureBms" to ensureB?.elapsedMs,
                "ensureAcached" to ensureA?.cached,
                "ensureBcached" to ensureB?.cached,
                "artifactWindows" to artifactWindows.size,
                "artifactBytes" to artifactBytes,
                "artifactBytesA" to wastedBytes,
                "artifactBytesAfterSwitch" to wastedAfterSwitch,
                "artifactWindowsOverlapAB" to overlapPairs,
                "proxyBytesDown" to proxy.stats().bytesDown,
                "proxyRequests" to proxy.stats().requests,
                "covdPeakRssMb" to covdPeak,
                "idePeakRssMb" to idePeak,
            ))

        println("R5: switch->LOADED(B) ${tLoadedB - tSwitch} ms, ensures=${answers.size}, " +
            "wasted A bytes≈$wastedBytes ($wastedAfterSwitch after the switch), covd peak≈$covdPeak MB")
        finish()
    }

    // ------------------------------------------------------------------ R5b

    @Test
    fun r5bDoubleSwitchDuringDownload() {
        scenario("R5B", "r5b-double-switch-during-download")
        ensureWorktreeAtBaseline()
        val branches = tourBranches(3, "R5B")
        val a = branches[0]
        val b = branches[1]
        val c = branches[2]

        proxy.throttle(THROTTLE_BYTES_PER_SEC, ARTIFACT_PATH)
        watchArtifacts()

        switchTo(a.sha)
        event("plan", "R5b: A=${a.ref}, B=${b.ref}, C=${c.ref}",
            eventData("a" to a.sha, "b" to b.sha, "c" to c.sha, "throttleBytesPerSec" to THROTTLE_BYTES_PER_SEC))

        val tRun = now()
        val ide = startIde("ide-r5b", waitSmart = false)
        val downloadWindow = awaitDownloadStarted(ide, 2, Duration.ofSeconds(START_TIMEOUT_S))
        event("download-started", "artifact downloads are running on ${a.sha.take(8)}",
            eventData("started" to downloadWindow.started, "secondStartedEarly" to downloadWindow.secondStarted))

        ide.waitFor(SWITCH_WINDOW_TIMEOUT_MS, "load of A is not in progress before the switch") { it != null && it.loading == true }

        val tSwitchB = now()
        switchTo(b.sha)
        val tSwitchBDone = now()
        pause(500, "stay on B just long enough for a second HEAD event")
        val tSwitchC = now()
        switchTo(c.sha)
        val tSwitchCDone = now()

        val aApplied = waitForCommit(ide, a.sha, ANSWER_TIMEOUT_MS, "A (${a.sha.take(8)}) was never applied")
        val tAApplied = now()
        event("old-commit-applied", "A applied after both switches",
            eventData("commit" to a.sha, "atMs" to tAApplied, "sinceSwitchCMs" to (tAApplied - tSwitchC)))

        val loadedC = awaitLoaded(ide, c.sha, LOAD_TIMEOUT_MS)
        val tLoadedC = now()
        event("loaded", "C is loaded and fresh",
            eventData("commit" to c.sha, "atMs" to tLoadedC, "sinceSwitchCMs" to (tLoadedC - tSwitchC), "state" to loadedC.state))

        val answers = awaitEnsureCount(2, ANSWER_TIMEOUT_MS)
        val ensureA = answers.firstOrNull { it.commit == a.sha }
        val ensureB = answers.firstOrNull { it.commit == b.sha }
        val ensureC = answers.firstOrNull { it.commit == c.sha }

        val covdPeak = peakRssMb("covd", tRun, tLoadedC)
        val idePeak = peakRssMb("ide-r5b", tRun, tLoadedC)

        assertTrue("A's ensure answer is missing", ensureA != null)
        assertTrue("C's ensure answer is missing", ensureC != null)
        assertTrue("the final commit is not C", loadedC.commit == c.sha)
        assertEquals("the final commit must be fresh (not stale)", false, loadedC.stale)
        assertNull("lastError must be null, was '${loadedC.lastError}'", loadedC.lastError)
        assertTrue("C's coverage loaded no files", (loadedC.files ?: 0) > 0)
        assertEquals("the worktree must end on C", c.sha, head())
        assertNoRegressionAfter(c.sha, tLoadedC, forbidden = a.sha, alsoForbidden = b.sha)

        event("summary", "R5b result",
            eventData(
                "result" to "pass",
                "switchBAtMs" to tSwitchB,
                "switchCAtMs" to tSwitchC,
                "switchBCheckoutMs" to (tSwitchBDone - tSwitchB),
                "switchCCheckoutMs" to (tSwitchCDone - tSwitchC),
                "switchCToLoadedCMs" to (tLoadedC - tSwitchC),
                "switchCToOldAppliedMs" to (tAApplied - tSwitchC),
                "ensureAnswers" to answers.size,
                "ensureAms" to ensureA?.elapsedMs,
                "ensureBms" to ensureB?.elapsedMs,
                "ensureCms" to ensureC?.elapsedMs,
                "ensureBPresent" to (ensureB != null),
                "artifactBytes" to artifactWindows.sumOf { it.bytes },
                "proxyBytesDown" to proxy.stats().bytesDown,
                "covdPeakRssMb" to covdPeak,
                "idePeakRssMb" to idePeak,
            ))

        println("R5b: A->B->C, final ${c.sha.take(8)}, ensures=${answers.size} " +
            "(A=${ensureA?.state}, B=${ensureB?.state ?: "absent"}, C=${ensureC?.state}), " +
            "switchC->LOADED(C) ${tLoadedC - tSwitchC} ms")
        finish()
    }

    // ------------------------------------------------------------------ R6

    @Test
    fun r6SwitchDuringParse() {
        scenario("R6", "r6-switch-during-parse")
        ensureWorktreeAtBaseline()
        val branches = tourBranches(6, "R6")
        watchArtifacts()

        var ide: SandboxIde? = null
        var caught: Attempt? = null
        val attempts = mutableListOf<String>()
        val tRun = now()

        for (index in 0 until MAX_ATTEMPTS) {
            val primary = branches[index * 2]
            val target = branches[index * 2 + 1]
            val attemptNo = index + 1
            val artifactsBefore = proxy.stats().artifactsCompleted
            val answersBefore = ensureFileCount()

            event("attempt", "R6 attempt $attemptNo: ${primary.ref} -> ${target.ref}",
                eventData(
                    "attempt" to attemptNo, "primary" to primary.sha, "target" to target.sha,
                    "shards" to primary.shards, "artifactsBefore" to artifactsBefore,
                    "answersBefore" to answersBefore,
                ))

            if (ide == null) {
                switchTo(primary.sha)
                ide = startIde("ide-r6", waitSmart = false)
            } else {
                switchTo(primary.sha)
            }
            val runningIde = ide

            val wanted = artifactsBefore + primary.shards
            val downloaded = proxy.awaitArtifactCompleted(wanted, Duration.ofSeconds(ARTIFACT_TIMEOUT_S))
            if (!downloaded) {
                attempts += "attempt $attemptNo: only ${proxy.stats().artifactsCompleted - artifactsBefore} of " +
                    "${primary.shards} shards arrived"
                event("attempt-missed", attempts.last())
                settleLoad(runningIde)
                continue
            }
            val tAllShards = now()

            val freeze = CovdFreeze(sampler)
            var attemptCaught = false
            var resumedAt = 0L
            var attemptData: Attempt? = null
            try {
                val pid = freeze.stop()
                val stoppedAt = freeze.stoppedAtMs ?: now()
                event("covd-sigstop", "SIGSTOP covd right after the last shard of ${primary.sha.take(8)}",
                    eventData(
                        "pid" to pid, "atMs" to stoppedAt, "processState" to pid?.let { procState(it) },
                        "sinceAllShardsMs" to (stoppedAt - tAllShards),
                        "shards" to primary.shards,
                    ))
                if (pid == null) {
                    attempts += "attempt $attemptNo: no covd process to freeze"
                    event("attempt-missed", attempts.last())
                    continue
                }

                val frozenSnapshot = runningIde.readBridge()
                val answersAtFreeze = ensureFileCount()
                // A cached poll answer for another commit may appear at any time; only the answer
                // for THIS commit tells whether covd already finished the parse.
                val alreadyAnswered = savedAnswers().any { it.commit == primary.sha }
                val windowCaught = !alreadyAnswered && frozenSnapshot?.loading == true
                event("window", "phase of covd when it was stopped",
                    eventData(
                        "windowCaught" to windowCaught,
                        "answersAtFreeze" to answersAtFreeze,
                        "alreadyAnsweredForPrimary" to alreadyAnswered,
                        "loading" to frozenSnapshot?.loading,
                        "state" to frozenSnapshot?.state,
                        "commit" to frozenSnapshot?.commit,
                        "processState" to procState(pid),
                    ))

                val tSwitch = now()
                switchTo(target.sha)
                val tSwitchDone = now()

                // The HEAD tracker debounces for 2 s; give it 3 s more. covd is still frozen, so
                // the IDE must stay responsive (bridge tick) while the request waits.
                pause(DEBOUNCE_MS + FREEZE_MARGIN_MS, "HEAD debounce and margin while covd is frozen")
                val during = runningIde.readBridge()
                val updatedA = during?.updatedAt
                pause(1_500, "second bridge read to prove the IDE is alive")
                val updatedB = runningIde.readBridge()?.updatedAt
                val bridgeAlive = updatedA != null && updatedB != null && updatedB > updatedA
                val stillFrozen = procState(pid) == "T"
                event("frozen-window", "IDE state while covd is frozen",
                    eventData(
                        "loading" to during?.loading, "state" to during?.state,
                        "commit" to during?.commit, "updatedAtFirst" to updatedA,
                        "updatedAtSecond" to updatedB, "bridgeAlive" to bridgeAlive,
                        "covdProcessState" to procState(pid), "stillFrozen" to stillFrozen,
                        "answersDuringFreeze" to ensureFileCount(),
                    ))

                if (windowCaught && during?.loading == true && bridgeAlive) {
                    attemptCaught = true
                    attemptData = Attempt(
                        primary = primary,
                        target = target,
                        tFreeze = stoppedAt,
                        tSwitch = tSwitch,
                        tSwitchDone = tSwitchDone,
                        answersBefore = answersBefore,
                        tAllShards = tAllShards,
                    )
                } else {
                    attempts += "attempt $attemptNo: parse window missed " +
                        "(windowCaught=$windowCaught, loading=${during?.loading}, bridgeAlive=$bridgeAlive)"
                }
            } finally {
                // Never leave covd stopped: the test itself would hang on the 11-minute HTTP read.
                freeze.resume()
                resumedAt = freeze.resumedAtMs ?: now()
                event("covd-sigcont", "SIGCONT covd",
                    eventData(
                        "pid" to freeze.pid, "atMs" to freeze.resumedAtMs,
                        "frozenMs" to freeze.frozenMs, "processState" to freeze.pid?.let { procState(it) },
                    ))
            }
            if (attemptCaught) {
                caught = attemptData?.copy(tResume = resumedAt)
            }

            if (attemptCaught) break
            settleLoad(runningIde)
        }

        val attempt = caught ?: throw AssertionError(
            "R6 could not freeze covd during the parse of the old commit: ${attempts.joinToString(" | ")}",
        )
        val runningIde = ide!!

        // The old answer must arrive first, then the plugin loads the new HEAD.
        val oldAnswer = awaitAnswer(attempt.primary.sha, ANSWER_TIMEOUT_MS)
        val loadedTarget = awaitLoaded(runningIde, attempt.target.sha, LOAD_TIMEOUT_MS)
        val tLoadedTarget = now()
        val newAnswer = awaitAnswer(attempt.target.sha, ANSWER_TIMEOUT_MS)

        // The journal copy lags the live bridge by up to one poll interval: wait for the row that
        // proves the new commit was loaded before reading the order of the two applies.
        val journalSawTarget = awaitFreshRow(attempt.target.sha)
        val afterCont = bridgeRows().filter { it.tMs >= attempt.tSwitch }
        val sawOldCommit = afterCont.any { it.commit == attempt.primary.sha }
        val oldApplyIndex = afterCont.indexOfFirst { it.commit == attempt.primary.sha }
        val firstNewIndex = afterCont.indexOfFirst { it.commit == attempt.target.sha }
        val orderOk = oldApplyIndex >= 0 && firstNewIndex > oldApplyIndex

        val covdPeak = peakRssMb("covd", tRun, tLoadedTarget)
        val idePeak = peakRssMb("ide-r6", tRun, tLoadedTarget)
        val frozenMs = attempt.tResume - attempt.tFreeze

        // ---- assertions --------------------------------------------------------------------
        assertEquals("the final coverage commit is not the target", attempt.target.sha, loadedTarget.commit)
        assertEquals("the final commit must be fresh (not stale)", false, loadedTarget.stale)
        assertNull("lastError must be null, was '${loadedTarget.lastError}'", loadedTarget.lastError)
        assertTrue("the target's coverage loaded no files", (loadedTarget.files ?: 0) > 0)
        assertEquals("the worktree must end on the target commit", attempt.target.sha, head())
        assertEquals("the old answer arrived after the new one", true, oldAnswer.atMs <= newAnswer.atMs)
        assertTrue("the journal never recorded a fresh load of the target", journalSawTarget)
        assertTrue("the old commit is never observed as loaded after the switch", sawOldCommit)
        assertTrue("the old commit must be applied before the new one, snapshots: " +
            afterCont.joinToString(",") { "${it.commit?.take(8)}" }, orderOk)
        assertNoRegressionAfter(attempt.target.sha, tLoadedTarget, forbidden = attempt.primary.sha)

        event("summary", "R6 result",
            eventData(
                "result" to "pass",
                "primary" to attempt.primary.sha,
                "target" to attempt.target.sha,
                "freezeAtMs" to attempt.tFreeze,
                "switchAtMs" to attempt.tSwitch,
                "resumeAtMs" to attempt.tResume,
                "frozenMs" to frozenMs,
                "oldAnswerAtMs" to oldAnswer.atMs,
                "oldAnswerAfterContMs" to (oldAnswer.atMs - attempt.tResume),
                "oldAnswerDownloadMs" to oldAnswer.downloadMs,
                "oldAnswerParseMs" to oldAnswer.parseMs,
                "oldAnswerMergeMs" to oldAnswer.mergeMs,
                "oldAnswerWriteMs" to oldAnswer.writeMs,
                "oldAnswerTotalMs" to oldAnswer.totalMs,
                "newAnswerAtMs" to newAnswer.atMs,
                "targetLoadedAtMs" to tLoadedTarget,
                "switchToLoadedTargetMs" to (tLoadedTarget - attempt.tSwitch),
                "contToLoadedTargetMs" to (tLoadedTarget - attempt.tResume),
                "sawOldCommitAfterSwitch" to sawOldCommit,
                "oldAppliedBeforeNew" to orderOk,
                "artifactBytes" to artifactWindows.sumOf { it.bytes },
                "proxyBytesDown" to proxy.stats().bytesDown,
                "covdPeakRssMb" to covdPeak,
                "idePeakRssMb" to idePeak,
                "ensureAnswers" to savedAnswers().count { it.kind == "ensure" },
            ))

        println("R6: caught on attempt ${attempts.size + 1}, frozen ${frozenMs} ms, " +
            "old answer ${oldAnswer.atMs - attempt.tResume} ms after SIGCONT, " +
            "switch->LOADED(target) ${tLoadedTarget - attempt.tSwitch} ms, " +
            "covd peak≈$covdPeak MB")
        finish()
    }

    // ------------------------------------------------------------------ R6b

    @Test
    fun r6bSwitchDuringFrozenDownload() {
        scenario("R6B", "r6b-switch-during-frozen-download")
        ensureWorktreeAtBaseline()
        val branches = tourBranches(2, "R6B")
        val a = branches[0]
        val b = branches[1]

        proxy.throttle(THROTTLE_BYTES_PER_SEC, ARTIFACT_PATH)
        watchArtifacts()

        switchTo(a.sha)
        event("plan", "R6b: A=${a.ref}, B=${b.ref} (freeze during the download)",
            eventData("a" to a.sha, "b" to b.sha, "throttleBytesPerSec" to THROTTLE_BYTES_PER_SEC))

        val tRun = now()
        val ide = startIde("ide-r6b", waitSmart = false)
        val downloadWindow = awaitDownloadStarted(ide, 3, Duration.ofSeconds(START_TIMEOUT_S))
        event("download-started", "three artifact downloads started on ${a.sha.take(8)}",
            eventData("started" to downloadWindow.started))

        ide.waitFor(SWITCH_WINDOW_TIMEOUT_MS, "load of A is not in progress before the freeze") { it != null && it.loading == true }

        val freeze = CovdFreeze(sampler)
        var tSwitch = 0L
        var inFlightAtFreeze = 0
        try {
            val found = freeze.stop()
            val stoppedAt = freeze.stoppedAtMs ?: now()
            // Artifact requests that started but have no finished window yet are exactly the ones
            // still open at the freeze: the proxy journals them only when they end.
            inFlightAtFreeze = artifactStarts.count { (_, startedMs) -> startedMs < stoppedAt }
            event("covd-sigstop", "SIGSTOP covd during the download of ${a.sha.take(8)}",
                eventData(
                    "pid" to found, "atMs" to stoppedAt, "processState" to found?.let { procState(it) },
                    "inFlightArtifacts" to inFlightAtFreeze,
                    "finishedWindows" to artifactWindows.size,
                ))
            val pid = found ?: throw AssertionError("R6b: no covd process to freeze")

            tSwitch = now()
            switchTo(b.sha)
            val tSwitchDone = now()

            pause(DEBOUNCE_MS + FREEZE_MARGIN_MS, "HEAD debounce and margin while covd is frozen")
            val during = ide.readBridge()
            val updatedA = during?.updatedAt
            pause(1_500, "second bridge read to prove the IDE is alive")
            val updatedB = ide.readBridge()?.updatedAt
            val bridgeAlive = updatedA != null && updatedB != null && updatedB > updatedA
            event("frozen-window", "IDE state while covd is frozen mid-download",
                eventData(
                    "loading" to during?.loading, "state" to during?.state, "commit" to during?.commit,
                    "bridgeAlive" to bridgeAlive, "covdProcessState" to procState(pid),
                    "frozenMs" to (now() - stoppedAt), "switchMs" to (tSwitchDone - tSwitch),
                ))
            assertTrue("the IDE bridge stopped updating while covd was frozen", bridgeAlive)
            assertTrue("the load must still be running while covd is frozen", during?.loading == true)
        } finally {
            freeze.resume()
            event("covd-sigcont", "SIGCONT covd",
                eventData(
                    "pid" to freeze.pid, "atMs" to freeze.resumedAtMs,
                    "frozenMs" to freeze.frozenMs, "processState" to freeze.pid?.let { procState(it) },
                ))
        }

        val loadedB = awaitLoaded(ide, b.sha, LOAD_TIMEOUT_MS)
        val tLoadedB = now()
        event("loaded", "B is loaded and fresh after the frozen download",
            eventData("commit" to b.sha, "atMs" to tLoadedB, "sinceSwitchMs" to (tLoadedB - tSwitch)))

        val answers = awaitEnsureCount(2, ANSWER_TIMEOUT_MS)
        val ensureA = answers.firstOrNull { it.commit == a.sha }
        val ensureB = answers.firstOrNull { it.commit == b.sha }
        val covdPeak = peakRssMb("covd", tRun, tLoadedB)
        val idePeak = peakRssMb("ide-r6b", tRun, tLoadedB)

        assertTrue("A's ensure answer is missing", ensureA != null)
        assertTrue("B's ensure answer is missing", ensureB != null)
        assertEquals("the final coverage commit is not B", b.sha, loadedB.commit)
        assertEquals("the final commit must be fresh (not stale)", false, loadedB.stale)
        assertNull("lastError must be null, was '${loadedB.lastError}'", loadedB.lastError)
        assertTrue("B's coverage loaded no files", (loadedB.files ?: 0) > 0)
        assertEquals("the worktree must end on B", b.sha, head())
        assertNoRegressionAfter(b.sha, tLoadedB, forbidden = a.sha)

        event("summary", "R6b result",
            eventData(
                "result" to "pass",
                "artifactRequestsInFlightAtFreeze" to inFlightAtFreeze,
                "switchAtMs" to tSwitch,
                "switchToLoadedBMs" to (tLoadedB - tSwitch),
                "ensureAnswers" to answers.size,
                "ensureAms" to ensureA?.elapsedMs,
                "ensureBms" to ensureB?.elapsedMs,
                "artifactBytes" to artifactWindows.sumOf { it.bytes },
                "proxyBytesDown" to proxy.stats().bytesDown,
                "covdPeakRssMb" to covdPeak,
                "idePeakRssMb" to idePeak,
            ))

        println("R6b: frozen mid-download, switch->LOADED(B) ${tLoadedB - tSwitch} ms, ensures=${answers.size}, " +
            "covd peak≈$covdPeak MB")
        finish()
    }

    // ------------------------------------------------------------------ helpers: plan and git

    private fun ensureWorktreeAtBaseline() {
        val baseline = plan.baseline ?: return
        val current = head()
        if (current != baseline.sha) {
            event("baseline-missing", "worktree was at ${current.take(8)}, switching back to the baseline")
            switchTo(baseline.sha)
        }
        assertEquals("the worktree must start on the baseline commit", baseline.sha, head())
        event("baseline", "worktree at ${baseline.sha.take(8)}")
    }

    private fun tourBranches(count: Int, what: String): List<PlanEntry> {
        val branches = tour(tourCount())
        assumeTrue("$what needs at least $count tour branches (realProject.tour), have ${branches.size}", branches.size >= count)
        return branches
    }

    /** `-DrealProject.tour` / `REAL_PROJECT_TOUR`, default 8: the plan is read from plan.json. */
    private fun tourCount(): Int {
        val raw = System.getProperty("realProject.tour") ?: System.getenv("REAL_PROJECT_TOUR")
        return raw?.toIntOrNull()?.coerceIn(2, 24) ?: 8
    }

    // ------------------------------------------------------------------ helpers: waiting

    private data class DownloadWindow(val started: Boolean, val secondStarted: Boolean)

    /** Waits for [count] artifact downloads; returns which waits succeeded instead of failing. */
    private fun awaitDownloadStarted(ide: SandboxIde, count: Int, timeout: Duration): DownloadWindow {
        val started = proxy.awaitArtifactStarted(1, timeout)
        val second = if (started && count > 1) {
            proxy.awaitArtifactStarted(count, Duration.ofSeconds(SECOND_START_TIMEOUT_S))
        } else {
            false
        }
        if (!started) fail("no artifact download started within ${timeout.seconds} s")
        event("artifact-start-check", "artifact downloads: >=1=$started, >=$count=$second")
        return DownloadWindow(started, second)
    }

    private fun waitForCommit(ide: SandboxIde, sha: String, timeoutMs: Long, message: String): BridgeSnapshot =
        ide.waitFor(timeoutMs, message) { it != null && it.commit == sha }!!

    private fun awaitEnsureCount(count: Int, timeoutMs: Long): List<SavedAnswer> {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            val answers = savedAnswers()
            if (answers.count { it.kind == "ensure" } >= count) return answers
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("expected $count ensure answers in covd-responses, see ${File(cacheRoot, "covd-responses")}")
    }

    private fun awaitAnswer(commit: String, timeoutMs: Long): SavedAnswer {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            savedAnswers().firstOrNull { it.kind == "ensure" && it.commit == commit }?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("no ensure answer for ${commit.take(8)} in ${File(cacheRoot, "covd-responses")}")
    }

    /** Waits (best effort) until no load runs, so a retry starts from a quiet IDE. */
    private fun settleLoad(ide: SandboxIde) {
        runCatching { ide.waitFor(300_000, "the load did not settle before the next attempt") { it != null && it.loading != true } }
        runCatching { Thread.sleep(1_000) }
    }

    // ------------------------------------------------------------------ helpers: observation

    private data class ArtifactWindow(
        val seq: Int,
        val jobId: Int,
        val startedMs: Long,
        val endedMs: Long,
        val status: Int?,
        val bytes: Long,
        val fault: String?,
    ) {
        fun overlaps(other: ArtifactWindow): Boolean = startedMs < other.endedMs && other.startedMs < endedMs

        fun bytesAfter(ms: Long): Long = when {
            endedMs <= ms -> 0L
            startedMs >= ms -> bytes
            else -> {
                val span = (endedMs - startedMs).coerceAtLeast(1L)
                (bytes * ((endedMs - ms).toDouble() / span)).toLong()
            }
        }
    }

    private val artifactStarts = ConcurrentHashMap<Int, Long>()
    private val artifactWindows = CopyOnWriteArrayList<ArtifactWindow>()

    /**
     * Journals artifact requests in flight. The proxy's own `proxy.jsonl` only has finished rows;
     * this listener is what lets the scenario say when a download was still open during a freeze.
     */
    private fun watchArtifacts() {
        proxy.onEvent { event ->
            val jobId = event.artifactJobId
            if (jobId == null) return@onEvent
            when (event.kind) {
                ProxyEvent.Kind.REQUEST -> artifactStarts[event.seq] = now()
                ProxyEvent.Kind.RESPONSE, ProxyEvent.Kind.DROP -> {
                    val started = artifactStarts.remove(event.seq) ?: return@onEvent
                    artifactWindows += ArtifactWindow(
                        seq = event.seq,
                        jobId = jobId,
                        startedMs = started,
                        endedMs = now(),
                        status = event.status,
                        bytes = event.bytesDown,
                        fault = event.fault,
                    )
                }
            }
        }
    }

    /** Bytes of one window that were transferred after [ms] (linear estimate inside a window). */
    private fun bytesAfter(window: ArtifactWindow, ms: Long): Long = window.bytesAfter(ms)

    private fun peakRssMb(proc: String, fromMs: Long, toMs: Long): Double? = procSamples()
        .filter { it.proc == proc && it.tMs in fromMs..toMs }
        .maxOfOrNull { it.rssMb }

    private data class ProcSample(val tMs: Long, val proc: String, val rssMb: Double)

    private fun procSamples(): List<ProcSample> {
        val file = ctx.samplesFile
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val fields = line.split(',')
                    if (fields.size < 4) return@mapNotNull null
                    val t = fields[0].toLongOrNull() ?: return@mapNotNull null
                    val rss = fields[3].toDoubleOrNull() ?: return@mapNotNull null
                    ProcSample(t, fields[1], rss)
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ helpers: saved answers

    private data class SavedAnswer(
        val file: String,
        val seq: Long,
        val atMs: Long,
        val kind: String,
        val state: String,
        val commit: String?,
        val baselineCommit: String?,
        val cached: Boolean,
        val stale: Boolean,
        val elapsedMs: Long,
        val totalMs: Long?,
        val downloadMs: Long?,
        val parseMs: Long?,
        val mergeMs: Long?,
        val writeMs: Long?,
        val baselineMs: Long?,
        val joinedFlight: Boolean,
        val head: String?,
    )

    private fun ensureFileCount(): Int =
        File(cacheRoot, "covd-responses").listFiles { f -> f.isFile && f.name.contains("-ensure-") }.orEmpty().size

    private fun savedAnswers(): List<SavedAnswer> {
        val dir = File(cacheRoot, "covd-responses")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { parseAnswer(it) }
            .sortedBy { it.atMs }
    }

    private fun parseAnswer(file: File): SavedAnswer? = runCatching {
        val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return@runCatching null
        val response = root.obj("response") ?: JsonObject(emptyMap())
        val trace = response.obj("trace")
        val timing = trace?.obj("timing")
        val request = root.obj("request")
        SavedAnswer(
            file = file.name,
            seq = root.lng("seq") ?: 0L,
            atMs = root.lng("atMs") ?: file.lastModified(),
            kind = root.str("kind") ?: "ensure",
            state = response.str("state").orEmpty(),
            commit = response.str("commit"),
            baselineCommit = response.str("baselineCommit"),
            cached = response.bul("cached") ?: false,
            stale = response.bul("stale") ?: false,
            elapsedMs = root.lng("elapsedMs") ?: 0L,
            totalMs = timing?.lng("totalMs"),
            downloadMs = timing?.lng("downloadMs"),
            parseMs = timing?.lng("parseMs"),
            mergeMs = timing?.lng("mergeMs"),
            writeMs = timing?.lng("writeMs"),
            baselineMs = timing?.lng("baselineMs"),
            joinedFlight = trace?.bul("joinedFlight") ?: false,
            head = request?.str("head"),
        )
    }.getOrNull()

    // ------------------------------------------------------------------ helpers: bridge history

    private data class BridgeRow(
        val tMs: Long,
        val commit: String?,
        val stale: Boolean?,
        val loading: Boolean?,
        val state: String?,
        val updatedAt: Long?,
        val lastError: String?,
    )

    private fun bridgeRows(): List<BridgeRow> {
        val file = ctx.snapshotsFile
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    val root = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
                    val t = root.lng("tMs") ?: return@mapNotNull null
                    BridgeRow(
                        tMs = t,
                        commit = root.str("commit"),
                        stale = root.bul("stale"),
                        loading = root.bul("loading"),
                        state = root.str("state"),
                        updatedAt = root.lng("updatedAt"),
                        lastError = root.str("lastError"),
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Waits until the journal copy (polled every 500 ms) contains a row of [sha] loaded fresh.
     * The live bridge file can be ahead of `snapshots.jsonl` by one poll interval.
     */
    private fun awaitFreshRow(sha: String, timeoutMs: Long = 15_000): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            if (bridgeRows().any { it.commit == sha && it.loading != true && it.stale != true }) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /**
     * The new commit must stay loaded: after the bridge first showed [sha] fresh, no snapshot may
     * carry another commit again (a late answer of the abandoned HEAD would).
     */
    private fun assertNoRegressionAfter(sha: String, sinceMs: Long, forbidden: String, alsoForbidden: String? = null) {
        // The journal lags the live bridge; wait for the row, then give a late answer a chance.
        awaitFreshRow(sha)
        runCatching { Thread.sleep(REGRESSION_WAIT_MS) }
        // The row that first showed the fresh commit may predate `sinceMs` by one poll interval.
        val rows = bridgeRows().filter { it.tMs >= sinceMs - 60_000L }
        val firstFresh = rows.indexOfFirst { it.commit == sha && it.loading != true && it.stale != true }
        assertTrue("the bridge never recorded a fresh load of ${sha.take(8)}", firstFresh >= 0)
        val after = rows.drop(firstFresh)
        assertTrue(
            "commit ${forbidden.take(8)} was applied after ${sha.take(8)} (snapshots: " +
                after.joinToString(",") { "${it.commit?.take(8)}" } + ")",
            after.none { it.commit == forbidden },
        )
        if (alsoForbidden != null) {
            assertTrue(
                "commit ${alsoForbidden.take(8)} was applied after ${sha.take(8)}",
                after.none { it.commit == alsoForbidden },
            )
        }
        assertEquals("the last loaded commit is not ${sha.take(8)}", sha, rows.lastOrNull { it.commit != null }?.commit)
    }

    // ------------------------------------------------------------------ helpers: covd freeze

    /**
     * SIGSTOP/SIGCONT of the scenario's own covd (matched by `--cache-dir`), with a 120 s
     * watchdog: a frozen daemon that is never resumed would hang the IDE request until its
     * 11-minute timeout and the scenario would never finish.
     */
    private class CovdFreeze(private val sampler: ProcSampler, private val maxFrozenMs: Long = 120_000L) {
        private val released = AtomicBoolean(false)

        @Volatile var pid: Long? = null
            private set

        @Volatile var stoppedAtMs: Long? = null
            private set

        @Volatile var resumedAtMs: Long? = null
            private set

        private var watchdog: Thread? = null

        val frozenMs: Long? get() = resumedAtMs?.let { it - (stoppedAtMs ?: it) }

        fun stop(): Long? {
            val found = sampler.sigstopCovd()
            pid = found
            if (found != null) {
                stoppedAtMs = System.currentTimeMillis()
                watchdog = Thread({
                    try {
                        Thread.sleep(maxFrozenMs)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (!released.get()) {
                        runCatching { sampler.sigcontCovd() }
                    }
                }, "rp-covd-watchdog").apply { isDaemon = true; start() }
            }
            return found
        }

        fun resume() {
            if (!released.compareAndSet(false, true)) return
            watchdog?.interrupt()
            if (pid != null) resumedAtMs = System.currentTimeMillis()
            sampler.sigcontCovd()
        }
    }

    /** `R`/`S`/`D`/`T` of `/proc/<pid>/stat`; `T` proves the daemon is stopped. */
    private fun procState(pid: Long): String? =
        runCatching { ProcSampler.parseState(File("/proc/$pid/stat").readText()) }.getOrNull()

    // ------------------------------------------------------------------ helpers: JSON

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun JsonObject.lng(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    private fun JsonObject.bul(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun now(): Long = System.currentTimeMillis()

    private data class Attempt(
        val primary: PlanEntry,
        val target: PlanEntry,
        val tFreeze: Long,
        val tSwitch: Long,
        val tSwitchDone: Long,
        val answersBefore: Int,
        val tAllShards: Long,
        val tResume: Long = 0L,
    )

    private companion object {
        /**
         * Per-response pace of the artifact bodies. covd downloads up to 4 shards at once
         * (`maxDownloadConcurrency = 4`), so a 2 MB/s cap per response gives only ~4 s of wall
         * time for a 26 MB shard set; 500 KB/s per response makes the aggregate ~2 MB/s and the
         * download last 15-20 s, which is the window this scenario needs.
         */
        const val THROTTLE_BYTES_PER_SEC = 500_000
        const val ARTIFACT_PATH = "/artifacts"

        const val START_TIMEOUT_S = 420L
        const val ARTIFACT_TIMEOUT_S = 420L
        const val SECOND_START_TIMEOUT_S = 20L
        const val SWITCH_WINDOW_TIMEOUT_MS = 120_000L
        const val ANSWER_TIMEOUT_MS = 600_000L
        const val LOAD_TIMEOUT_MS = 900_000L
        const val REGRESSION_WAIT_MS = 4_000L
        const val POLL_MS = 500L
        const val DEBOUNCE_MS = 2_000L
        const val FREEZE_MARGIN_MS = 3_000L
        const val MAX_FROZEN_MS = 120_000L
        const val MAX_ATTEMPTS = 3
    }
}
