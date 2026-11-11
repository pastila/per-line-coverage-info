package com.github.yakov255.perlinecoverageinfo.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.steps.CommonSteps
import com.intellij.remoterobot.utils.Keyboard
import java.time.Duration

/**
 * Shared helpers for the UI tests: locating the IDE frame, invoking actions and polling
 * without fixed sleeps.
 *
 * [robot] is exposed so fixtures can search the whole IDE tree; the small wrappers below cover
 * the three interactions every scenario needs.
 */
class IdeRobot(val robot: RemoteRobot) {

    val keyboard = Keyboard(robot)
    val steps = CommonSteps(robot)

    /** Root window of the sandbox IDE. */
    fun ideFrame(): CommonContainerFixture =
        robot.find(CommonContainerFixture::class.java, IDE_FRAME, Duration.ofSeconds(30))

    /**
     * Runs an IDE action by id through the platform action system. This is far more stable than
     * emulating a platform-specific keyboard shortcut.
     */
    fun invokeAction(actionId: String) {
        steps.invokeAction(actionId)
    }

    /** Polls until [condition] returns a non-null value; fails with [message] after [timeout]. */
    fun <T : Any> await(message: String, timeout: Duration, poll: Duration = Duration.ofSeconds(1), condition: () -> T?): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                condition()?.let { return it }
            } catch (e: Throwable) {
                lastFailure = e
            }
            Thread.sleep(poll.toMillis())
        }
        throw AssertionError("$message (last error: ${lastFailure?.message})", lastFailure)
    }

    companion object {
        const val SETTINGS_ACTION = "ShowSettings"

        @JvmField
        val IDE_FRAME = com.intellij.remoterobot.search.locators.byXpath("//div[@class='IdeFrameImpl']")
    }
}

/**
 * Snapshot written by `CoverageUiTestBridge` inside the IDE process.
 *
 * RemoteRobot sees Swing only, so assertions about real coverage state (commit, loaded files,
 * per-line classification) read this file instead of parsing UI text.
 */
data class CoverageSnapshot(
    val state: String,
    val commit: String?,
    val baselineCommit: String?,
    val files: Int,
    val artifacts: Int,
    val error: String?,
    val lines: Map<String, Map<Int, String>>,
    /** Shown from a cached artifact (offline-first) and not yet confirmed by the daemon. */
    val stale: Boolean = false,
    val gutterVisible: Boolean = true,
    val headHash: String? = null,
    /** Epoch millis of the moment the plugin wrote this snapshot. */
    val updatedAt: Long = 0,
) {
    val isLoaded: Boolean get() = state == "LOADED"
}
