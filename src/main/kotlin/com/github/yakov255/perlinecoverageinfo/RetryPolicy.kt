package com.github.yakov255.perlinecoverageinfo

/**
 * Pure failure-handling policy for daemon answers.
 *
 * It decides what to do with one `ensure` answer: retry it after a delay, block until the
 * token is updated, block until HEAD or the settings change, or do nothing. It owns no
 * threads and knows nothing about the IDE, so the retry ladder, the ceilings and the code
 * classification are unit-testable without a project.
 *
 * The default ladder is 5 s / 15 s / 45 s and can be replaced by the test/diagnostics
 * property `-Dcoverage.retry.delaysMs=500,1500,4500`. A daemon-provided `retryAfterMs` is
 * a floor, while a single wait never exceeds [MAX_DELAY_MS].
 */
internal class RetryPolicy(private val fixedDelaysMs: List<Long>? = null) {

    private fun delaysMs(): List<Long> =
        fixedDelaysMs ?: parseDelays(System.getProperty(DELAYS_PROPERTY))

    /** Number of automatic retries before the user is notified. */
    val maxAttempts: Int get() = delaysMs().size

    /** Wait before automatic retry number [attempt] (0-based), never below `retryAfterMs`. */
    fun delayFor(attempt: Int, retryAfterMs: Long): Long {
        val delays = delaysMs()
        val base = delays.getOrElse(attempt) { delays.lastOrNull() ?: DEFAULT_DELAYS_MS.last() }
        return maxOf(base, retryAfterMs.coerceAtLeast(0)).coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * Classifies one daemon answer.
     *
     * @param attempt retries already scheduled for the current HEAD/settings.
     */
    fun decide(state: String?, code: String?, retryable: Boolean, retryAfterMs: Long, attempt: Int): Decision {
        if (state == "ready" || state == "not_ready") return Decision(Verdict.NONE)

        if (code in AUTH_CODES) {
            return Decision(Verdict.BLOCK_AUTH, blocked = AUTH_BLOCK)
        }
        if (code in NON_RETRYABLE_CODES) {
            return Decision(Verdict.STOP_UNTIL_CHANGE, blocked = NON_RETRYABLE_PREFIX + code)
        }
        if (retryable) {
            return if (attempt < maxAttempts) {
                Decision(Verdict.RETRY, delayMs = delayFor(attempt, retryAfterMs))
            } else {
                // Budget exhausted: the caller shows the balloon and waits for the user.
                Decision(Verdict.NONE)
            }
        }
        return Decision(Verdict.NONE)
    }

    enum class Verdict { RETRY, BLOCK_AUTH, STOP_UNTIL_CHANGE, NONE }

    data class Decision(val verdict: Verdict, val delayMs: Long = 0, val blocked: String? = null)

    companion object {
        val DEFAULT_DELAYS_MS: List<Long> = listOf(5_000L, 15_000L, 45_000L)

        /** A single wait is capped here even when the daemon asks for more. */
        const val MAX_DELAY_MS: Long = 60_000L

        const val AUTH_BLOCK: String = "AUTH"
        const val NON_RETRYABLE_PREFIX: String = "NON_RETRYABLE:"

        val AUTH_CODES: Set<String> = setOf("AUTH_INVALID", "ACCESS_DENIED")
        val NON_RETRYABLE_CODES: Set<String> = setOf("NO_COMPONENT_JOBS", "NO_DATA", "PROJECT_SETUP", "GIT")

        const val DELAYS_PROPERTY: String = "coverage.retry.delaysMs"

        /** Reads the ladder from a property value; blank or unusable values keep the default. */
        fun parseDelays(raw: String?): List<Long> {
            if (raw.isNullOrBlank()) return DEFAULT_DELAYS_MS
            val parsed = raw.split(',')
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it > 0 }
            return parsed.ifEmpty { DEFAULT_DELAYS_MS }
        }
    }
}

/**
 * Mutable retry counter and block marker for one project.
 *
 * A block belongs to the HEAD and settings it was created for: a new HEAD or a changed
 * settings fingerprint lifts it. Kept separate from [RetryPolicy] so the counter and the
 * block transitions are testable without an IDE.
 */
internal class RetryState {

    @Volatile
    var attempt: Int = 0
        private set

    @Volatile
    var blocked: String? = null
        private set

    @Volatile
    var blockedForHead: String? = null
        private set

    @Volatile
    var blockedForSettings: String? = null
        private set

    @Synchronized
    fun onRetryScheduled() {
        attempt += 1
    }

    /** Counter and block fall together on success, a HEAD change and a settings change. */
    @Synchronized
    fun reset() {
        attempt = 0
        clearBlock()
    }

    @Synchronized
    fun clearBlock() {
        blocked = null
        blockedForHead = null
        blockedForSettings = null
    }

    @Synchronized
    fun block(reason: String, head: String?, settings: String) {
        attempt = 0
        blocked = reason
        blockedForHead = head
        blockedForSettings = settings
    }

    /** True while [reason] was created for exactly this HEAD and settings fingerprint. */
    fun isBlockedFor(head: String?, settings: String): Boolean =
        blocked != null && blockedForHead == head && blockedForSettings == settings
}
