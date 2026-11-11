package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import com.github.yakov255.perlinecoverageinfo.realproject.harness.BridgeSnapshot
import com.github.yakov255.perlinecoverageinfo.realproject.harness.SandboxIde
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.BaselineEntry
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.PlanEntry
import org.junit.Test
import java.io.File
import java.time.Duration

/**
 * F1/F2: what the plugin does when the network breaks and when the GitLab token expires.
 *
 * The expectations are the ones fixed in "Failure handling contract (F1, F2)" of
 * `docs/real-project-testing.md`:
 *
 * * retryable daemon errors are retried silently three times (the shortened policy from
 *   `-Dcoverage.retry.delaysMs`) and then raise one balloon with *Retry now* and *Show log*;
 * * an expired token is never retried: one `AUTH_INVALID` answer, a sticky balloon
 *   "GitLab token expired or invalid" with *Update token…*, and no daemon/GitLab traffic while
 *   blocked (the 30 s poller and the HEAD tracker included);
 * * a successful load or a settings change resets the retry counter and clears the block.
 *
 * Every check of a new bridge field (`retryAttempt`, `retryMax`, `blocked`, `notifications`,
 * `lastCommand`) is read from [BridgeSnapshot.raw] and reported as "bridge has no field X yet"
 * when the running plugin predates the contract. The passive facts (saved covd answers, proxy
 * journal, loaded commit) are always recorded, so a run against the old plugin still shows what
 * actually happened (the "before" baseline).
 *
 * No credential is involved: the proxy strips the token and the plugin is seeded with `dummy`.
 */
class FailureScenarios : ScenarioBase() {

    // ------------------------------------------------------------------ F1a: one cut

    /**
     * F1a: cold cache, a single network cut in the middle of one artifact shard.
     *
     * covd retries a truncated body by itself (`DownloadArtifact` retries three times), so the
     * contract allows either one `ensure` or one plugin retry; both must end in a fresh LOADED
     * load and must not leave a balloon.
     */
    @Test
    fun f1aSingleNetworkCut() {
        scenario("F1a", "f1a-single-network-cut")
        val baseline = requireBaseline()
        switchTo(baseline.sha)
        resetProjectState()
        val ideName = "f1a"
        val checks = FailureSupport.Checks("F1a")

        proxy.throttle(THROTTLE_BYTES_PER_SEC, ARTIFACT_PATH)
        proxy.dropAfter(ARTIFACT_PATH, afterBytes = CUT_AFTER_BYTES, times = 1)
        event(
            "faults",
            "one cut after $CUT_AFTER_BYTES B of one artifact, throttle $THROTTLE_BYTES_PER_SEC B/s",
            eventData("dropAfter" to CUT_AFTER_BYTES, "times" to 1, "throttleBytesPerSec" to THROTTLE_BYTES_PER_SEC),
        )

        val startedAt = now()
        val ide = startIde(ideName, extraProps = FailureSupport.ideProps(ideName), waitSmart = false)
        val loaded = awaitLoadedOrNull(ide, baseline.sha, LOAD_TIMEOUT_MS, checks, "F1a")
        val finishedAt = now()

        val ensures = FailureSupport.ensures(cacheRoot)
        val ready = ensures.filter { it.state == "ready" }
        val errors = ensures.filter { it.isError }
        val journal = FailureSupport.journal(ctx.dir)
        val drops = journal.filter { it.fault == "drop" }
        val artifacts = journal.filter { it.artifactJobId != null && it.status in 200..299 }
        val duplicateArtifactCalls = ready.flatMap { it.artifactJobIds }
            .groupingBy { it }.eachCount().filterValues { count -> count > 1 }
        val retryAttempt = loaded?.let { FailureSupport.longField(it, "retryAttempt") }
        val balloons = loaded?.let { FailureSupport.notifications(it) }
        val contractSnapshot = loaded ?: ide.readBridge()

        event(
            "facts",
            "one cut: ensures=${ensures.size}, ready=${ready.size}, errors=${errors.size}",
            eventData(
                "loadedState" to loaded?.state,
                "loadedCommit" to loaded?.commit?.take(8),
                "loadedStale" to loaded?.stale,
                "loadMs" to (finishedAt - startedAt),
                "ensureAnswers" to ensures.size,
                "ensureStates" to ensures.joinToString(",") { it.describe() },
                "codes" to ensures.mapNotNull { it.code }.distinct().joinToString(","),
                "covdResponseCode" to (errors.firstOrNull()?.code ?: "none"),
                "retryAttempt" to retryAttempt,
                "blocked" to loaded?.let { FailureSupport.stringField(it, "blocked") },
                "notifications" to FailureSupport.describeBalloons(balloons),
                "dropRows" to drops.size,
                "droppedBytes" to drops.sumOf { it.bytesDown },
                "artifactRows2xx" to artifacts.size,
                "artifactCallsPerJob" to duplicateArtifactCalls.toString(),
                "proxyRequests" to journal.size,
            ),
        )

        checks.check("precondition", drops.isNotEmpty(), "the injector never cut an artifact response: proxy.jsonl has no row with fault=drop")
        checks.check("loaded", loaded != null && loaded.commit == baseline.sha, "coverage of ${baseline.sha.take(8)} is not loaded (state=${loaded?.state}, commit=${loaded?.commit?.take(8)})")
        checks.check("fresh", loaded?.stale != true, "the loaded coverage is stale; a network cut must not end in a stale answer")
        checks.check("no-error", errors.isEmpty(), "covd answered with an error although the cut was retried internally: ${FailureSupport.describe(errors)}")
        checks.check("ensure-count", ensures.size in 1..2, "expected exactly one ensure plus at most one policy retry, got ${ensures.size}: ${FailureSupport.describe(ensures)}")
        checks.check("ready", ready.any { it.commit == baseline.sha && !it.cached }, "no fresh ready answer for ${baseline.sha.take(8)}: ${FailureSupport.describe(ensures)}")
        if (checks.hasField(contractSnapshot, "notifications")) {
            checks.check("no-balloon", FailureSupport.retryBalloon(contractSnapshot!!) == null, "a retry balloon is listed after one recovered cut: ${FailureSupport.describeBalloons(balloons)}")
        }
        if (checks.hasField(contractSnapshot, "blocked")) {
            checks.check("not-blocked", FailureSupport.stringField(contractSnapshot!!, "blocked") == null, "blocked=${FailureSupport.stringField(contractSnapshot!!, "blocked")} after a successful load")
        }
        if (checks.hasField(contractSnapshot, "retryAttempt")) {
            checks.check("retry-reset", retryAttempt == 0L, "retryAttempt=$retryAttempt after a successful load, expected 0")
        }

        println(
            "F1a: LOADED ${baseline.sha.take(8)} in ${finishedAt - startedAt} ms; ensures=${ensures.size} " +
                "(${ensures.joinToString(",") { it.describe() }}), drops=${drops.size}, retryAttempt=$retryAttempt",
        )
        finish()
        checks.assertAll()
    }

    // ------------------------------------------------------------------ F1b: upstream forever

    /**
     * F1b: every artifact request fails (`503`; a truncated body is reported as `INTERNAL` by
     * covd, which the retry contract does not cover — see F1a). The plugin must retry three times
     * (shortened policy),
     * then raise one balloon; the 30 s poller and the HEAD tracker must not add ensures while the
     * balloon is up. A `reload` command (the "Retry now" action without UI) after the fault is
     * removed must load fresh and reset the counter.
     */
    @Test
    fun f1bPermanentNetworkCut() {
        scenario("F1b", "f1b-permanent-network-cut")
        val baseline = requireBaseline()
        switchTo(baseline.sha)
        resetProjectState()
        val ideName = "f1b"
        val checks = FailureSupport.Checks("F1b")

        // A truncated body is not usable, but covd currently classifies `unexpected EOF` as
        // INTERNAL (non-retryable, see the F1a evidence); a permanently failing upstream is
        // expressed as 503, which covd reports as GITLAB_API with retryable=true.
        proxy.respondStatus(ARTIFACT_PATH, 503, body = SERVICE_UNAVAILABLE, contentType = "application/json")
        event(
            "faults",
            "every artifact response answers 503",
            eventData("pathRegex" to ARTIFACT_PATH, "status" to 503, "retryDelaysMs" to RETRY_DELAYS),
        )

        val startedAt = now()
        val ide = startIde(ideName, extraProps = FailureSupport.ideProps(ideName, RETRY_DELAYS), waitSmart = false)

        val afterRetries = FailureSupport.awaitAnswers(cacheRoot, ERROR_BUDGET_MS) { answers ->
            val errors = answers.count { it.kind == "ensure" && it.isError }
            errors >= EXPECTED_ERRORS || answers.any { it.kind == "ensure" && it.state == "ready" }
        }
        val retryErrors = afterRetries.filter { it.kind == "ensure" && it.isError }
        val retriesDoneAt = now()
        // The retry delay starts when the answer arrives; the next request proves whether the
        // plugin's policy or the 30 s poller produced it. Answer timestamps alone cannot: one
        // 503 answer takes ~1 min because covd retries the artifact four times inside it.
        val retryRequestGaps = retryErrors.dropLast(1).map { error ->
            FailureSupport.firstRequestAfter(ctx.dir, error.atMs)?.let { it.t - error.atMs }
        }
        event(
            "retries",
            "retry phase ended with ${retryErrors.size} error answers",
            eventData(
                "errorAnswers" to retryErrors.size,
                "states" to retryErrors.joinToString(",") { it.describe() },
                "codes" to retryErrors.mapNotNull { it.code }.distinct().joinToString(","),
                "allRetryable" to retryErrors.all { it.retryableFailure },
                "elapsedMs" to (retriesDoneAt - startedAt),
                "retryRequestGapsMs" to retryRequestGaps.joinToString(",") { it?.toString() ?: "none" },
            ),
        )

        val balloonSnapshot = FailureSupport.awaitBridge(ide, BALLOON_WAIT_MS) { snapshot ->
            FailureSupport.retryBalloon(snapshot) != null
        }
        val balloon = balloonSnapshot?.let { FailureSupport.retryBalloon(it) }
        val retryAttemptAtBalloon = balloonSnapshot?.let { FailureSupport.longField(it, "retryAttempt") }
        val retryMaxAtBalloon = balloonSnapshot?.let { FailureSupport.longField(it, "retryMax") }
        val ensuresAtBalloon = FailureSupport.ensures(cacheRoot)

        // The contract says the poller does not ask again while the failure is being reported.
        pause(POLLER_WINDOW_MS, "poller window after the balloon")
        val afterWindow = FailureSupport.ensures(cacheRoot)
        val extraDuringWindow = afterWindow.size - ensuresAtBalloon.size
        val windowSnapshot = ide.readBridge()
        val contractSnapshot = windowSnapshot ?: balloonSnapshot

        event(
            "balloon",
            if (balloon == null) "no retry balloon appeared" else "retry balloon: ${balloon.describe()}",
            eventData(
                "balloonTitle" to balloon?.title,
                "balloonActions" to balloon?.actions?.joinToString(","),
                "balloonType" to balloon?.type,
                "notifications" to balloonSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) },
                "retryAttempt" to retryAttemptAtBalloon,
                "retryMax" to retryMaxAtBalloon,
                "ensuresAtBalloon" to ensuresAtBalloon.size,
                "ensuresAfterWindow" to afterWindow.size,
                "extraEnsuresInWindow" to extraDuringWindow,
                "windowMs" to POLLER_WINDOW_MS,
            ),
        )

        // "Retry now" without UI: faults gone + the bridge's reload command.
        proxy.clearFaults()
        FailureSupport.sendCommand(ideName, "reload")
        event("recovery", "faults cleared, reload command written", eventData("command" to "reload"))
        val loaded = awaitLoadedOrNull(ide, baseline.sha, RECOVERY_TIMEOUT_MS, checks, "F1b after reload")
        val finalAnswers = FailureSupport.ensures(cacheRoot)
        val finalRetryAttempt = loaded?.let { FailureSupport.longField(it, "retryAttempt") }
        val finalSnapshot = ide.readBridge()

        event(
            "recovered",
            "after reload: state=${loaded?.state} commit=${loaded?.commit?.take(8)}",
            eventData(
                "loadedState" to loaded?.state,
                "loadedCommit" to loaded?.commit?.take(8),
                "loadedStale" to loaded?.stale,
                "ensureAnswers" to finalAnswers.size,
                "newEnsures" to (finalAnswers.size - afterWindow.size),
                "retryAttempt" to finalRetryAttempt,
                "blocked" to finalSnapshot?.let { FailureSupport.stringField(it, "blocked") },
                "notifications" to finalSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) },
            ),
        )

        checks.check("retry-count", retryErrors.size == EXPECTED_ERRORS, "expected 1+3 = $EXPECTED_ERRORS error answers, got ${retryErrors.size}: ${FailureSupport.describe(retryErrors)}")
        checks.check("retryable", retryErrors.take(EXPECTED_ERRORS).all { it.retryableFailure }, "not every retryable failure is flagged retryable: ${FailureSupport.describe(retryErrors.take(EXPECTED_ERRORS))}")
        checks.check(
            "retry-policy-timing",
            retryRequestGaps.isNotEmpty() && retryRequestGaps.all { it != null && it <= RETRY_START_LIMIT_MS },
            "the retries started $retryRequestGaps ms after the previous answer: that is the 30 s poller, " +
                "not the shortened retry policy",
        )
        if (checks.hasField(contractSnapshot, "notifications")) {
            checks.check("balloon", balloon != null, "no notification with the action 'Retry now' is listed: ${contractSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) }}")
            checks.check("balloon-retry-action", balloon?.hasAction("Retry now") == true, "the balloon has no 'Retry now' action: ${balloon?.actions}")
            checks.check("balloon-show-log-action", balloon?.hasAction("Show log") == true, "the balloon has no 'Show log' action: ${balloon?.actions}")
        }
        if (checks.hasField(contractSnapshot, "retryAttempt")) {
            checks.check("retry-attempt", (retryAttemptAtBalloon ?: 0L) > 0L, "retryAttempt=$retryAttemptAtBalloon after the balloon, expected the exhausted counter")
        }
        checks.check("poller-silent", extraDuringWindow == 0, "$extraDuringWindow extra ensure answers arrived in the $POLLER_WINDOW_MS ms window after the balloon")
        checks.check("recovered-loaded", loaded != null && loaded.commit == baseline.sha, "the reload did not load ${baseline.sha.take(8)}")
        checks.check("recovered-fresh", loaded?.stale != true, "the recovered coverage is stale")
        if (checks.hasField(finalSnapshot, "retryAttempt")) {
            checks.check("recovered-counter", finalRetryAttempt == 0L, "retryAttempt=$finalRetryAttempt after a successful reload, expected 0")
        }
        if (checks.hasField(finalSnapshot, "blocked")) {
            checks.check("recovered-blocked", FailureSupport.stringField(finalSnapshot!!, "blocked") == null, "blocked=${FailureSupport.stringField(finalSnapshot!!, "blocked")} after a successful reload")
        }

        println(
            "F1b: errors=${retryErrors.size}, balloon=${balloon?.title ?: "none"} (${balloon?.actions}), " +
                "extra ensures in ${POLLER_WINDOW_MS} ms window=$extraDuringWindow, " +
                "recovered=${loaded?.state}/${loaded?.commit?.take(8)} retryAttempt=$finalRetryAttempt",
        )
        finish()
        checks.assertAll()
    }

    // ------------------------------------------------------------------ F1c: failure -> failure -> ok

    /**
     * F1c: the first attempt fails with an upstream `503`, the first retry with `502`, the second
     * retry succeeds. The faults are switched as soon as the previous error answer is saved, so
     * each attempt exercises one branch of the retry policy and a failure during the ladder must
     * not reset the counter, raise a balloon or skip the final success.
     *
     * The upstream answers are 5xx rather than a body cut on purpose: covd classifies a truncated
     * artifact download as `INTERNAL` (non-retryable), so a literal cut cannot start the ladder.
     * The F1a/F1b evidence records that finding.
     */
    @Test
    fun f1cCutThen503ThenSuccess() {
        scenario("F1c", "f1c-cut-then-503-then-success")
        val baseline = requireBaseline()
        switchTo(baseline.sha)
        resetProjectState()
        val ideName = "f1c"
        val checks = FailureSupport.Checks("F1c")

        // The pure body cut of F1a is classified INTERNAL by covd, so it cannot start the retry
        // ladder; the two failures of this scenario are therefore upstream 5xx answers (503, then
        // 502). They still exercise the contract path "second failure in the middle of the ladder".
        proxy.respondStatus(ARTIFACT_PATH, 503, body = SERVICE_UNAVAILABLE, contentType = "application/json")
        event("faults", "attempt 1: every artifact response answers 503", eventData("pathRegex" to ARTIFACT_PATH, "status" to 503))

        val ide = startIde(ideName, extraProps = FailureSupport.ideProps(ideName, F1C_RETRY_DELAYS), waitSmart = false)

        val afterFirst = FailureSupport.awaitAnswers(cacheRoot, ATTEMPT_WAIT_MS, pollMs = FAST_POLL_MS) { answers ->
            answers.any { it.kind == "ensure" && it.retryableFailure }
        }
        val firstError = afterFirst.lastOrNull { it.kind == "ensure" && it.isError }
        val firstErrorAt = firstError?.atMs ?: now()
        event("attempt-1", "first attempt failed: ${firstError?.describe()}", eventData("answer" to firstError?.describe(), "code" to firstError?.code))

        proxy.clearFaults()
        proxy.respondStatus(ARTIFACT_PATH, 502, body = BAD_GATEWAY, contentType = "application/json")
        event("faults", "attempt 2: every artifact response answers 502")

        val afterSecond = FailureSupport.awaitAnswers(cacheRoot, ATTEMPT_WAIT_MS, pollMs = FAST_POLL_MS) { answers ->
            answers.count { it.kind == "ensure" && it.isError } >= 2
        }
        val errorsBeforeRecovery = afterSecond.filter { it.kind == "ensure" && it.isError }
        val secondError = errorsBeforeRecovery.getOrNull(1)
        val secondRequestAt = FailureSupport.firstRequestAfter(ctx.dir, firstErrorAt)?.t
        val retryStartMs = secondRequestAt?.minus(firstErrorAt)
        event(
            "attempt-2",
            "second error after ${secondError?.atMs?.minus(firstErrorAt) ?: -1} ms (retry request after $retryStartMs ms)",
            eventData(
                "answers" to FailureSupport.describe(errorsBeforeRecovery),
                "secondCode" to secondError?.code,
                "retryRequestDelayMs" to retryStartMs,
                "secondAnswerDelayMs" to secondError?.atMs?.minus(firstErrorAt),
            ),
        )

        proxy.clearFaults()
        event("faults", "attempt 3: no faults")
        val loaded = awaitLoadedOrNull(ide, baseline.sha, RECOVERY_TIMEOUT_MS, checks, "F1c")
        val finalAnswers = FailureSupport.ensures(cacheRoot)
        val finalErrorCount = finalAnswers.count { it.isError }
        val ready = finalAnswers.filter { it.state == "ready" }
        val thirdAnswer = finalAnswers.dropWhile { it.seq <= (secondError?.seq ?: Long.MAX_VALUE) }.firstOrNull { it.state == "ready" }
        val retryAttempt = loaded?.let { FailureSupport.longField(it, "retryAttempt") }
        val finalSnapshot = ide.readBridge()

        event(
            "facts",
            "503 -> 502 -> success: states=${FailureSupport.describe(finalAnswers)}",
            eventData(
                "ensureAnswers" to finalAnswers.size,
                "errorAnswers" to finalErrorCount,
                "readyAnswers" to ready.size,
                "codes" to finalAnswers.mapNotNull { it.code }.distinct().joinToString(","),
                "retryRequestDelayMs" to retryStartMs,
                "thirdAnswerAtMs" to thirdAnswer?.atMs,
                "loadedState" to loaded?.state,
                "loadedCommit" to loaded?.commit?.take(8),
                "loadedStale" to loaded?.stale,
                "retryAttempt" to retryAttempt,
                "blocked" to finalSnapshot?.let { FailureSupport.stringField(it, "blocked") },
                "notifications" to finalSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) },
            ),
        )

        checks.check("first-error", firstError != null && firstError.retryableFailure, "the first attempt did not produce a retryable error: ${firstError?.describe()}")
        checks.check("second-error", secondError != null, "no retry ran after the first failure: ${FailureSupport.describe(errorsBeforeRecovery)}")
        checks.check(
            "policy-not-poller",
            secondError != null && retryStartMs != null && retryStartMs <= RETRY_START_LIMIT_MS,
            "the second attempt's first request came $retryStartMs ms after the first answer: " +
                "that is the 30 s poller, not the shortened retry policy",
        )
        checks.check("ensure-count", finalAnswers.size == 3, "expected exactly 3 attempts (503, 502, success), got ${finalAnswers.size}: ${FailureSupport.describe(finalAnswers)}")
        checks.check("loaded", loaded != null && loaded.commit == baseline.sha, "coverage of ${baseline.sha.take(8)} is not loaded after the third attempt")
        checks.check("fresh", loaded?.stale != true, "the recovered coverage is stale")
        if (checks.hasField(finalSnapshot, "notifications")) {
            checks.check("no-balloon", FailureSupport.retryBalloon(finalSnapshot!!) == null, "a retry balloon is listed although the third attempt succeeded: ${FailureSupport.describeBalloons(FailureSupport.notifications(finalSnapshot!!))}")
        }
        if (checks.hasField(finalSnapshot, "retryAttempt")) {
            checks.check("retry-reset", retryAttempt == 0L, "retryAttempt=$retryAttempt after a successful load, expected 0")
        }
        if (checks.hasField(finalSnapshot, "blocked")) {
            checks.check("not-blocked", FailureSupport.stringField(finalSnapshot!!, "blocked") == null, "blocked=${FailureSupport.stringField(finalSnapshot!!, "blocked")} after a successful load")
        }

        println(
            "F1c: ensures=${finalAnswers.size} (${finalAnswers.joinToString(",") { it.describe() }}), " +
                "retry request after $retryStartMs ms, loaded=${loaded?.commit?.take(8)}",
        )
        finish()
        checks.assertAll()
    }

    // ------------------------------------------------------------------ F2a: 401 from the start

    /**
     * F2a: every GitLab call answers `401`. One `AUTH_INVALID` ensure, no retries, a sticky
     * "token expired" balloon, no traffic at all for 90 s (poller tick and a HEAD switch), then a
     * new token through the settings path loads the current HEAD without an IDE restart.
     */
    @Test
    fun f2aTokenExpiredFromStart() {
        scenario("F2a", "f2a-token-expired-from-start")
        val baseline = requireBaseline()
        val other = otherBranch(baseline.sha)
        switchTo(baseline.sha)
        resetProjectState()
        val ideName = "f2a"
        val checks = FailureSupport.Checks("F2a")

        proxy.respondStatus(API_PATH, 401, body = UNAUTHORIZED, contentType = "application/json")
        event("faults", "every /api/v4/ request answers 401", eventData("pathRegex" to API_PATH, "status" to 401))

        val startedAt = now()
        val ide = startIde(ideName, extraProps = FailureSupport.ideProps(ideName, RETRY_DELAYS), waitSmart = false)

        val firstPhase = FailureSupport.awaitAnswers(cacheRoot, FIRST_ANSWER_TIMEOUT_MS) { answers ->
            answers.any { it.kind == "ensure" && it.isError }
        }
        val firstAnswer = firstPhase.lastOrNull { it.kind == "ensure" }
        val blockStartedAt = firstAnswer?.atMs ?: now()

        // The block marker and the sticky balloon appear right after the first answer.
        val marked = FailureSupport.awaitBridge(ide, MARKER_WAIT_MS) { snapshot ->
            FailureSupport.fieldPresent(snapshot, "blocked") && FailureSupport.stringField(snapshot, "blocked") == "AUTH"
        }
        val balloonSnapshot = FailureSupport.awaitBridge(ide, MARKER_WAIT_MS) { snapshot ->
            FailureSupport.tokenBalloon(snapshot) != null
        }
        val balloon = balloonSnapshot?.let { FailureSupport.tokenBalloon(it) }
        event(
            "blocked",
            "first answer: ${firstAnswer?.describe()}; balloon: ${balloon?.describe() ?: "none"}",
            eventData(
                "firstAnswer" to firstAnswer?.describe(),
                "code" to firstAnswer?.code,
                "retryable" to firstAnswer?.retryable,
                "blocked" to marked?.let { FailureSupport.stringField(it, "blocked") },
                "blockedFieldPresent" to (marked != null),
                "balloonTitle" to balloon?.title,
                "balloonActions" to balloon?.actions?.joinToString(","),
                "notifications" to balloonSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) },
            ),
        )

        // Half of the 90 s window: a branch switch must not reach GitLab while the block is up.
        val switchAt = blockStartedAt + HEAD_SWITCH_AT_MS
        sleepUntil(switchAt)
        switchTo(other.sha)
        val switchedAt = now()
        event("head-switch", "worktree switched to ${other.sha.take(8)} while blocked", eventData("commit" to other.sha))

        val windowEnd = blockStartedAt + BLOCK_WINDOW_MS
        sleepUntil(windowEnd)
        val windowSnapshot = ide.readBridge()
        val requestsInWindow = FailureSupport.requestsBetween(ctx.dir, blockStartedAt + EVENT_MARGIN_MS, windowEnd)
        val answersInWindow = FailureSupport.answers(cacheRoot).filter { it.atMs >= blockStartedAt + EVENT_MARGIN_MS }
        val stuckBalloon = windowSnapshot?.let { FailureSupport.tokenBalloon(it) }

        event(
            "window",
            "90 s block window ended: ${requestsInWindow.size} GitLab requests, ${answersInWindow.size} answers",
            eventData(
                "windowMs" to BLOCK_WINDOW_MS,
                "switchedAtMs" to switchedAt,
                "gitlabRequests" to requestsInWindow.size,
                "requestPaths" to requestsInWindow.take(10).joinToString(",") { it.path },
                "answers" to FailureSupport.describe(answersInWindow),
                "balloonStillListed" to (stuckBalloon != null),
                "blocked" to windowSnapshot?.let { FailureSupport.stringField(it, "blocked") },
            ),
        )

        // Recovery through the settings path: new token + Apply, no IDE restart.
        proxy.clearFaults()
        FailureSupport.sendCommand(ideName, "settings.token=$REPLACEMENT_TOKEN")
        FailureSupport.sendCommand(ideName, "settings.apply")
        event("recovery", "faults cleared, settings.token + settings.apply written", eventData("command" to "settings.apply"))

        val loaded = awaitLoadedOrNull(ide, other.sha, RECOVERY_TIMEOUT_MS, checks, "F2a after Apply")
        val finalAnswers = FailureSupport.ensures(cacheRoot)
        val finalSnapshot = ide.readBridge()
        val finalBlocked = finalSnapshot?.let { FailureSupport.stringField(it, "blocked") }
        val retryAttempt = finalSnapshot?.let { FailureSupport.longField(it, "retryAttempt") }
        val authAnswers = finalAnswers.filter { it.code == "AUTH_INVALID" }

        event(
            "recovered",
            "after Apply: ${loaded?.state} ${loaded?.commit?.take(8)}",
            eventData(
                "loadedState" to loaded?.state,
                "loadedCommit" to loaded?.commit?.take(8),
                "loadedStale" to loaded?.stale,
                "head" to other.sha,
                "authAnswers" to authAnswers.size,
                "ensureAnswers" to finalAnswers.size,
                "blocked" to finalBlocked,
                "retryAttempt" to retryAttempt,
                "notifications" to finalSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) },
            ),
        )

        checks.check("first-code", firstAnswer?.code == "AUTH_INVALID", "the first answer is not AUTH_INVALID: ${firstAnswer?.describe()}")
        checks.check("single-auth-answer", authAnswers.size == 1 && authAnswers.single().atMs <= blockStartedAt + EVENT_MARGIN_MS, "expected exactly one AUTH_INVALID answer and no retries, got ${authAnswers.size}: ${FailureSupport.describe(authAnswers)}")
        checks.check("no-answers-in-window", answersInWindow.isEmpty(), "${answersInWindow.size} answers arrived during the 90 s block: ${FailureSupport.describe(answersInWindow)}")
        checks.check("no-gitlab-in-window", requestsInWindow.isEmpty(), "${requestsInWindow.size} GitLab requests arrived during the 90 s block: ${requestsInWindow.take(5).joinToString(",") { it.path }}")
        val blockedProbe = marked ?: windowSnapshot ?: ide.readBridge()
        val notificationProbe = balloonSnapshot ?: windowSnapshot ?: ide.readBridge()
        if (checks.hasField(blockedProbe, "blocked")) {
            checks.check("blocked-auth", FailureSupport.stringField(blockedProbe!!, "blocked") == "AUTH", "blocked=${FailureSupport.stringField(blockedProbe!!, "blocked")}, expected AUTH")
        }
        if (checks.hasField(notificationProbe, "notifications")) {
            checks.check("token-balloon", balloon != null, "no 'GitLab token expired or invalid' balloon with an update action: ${notificationProbe?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) }}")
            checks.check("token-balloon-action", balloon?.hasAction("Update token") == true, "the token balloon has no 'Update token…' action: ${balloon?.actions}")
        }
        checks.check("balloon-sticky", stuckBalloon != null, "the token balloon is gone before the block was cleared: ${windowSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) }}")
        checks.check("recovered-loaded", loaded != null && loaded.commit == other.sha, "Apply did not load the current HEAD ${other.sha.take(8)} (state=${loaded?.state}, commit=${loaded?.commit?.take(8)})")
        checks.check("recovered-fresh", loaded?.stale != true, "the coverage loaded after Apply is stale")
        if (checks.hasField(finalSnapshot, "blocked")) {
            checks.check("blocked-cleared", finalBlocked == null, "blocked=$finalBlocked after Apply, expected null")
        }
        if (checks.hasField(finalSnapshot, "retryAttempt")) {
            checks.check("retry-reset", retryAttempt == 0L, "retryAttempt=$retryAttempt after Apply, expected 0")
        }

        println(
            "F2a: first=${firstAnswer?.describe()}, auth answers=${authAnswers.size}, " +
                "requests in 90 s window=${requestsInWindow.size}, balloon=${balloon?.title ?: "none"}, " +
                "after Apply=${loaded?.state}/${loaded?.commit?.take(8)} blocked=$finalBlocked",
        )
        finish()
        checks.assertAll()
    }

    // ------------------------------------------------------------------ F2b: 401 mid-download

    /**
     * F2b: the token dies while the shards are coming. The in-flight `ensure` ends in
     * `AUTH_INVALID`, the same block/balloon appears, and after recovery the whole download is
     * repeated from scratch.
     */
    @Test
    fun f2bTokenExpiredMidDownload() {
        scenario("F2b", "f2b-token-expired-mid-download")
        val baseline = requireBaseline()
        switchTo(baseline.sha)
        resetProjectState()
        val ideName = "f2b"
        val checks = FailureSupport.Checks("F2b")

        // Slow enough that the 401 lands while several shards are still on the wire.
        proxy.throttle(MID_DOWNLOAD_THROTTLE_BYTES_PER_SEC, ARTIFACT_PATH)
        val startedAt = now()
        val ide = startIde(ideName, extraProps = FailureSupport.ideProps(ideName, RETRY_DELAYS), waitSmart = false)

        // covd downloads up to four shards at once. Once four artifact requests are open, the
        // remaining shards are not requested yet: the 401 that is installed now hits them, so the
        // download certainly fails in the middle.
        val fourShards = proxy.awaitRequests(ARTIFACT_PATH, 4, Duration.ofSeconds(ARTIFACT_TIMEOUT_S))
        val artifactsBefore = proxy.stats().artifactsCompleted
        val startedBefore = proxy.stats().artifactsStarted
        proxy.respondStatus(API_PATH, 401, body = UNAUTHORIZED, contentType = "application/json")
        val injectedAt = now()
        event(
            "faults",
            "401 from the next request on; $startedBefore shard requests were open, $artifactsBefore complete",
            eventData(
                "fourShards" to fourShards,
                "artifactsCompleted" to artifactsBefore,
                "artifactsStarted" to startedBefore,
                "injectedAtMs" to injectedAt,
            ),
        )

        val firstPhase = FailureSupport.awaitAnswers(cacheRoot, FIRST_ANSWER_TIMEOUT_MS) { answers ->
            answers.any { it.kind == "ensure" && it.isError }
        }
        val firstAnswer = firstPhase.lastOrNull { it.kind == "ensure" }
        val blockStartedAt = firstAnswer?.atMs ?: now()

        val marked = FailureSupport.awaitBridge(ide, MARKER_WAIT_MS) { snapshot ->
            FailureSupport.fieldPresent(snapshot, "blocked") && FailureSupport.stringField(snapshot, "blocked") == "AUTH"
        }
        val balloonSnapshot = FailureSupport.awaitBridge(ide, MARKER_WAIT_MS) { snapshot ->
            FailureSupport.tokenBalloon(snapshot) != null
        }
        val balloon = balloonSnapshot?.let { FailureSupport.tokenBalloon(it) }
        event(
            "blocked",
            "mid-download 401: ${firstAnswer?.describe()}; balloon: ${balloon?.describe() ?: "none"}",
            eventData(
                "firstAnswer" to firstAnswer?.describe(),
                "code" to firstAnswer?.code,
                "downloadedBefore" to (proxy.stats().artifactsCompleted),
                "blocked" to marked?.let { FailureSupport.stringField(it, "blocked") },
                "balloonTitle" to balloon?.title,
                "balloonActions" to balloon?.actions?.joinToString(","),
                "notifications" to balloonSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) },
            ),
        )

        // No immediate retry: the counter stays at one answer for longer than a retry delay.
        pause(NO_RETRY_WINDOW_MS, "no-retry window after the 401")
        val quietAnswers = FailureSupport.answers(cacheRoot)
        // Only the window right after the answer proves "no retry": the 30 s poller answers much
        // later and is measured separately by F2a.
        val blockedWindowEnd = blockStartedAt + NO_RETRY_WINDOW_MS
        val retriesInWindow = quietAnswers.count { answer ->
            answer.kind == "ensure" && answer.code == "AUTH_INVALID" &&
                answer.atMs > blockStartedAt && answer.atMs <= blockedWindowEnd
        }
        val quietAuth = quietAnswers.count { it.kind == "ensure" && it.code == "AUTH_INVALID" }
        val quietRequests = FailureSupport.requestCountBetween(ctx.dir, injectedAt + EVENT_MARGIN_MS, now())

        proxy.clearFaults()
        val recoveryStart = now()
        val artifactsBeforeRecovery = proxy.stats().artifactsCompleted
        FailureSupport.sendCommand(ideName, "settings.token=$REPLACEMENT_TOKEN")
        FailureSupport.sendCommand(ideName, "settings.apply")
        event(
            "recovery",
            "faults cleared, settings.token + settings.apply written",
            eventData("artifactsBeforeRecovery" to artifactsBeforeRecovery, "requestsDuringBlock" to quietRequests),
        )

        val loaded = awaitLoadedOrNull(ide, baseline.sha, RECOVERY_TIMEOUT_MS, checks, "F2b after Apply")
        val finalAnswers = FailureSupport.ensures(cacheRoot)
        val finalSnapshot = ide.readBridge()
        val finalBlocked = finalSnapshot?.let { FailureSupport.stringField(it, "blocked") }
        val retryAttempt = finalSnapshot?.let { FailureSupport.longField(it, "retryAttempt") }
        val ready = finalAnswers.lastOrNull { it.state == "ready" }
        val artifactsAfterRecovery = proxy.stats().artifactsCompleted

        event(
            "recovered",
            "after Apply: ${loaded?.state} ${loaded?.commit?.take(8)}",
            eventData(
                "loadedState" to loaded?.state,
                "loadedCommit" to loaded?.commit?.take(8),
                "loadedStale" to loaded?.stale,
                "quietAuthAnswers" to quietAuth,
                "retriesInNoRetryWindow" to retriesInWindow,
                "requestsDuringBlock" to quietRequests,
                "ensureAnswers" to finalAnswers.size,
                "readyAnswers" to finalAnswers.count { it.state == "ready" },
                "readyCached" to ready?.cached,
                "artifactsBeforeRecovery" to artifactsBeforeRecovery,
                "artifactsAfterRecovery" to artifactsAfterRecovery,
                "artifactsDuringRecovery" to (artifactsAfterRecovery - artifactsBeforeRecovery),
                "downloadMs" to ready?.elapsedMs,
                "blocked" to finalBlocked,
                "retryAttempt" to retryAttempt,
                "notifications" to finalSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) },
            ),
        )

        checks.check(
            "precondition",
            fourShards && startedBefore >= 4 && startedBefore < SHARDS_EXPECTED,
            "the 401 was installed after $startedBefore artifact requests ($artifactsBefore complete): the download had not started or had already finished",
        )
        checks.check("first-code", firstAnswer?.code == "AUTH_INVALID", "the mid-download 401 did not end in AUTH_INVALID: ${firstAnswer?.describe()}")
        checks.check("single-auth-answer", retriesInWindow == 0, "$retriesInWindow AUTH_INVALID retries arrived within ${NO_RETRY_WINDOW_MS} ms of the first answer; the token path must not retry")
        val blockedProbe = marked ?: ide.readBridge()
        val notificationProbe = balloonSnapshot ?: ide.readBridge()
        if (checks.hasField(blockedProbe, "blocked")) {
            checks.check("blocked-auth", FailureSupport.stringField(blockedProbe!!, "blocked") == "AUTH", "blocked=${FailureSupport.stringField(blockedProbe!!, "blocked")}, expected AUTH")
        }
        if (checks.hasField(notificationProbe, "notifications")) {
            checks.check("token-balloon", balloon != null, "no token balloon: ${notificationProbe?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) }}")
            checks.check("token-balloon-action", balloon?.hasAction("Update token") == true, "the token balloon has no 'Update token…' action: ${balloon?.actions}")
        }
        checks.check("recovered-loaded", loaded != null && loaded.commit == baseline.sha, "Apply did not load ${baseline.sha.take(8)}")
        checks.check("recovered-fresh", loaded?.stale != true, "the recovered coverage is stale")
        checks.check("full-redownload", artifactsAfterRecovery - artifactsBeforeRecovery > 0, "no artifact was downloaded again after recovery")
        checks.check("not-cached", ready != null && !ready.cached, "the recovered answer is marked cached; the failed download must not have produced a usable artifact")
        if (checks.hasField(finalSnapshot, "blocked")) {
            checks.check("blocked-cleared", finalBlocked == null, "blocked=$finalBlocked after Apply, expected null")
        }
        if (checks.hasField(finalSnapshot, "retryAttempt")) {
            checks.check("retry-reset", retryAttempt == 0L, "retryAttempt=$retryAttempt after Apply, expected 0")
        }

        println(
            "F2b: artifacts before 401=$artifactsBefore, first=${firstAnswer?.describe()}, auth answers=$quietAuth, " +
                "requests during block=$quietRequests, after Apply=${loaded?.state}/${loaded?.commit?.take(8)}, " +
                "artifacts re-downloaded=${artifactsAfterRecovery - artifactsBeforeRecovery}",
        )
        finish()
        checks.assertAll()
    }

    // ------------------------------------------------------------------ F2c: 401 then branch switch

    /**
     * F2c: the token expires, then the user switches branches. While blocked, neither the HEAD
     * tracker nor the poller may call GitLab; Apply loads the *current* HEAD.
     */
    @Test
    fun f2cTokenExpiredThenBranchSwitch() {
        scenario("F2c", "f2c-token-expired-then-branch-switch")
        val branches = tour(2)
        check(branches.size >= 2) { "F2c needs two tour branches in the local repository, have ${branches.size}" }
        val a = branches[0]
        val b = branches[1]
        switchTo(a.sha)
        resetProjectState()
        val ideName = "f2c"
        val checks = FailureSupport.Checks("F2c")

        proxy.respondStatus(API_PATH, 401, body = UNAUTHORIZED, contentType = "application/json")
        event("faults", "every /api/v4/ request answers 401; A=${a.ref}, B=${b.ref}", eventData("a" to a.sha, "b" to b.sha))

        val ide = startIde(ideName, extraProps = FailureSupport.ideProps(ideName, RETRY_DELAYS), waitSmart = false)
        val firstPhase = FailureSupport.awaitAnswers(cacheRoot, FIRST_ANSWER_TIMEOUT_MS) { answers ->
            answers.any { it.kind == "ensure" && it.isError }
        }
        val firstAnswer = firstPhase.lastOrNull { it.kind == "ensure" }
        val marked = FailureSupport.awaitBridge(ide, MARKER_WAIT_MS) { snapshot ->
            FailureSupport.fieldPresent(snapshot, "blocked") && FailureSupport.stringField(snapshot, "blocked") == "AUTH"
        }
        val balloonSnapshot = FailureSupport.awaitBridge(ide, MARKER_WAIT_MS) { snapshot ->
            FailureSupport.tokenBalloon(snapshot) != null
        }
        val balloon = balloonSnapshot?.let { FailureSupport.tokenBalloon(it) }
        event(
            "blocked",
            "first answer: ${firstAnswer?.describe()}; balloon: ${balloon?.describe() ?: "none"}",
            eventData(
                "firstAnswer" to firstAnswer?.describe(),
                "code" to firstAnswer?.code,
                "blocked" to marked?.let { FailureSupport.stringField(it, "blocked") },
                "balloonTitle" to balloon?.title,
                "balloonActions" to balloon?.actions?.joinToString(","),
            ),
        )

        val switchAt = now()
        switchTo(b.sha)
        val windowEnd = switchAt + SWITCH_WINDOW_MS
        sleepUntil(windowEnd)
        val windowSnapshot = ide.readBridge()
        val requestsAfterSwitch = FailureSupport.requestsBetween(ctx.dir, switchAt + EVENT_MARGIN_MS, windowEnd)
        val answersAfterSwitch = FailureSupport.answers(cacheRoot).filter { it.atMs >= switchAt + EVENT_MARGIN_MS }
        event(
            "head-switch",
            "switched to ${b.sha.take(8)} while blocked: ${requestsAfterSwitch.size} requests, ${answersAfterSwitch.size} answers",
            eventData(
                "a" to a.sha,
                "b" to b.sha,
                "windowMs" to SWITCH_WINDOW_MS,
                "gitlabRequests" to requestsAfterSwitch.size,
                "requestPaths" to requestsAfterSwitch.take(10).joinToString(",") { it.path },
                "answers" to FailureSupport.describe(answersAfterSwitch),
                "blocked" to windowSnapshot?.let { FailureSupport.stringField(it, "blocked") },
            ),
        )

        proxy.clearFaults()
        FailureSupport.sendCommand(ideName, "settings.token=$REPLACEMENT_TOKEN")
        FailureSupport.sendCommand(ideName, "settings.apply")
        event("recovery", "faults cleared, settings.token + settings.apply written")

        val loaded = awaitLoadedOrNull(ide, b.sha, RECOVERY_TIMEOUT_MS, checks, "F2c after Apply")
        val finalAnswers = FailureSupport.ensures(cacheRoot)
        val finalSnapshot = ide.readBridge()
        val finalBlocked = finalSnapshot?.let { FailureSupport.stringField(it, "blocked") }
        val retryAttempt = finalSnapshot?.let { FailureSupport.longField(it, "retryAttempt") }
        val authAnswers = finalAnswers.filter { it.code == "AUTH_INVALID" }

        event(
            "recovered",
            "after Apply: ${loaded?.state} ${loaded?.commit?.take(8)}",
            eventData(
                "loadedState" to loaded?.state,
                "loadedCommit" to loaded?.commit?.take(8),
                "loadedStale" to loaded?.stale,
                "head" to b.sha,
                "authAnswers" to authAnswers.size,
                "ensureAnswers" to finalAnswers.size,
                "blocked" to finalBlocked,
                "retryAttempt" to retryAttempt,
                "notifications" to finalSnapshot?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) },
            ),
        )

        checks.check("first-code", firstAnswer?.code == "AUTH_INVALID", "the first answer is not AUTH_INVALID: ${firstAnswer?.describe()}")
        checks.check("single-auth-answer", authAnswers.size == 1, "expected exactly one AUTH_INVALID answer, got ${authAnswers.size}: ${FailureSupport.describe(authAnswers)}")
        checks.check("no-gitlab-after-switch", requestsAfterSwitch.isEmpty(), "${requestsAfterSwitch.size} GitLab requests after the branch switch while blocked: ${requestsAfterSwitch.take(5).joinToString(",") { it.path }}")
        checks.check("no-answers-after-switch", answersAfterSwitch.isEmpty(), "${answersAfterSwitch.size} daemon answers after the branch switch while blocked: ${FailureSupport.describe(answersAfterSwitch)}")
        val blockedProbe = marked ?: ide.readBridge()
        val notificationProbe = balloonSnapshot ?: ide.readBridge()
        if (checks.hasField(blockedProbe, "blocked")) {
            checks.check("blocked-auth", FailureSupport.stringField(blockedProbe!!, "blocked") == "AUTH", "blocked=${FailureSupport.stringField(blockedProbe!!, "blocked")}, expected AUTH")
        }
        if (checks.hasField(notificationProbe, "notifications")) {
            checks.check("token-balloon", balloon != null, "no token balloon: ${notificationProbe?.let { FailureSupport.describeBalloons(FailureSupport.notifications(it)) }}")
            checks.check("token-balloon-action", balloon?.hasAction("Update token") == true, "the token balloon has no 'Update token…' action: ${balloon?.actions}")
        }
        checks.check("loaded-current-head", loaded != null && loaded.commit == b.sha, "Apply loaded ${loaded?.commit?.take(8)}, expected the current HEAD ${b.sha.take(8)}")
        checks.check("loaded-fresh", loaded?.stale != true, "the coverage loaded after Apply is stale")
        if (checks.hasField(finalSnapshot, "blocked")) {
            checks.check("blocked-cleared", finalBlocked == null, "blocked=$finalBlocked after Apply, expected null")
        }
        if (checks.hasField(finalSnapshot, "retryAttempt")) {
            checks.check("retry-reset", retryAttempt == 0L, "retryAttempt=$retryAttempt after Apply, expected 0")
        }

        println(
            "F2c: first=${firstAnswer?.describe()}, switch A->B ${a.sha.take(8)}->${b.sha.take(8)}, " +
                "requests after switch=${requestsAfterSwitch.size}, after Apply=${loaded?.state}/${loaded?.commit?.take(8)}",
        )
        finish()
        checks.assertAll()
    }

    // ------------------------------------------------------------------ helpers

    private fun requireBaseline(): BaselineEntry =
        plan.baseline ?: throw AssertionError("the plan has no baseline: run scripts/real-project-prepare.sh")

    /** A tour branch other than [sha]; a HEAD switch must move to a different commit. */
    private fun otherBranch(sha: String): PlanEntry {
        val branches = tour(4)
        return branches.firstOrNull { it.sha != sha }
            ?: throw AssertionError("the plan has no local branch other than ${sha.take(8)}")
    }

    /**
     * Removes project-persistent plugin state a previous run may have left in the shared worktree
     * (a pinned artifact or a hidden gutter would suppress the load this scenario needs).
     */
    private fun resetProjectState() {
        val idea = File(worktree, ".idea")
        listOf("coverageGutterVisibility.xml", "coverageUserSelection.xml").forEach { name ->
            File(idea, name).delete()
        }
    }

    /** Waits for a fresh LOADED snapshot of [sha]; on timeout records a contract violation. */
    private fun awaitLoadedOrNull(
        ide: SandboxIde,
        sha: String,
        timeoutMs: Long,
        checks: FailureSupport.Checks,
        what: String,
    ): BridgeSnapshot? {
        val snapshot = FailureSupport.awaitBridge(ide, timeoutMs) { s ->
            s.commit == sha && s.loading != true && s.isLoaded && s.stale != true
        }
        if (snapshot == null) {
            val last = ide.readBridge()
            checks.fail(
                "$what: coverage of ${sha.take(8)} never became LOADED fresh within $timeoutMs ms " +
                    "(state=${last?.state}, commit=${last?.commit?.take(8)}, stale=${last?.stale}, " +
                    "loading=${last?.loading}, lastError=${last?.lastError})",
            )
        }
        return snapshot
    }

    private fun sleepUntil(deadlineMs: Long) {
        val left = deadlineMs - now()
        if (left > 0) pause(left, "waiting for the scheduled window")
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val ARTIFACT_PATH = "/artifacts"
        const val API_PATH = "/api/v4/"
        const val UNAUTHORIZED = """{"message":"401 Unauthorized"}"""
        const val SERVICE_UNAVAILABLE = """{"message":"503 Service Unavailable"}"""
        const val BAD_GATEWAY = """{"message":"502 Bad Gateway"}"""

        /** The token the proxy never uses (it strips credentials); only its presence matters. */
        const val REPLACEMENT_TOKEN = "dummy2"

        /**
         * Mid-shard cut. The master shards are ~800 KB compressed each (7 shards, ~5.5 MB in
         * total), so 300 KB is inside every one of them and still past the response headers.
         */
        const val CUT_AFTER_BYTES = 300_000L

        /** Per-response pace of artifact bodies; concurrency is 4, so the aggregate is four times this. */
        const val THROTTLE_BYTES_PER_SEC = 1_000_000

        /**
         * F2b needs the 401 to land while shards are still on the wire: 100 KB/s turns every
         * ~800 KB shard into an 8 s response.
         */
        const val MID_DOWNLOAD_THROTTLE_BYTES_PER_SEC = 100_000

        /** Shortened retry policy for the balloon scenarios. */
        const val RETRY_DELAYS = "500,1500,4500"

        /** F1c: a longer first delay gives the scenario time to swap the fault before the retry. */
        const val F1C_RETRY_DELAYS = "2000,4000,8000"

        const val EXPECTED_ERRORS = 4 // one attempt + three retries
        const val ERROR_BUDGET_MS = 360_000L
        /** A retry must start well within the 30 s poller interval; one ensure can still run for a minute. */
        const val RETRY_START_LIMIT_MS = 15_000L
        const val BALLOON_WAIT_MS = 60_000L
        const val POLLER_WINDOW_MS = 40_000L
        const val FIRST_ANSWER_TIMEOUT_MS = 240_000L
        const val MARKER_WAIT_MS = 20_000L
        const val BLOCK_WINDOW_MS = 90_000L
        const val HEAD_SWITCH_AT_MS = 10_000L
        const val ATTEMPT_WAIT_MS = 120_000L
        const val FAST_POLL_MS = 50L
        const val ARTIFACT_TIMEOUT_S = 300L

        /** The master pipeline publishes seven coverage shards. */
        const val SHARDS_EXPECTED = 7
        const val NO_RETRY_WINDOW_MS = 10_000L
        const val SWITCH_WINDOW_MS = 25_000L
        const val EVENT_MARGIN_MS = 1_000L
        const val LOAD_TIMEOUT_MS = 900_000L
        const val RECOVERY_TIMEOUT_MS = 600_000L
    }
}
