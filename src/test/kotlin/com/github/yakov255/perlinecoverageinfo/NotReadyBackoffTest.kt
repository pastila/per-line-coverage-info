package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Backoff ladder for repeated `not_ready` answers (review item M5).
 *
 * The daemon answers `not_ready` while a CI job runs. The plugin must keep asking, but not every
 * second: the wait grows per answer for one reason and starts over when the reason changes or
 * when a load succeeds.
 */
class NotReadyBackoffTest {

    @Test
    fun `the wait grows per answer for the same reason up to the ceiling`() {
        val backoff = NotReadyBackoff()

        assertEquals(5_000, backoff.nextDelay("jobs_running", 0))
        assertEquals(10_000, backoff.nextDelay("jobs_running", 0))
        assertEquals(20_000, backoff.nextDelay("jobs_running", 0))
        assertEquals(40_000, backoff.nextDelay("jobs_running", 0))
        assertEquals(60_000, backoff.nextDelay("jobs_running", 0))
        assertEquals(60_000, backoff.nextDelay("jobs_running", 0))
    }

    @Test
    fun `a daemon retry hint is a floor below the ceiling`() {
        val backoff = NotReadyBackoff()

        assertEquals(30_000, backoff.nextDelay("jobs_running", 30_000))
        assertEquals(10_000, backoff.nextDelay("jobs_running", 0))
        assertEquals(60_000, backoff.nextDelay("jobs_running", 600_000))
    }

    @Test
    fun `another reason starts the ladder again`() {
        val backoff = NotReadyBackoff()
        assertEquals(5_000, backoff.nextDelay("jobs_running", 0))
        assertEquals(10_000, backoff.nextDelay("jobs_running", 0))

        assertEquals(5_000, backoff.nextDelay("no_pipeline_yet", 0))
        assertEquals(10_000, backoff.nextDelay("no_pipeline_yet", 0))
        // The old reason is gone: coming back to it starts at the base too.
        assertEquals(5_000, backoff.nextDelay("jobs_running", 0))
    }

    @Test
    fun `reset after ready, a HEAD change or a settings change starts at the base`() {
        val backoff = NotReadyBackoff()
        repeat(3) { backoff.nextDelay("jobs_running", 0) }
        assertEquals(3, backoff.currentStep())

        backoff.reset()

        assertEquals(0, backoff.currentStep())
        assertEquals(5_000, backoff.nextDelay("jobs_running", 0))
    }

    @Test
    fun `a missing reason is treated as its own reason and does not throw`() {
        val backoff = NotReadyBackoff()

        assertEquals(5_000, backoff.nextDelay(null, 0))
        assertEquals(10_000, backoff.nextDelay(null, 0))
        assertEquals(5_000, backoff.nextDelay("jobs_running", 0))
    }
}
