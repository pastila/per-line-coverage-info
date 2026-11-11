package com.github.yakov255.perlinecoverageinfo

/**
 * Backoff for repeated `not_ready` answers from the daemon.
 *
 * `not_ready` means the daemon is waiting for a CI job: it is not an error, so the plugin keeps
 * asking until the coverage appears. Asking every second would hold the daemon busy (each call
 * resolves pipelines and lists jobs), so the wait grows per answer for the same reason:
 * 5 s, 10 s, 20 s, 40 s, then the 60 s ceiling. A daemon `retryAfterMs` is a floor.
 *
 * The counter belongs to one reason: a new reason (another pipeline state) starts the ladder
 * again, and [reset] clears it on `ready`, a HEAD change, a settings change or a manual load.
 * Pure state, no threads and no IDE types, so the ladder is unit-testable directly.
 */
internal class NotReadyBackoff(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {

    private var reason: String? = null
    private var step: Int = 0

    /** The wait before the next ask, never below [requestedMs] and never above the ceiling. */
    @Synchronized
    fun nextDelay(reason: String?, requestedMs: Long): Long {
        val key = reason.orEmpty()
        if (key != this.reason) {
            this.reason = key
            step = 0
        }
        val grown = if (step >= LADDER_STEPS) maxDelayMs else baseDelayMs shl step
        val delay = maxOf(grown, requestedMs).coerceAtMost(maxDelayMs)
        step++
        return delay
    }

    /** Success, a new HEAD, new settings or a manual load: the next `not_ready` starts at the base. */
    @Synchronized
    fun reset() {
        reason = null
        step = 0
    }

    /** Answers already scheduled for the current reason; 0 after [reset] or a reason change. */
    @Synchronized
    internal fun currentStep(): Int = step

    internal companion object {
        const val DEFAULT_BASE_DELAY_MS: Long = 5_000
        const val DEFAULT_MAX_DELAY_MS: Long = 60_000

        /** 5, 10, 20, 40: after four steps the 80 s value is clamped by the ceiling. */
        private const val LADDER_STEPS = 4
    }
}
