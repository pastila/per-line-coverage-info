package com.github.yakov255.perlinecoverageinfo

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-wide latch for the first-run GitLab token dialog.
 *
 * Several projects can start at the same moment on the first IDE run. The persisted
 * `tokenPrompted` flag is written only after the dialog closes, so it alone cannot stop a second
 * dialog: the first caller claims the prompt and every later one skips it.
 */
internal class TokenPromptGuard {

    private val claimed = AtomicBoolean(false)

    /** True for the first caller only. */
    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)

    /** Test seam: lets one test exercise the first prompt again. */
    fun resetForTest() {
        claimed.set(false)
    }

    companion object {
        private val instance = TokenPromptGuard()

        fun getInstance(): TokenPromptGuard = instance
    }
}
