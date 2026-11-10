package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests of the failure contract: the retry ladder, the delay ceilings and the mapping from
 * daemon codes to retry / block / stop.
 */
class RetryPolicyTest {

    private val policy = RetryPolicy(RetryPolicy.DEFAULT_DELAYS_MS)

    // --- retry ladder ---------------------------------------------------------------

    @Test
    fun `default ladder is three retries after five fifteen and forty-five seconds`() {
        assertEquals(listOf(5_000L, 15_000L, 45_000L), RetryPolicy.DEFAULT_DELAYS_MS)
        assertEquals(3, policy.maxAttempts)
        assertEquals(5_000L, policy.delayFor(0, 0))
        assertEquals(15_000L, policy.delayFor(1, 0))
        assertEquals(45_000L, policy.delayFor(2, 0))
    }

    @Test
    fun `retryAfterMs is a floor and a single wait never exceeds sixty seconds`() {
        assertEquals(12_000L, policy.delayFor(0, 12_000))
        assertEquals(60_000L, policy.delayFor(0, 90_000))
        assertEquals(60_000L, RetryPolicy(listOf(120_000L)).delayFor(0, 0))
    }

    @Test
    fun `a custom ladder replaces the default and defines the number of attempts`() {
        val custom = RetryPolicy(RetryPolicy.parseDelays("500,1500,4500"))
        assertEquals(listOf(500L, 1_500L, 4_500L), RetryPolicy.parseDelays("500,1500,4500"))
        assertEquals(3, custom.maxAttempts)
        assertEquals(500L, custom.delayFor(0, 0))
        assertEquals(4_500L, custom.delayFor(2, 0))
    }

    @Test
    fun `a blank or unusable delays property keeps the default ladder`() {
        assertEquals(RetryPolicy.DEFAULT_DELAYS_MS, RetryPolicy.parseDelays(null))
        assertEquals(RetryPolicy.DEFAULT_DELAYS_MS, RetryPolicy.parseDelays("  "))
        assertEquals(RetryPolicy.DEFAULT_DELAYS_MS, RetryPolicy.parseDelays("abc,-1,0"))
        assertEquals(listOf(250L, 500L), RetryPolicy.parseDelays(" 250 , 500 "))
    }

    @Test
    fun `the delays system property replaces the ladder at runtime`() {
        val previous = System.getProperty(RetryPolicy.DELAYS_PROPERTY)
        try {
            System.setProperty(RetryPolicy.DELAYS_PROPERTY, "250,500")
            val dynamic = RetryPolicy()
            assertEquals(2, dynamic.maxAttempts)
            assertEquals(250L, dynamic.delayFor(0, 0))
            assertEquals(500L, dynamic.delayFor(1, 0))
        } finally {
            if (previous == null) {
                System.clearProperty(RetryPolicy.DELAYS_PROPERTY)
            } else {
                System.setProperty(RetryPolicy.DELAYS_PROPERTY, previous)
            }
        }
    }

    // --- decisions ------------------------------------------------------------------

    @Test
    fun `retryable errors are retried until the budget is spent`() {
        for (attempt in 0 until policy.maxAttempts) {
            val decision = policy.decide("error", "CONNECTION", retryable = true, retryAfterMs = 0, attempt = attempt)
            assertEquals(RetryPolicy.Verdict.RETRY, decision.verdict)
            assertNull(decision.blocked)
            assertTrue(decision.delayMs > 0)
        }

        val exhausted = policy.decide(
            "error", "GITLAB_API", retryable = true, retryAfterMs = 0, attempt = policy.maxAttempts,
        )
        assertEquals(RetryPolicy.Verdict.NONE, exhausted.verdict)
        assertEquals(0L, exhausted.delayMs)
    }

    @Test
    fun `auth errors block without any retry`() {
        for (code in listOf("AUTH_INVALID", "ACCESS_DENIED")) {
            val decision = policy.decide("error", code, retryable = false, retryAfterMs = 0, attempt = 0)
            assertEquals(RetryPolicy.Verdict.BLOCK_AUTH, decision.verdict)
            assertEquals("AUTH", decision.blocked)
            assertEquals(0L, decision.delayMs)
        }
    }

    @Test
    fun `deterministic errors stop automatic loads until head or settings change`() {
        for (code in listOf("NO_COMPONENT_JOBS", "NO_DATA", "PROJECT_SETUP", "GIT")) {
            val decision = policy.decide("error", code, retryable = false, retryAfterMs = 0, attempt = 0)
            assertEquals(code, RetryPolicy.Verdict.STOP_UNTIL_CHANGE, decision.verdict)
            assertEquals("NON_RETRYABLE:$code", decision.blocked)
            assertEquals(0L, decision.delayMs)
        }
    }

    @Test
    fun `unknown non-retryable errors neither retry nor block`() {
        val decision = policy.decide("error", "INTERNAL", retryable = false, retryAfterMs = 0, attempt = 0)
        assertEquals(RetryPolicy.Verdict.NONE, decision.verdict)
        assertNull(decision.blocked)
    }

    @Test
    fun `ready and not_ready answers are not failures`() {
        for (state in listOf("ready", "not_ready")) {
            val decision = policy.decide(state, null, retryable = false, retryAfterMs = 0, attempt = 1)
            assertEquals(RetryPolicy.Verdict.NONE, decision.verdict)
        }
    }

    @Test
    fun `retryAfterMs raises the wait of a retry decision`() {
        val decision = policy.decide("error", "CONNECTION", retryable = true, retryAfterMs = 20_000, attempt = 0)
        assertEquals(RetryPolicy.Verdict.RETRY, decision.verdict)
        assertEquals(20_000L, decision.delayMs)
    }

    // --- retry state (counter and block lifetimes) -----------------------------------

    @Test
    fun `a block belongs to the head and settings it was created for`() {
        val state = RetryState()
        state.block("AUTH", head = "aaa", settings = "s1")

        assertTrue(state.isBlockedFor("aaa", "s1"))
        assertFalse("another HEAD lifts the block", state.isBlockedFor("bbb", "s1"))
        assertFalse("changed settings lift the block", state.isBlockedFor("aaa", "s2"))
        assertEquals("AUTH", state.blocked)
    }

    @Test
    fun `success and an explicit reset clear the counter and the block`() {
        val state = RetryState()
        state.onRetryScheduled()
        state.onRetryScheduled()
        assertEquals(2, state.attempt)
        state.block("NON_RETRYABLE:NO_DATA", head = "aaa", settings = "s1")

        state.reset()

        assertEquals(0, state.attempt)
        assertNull(state.blocked)
        assertNull(state.blockedForHead)
        assertNull(state.blockedForSettings)
        assertFalse(state.isBlockedFor("aaa", "s1"))
    }

    @Test
    fun `blocking restarts the retry budget`() {
        val state = RetryState()
        state.onRetryScheduled()

        state.block("AUTH", head = "aaa", settings = "s1")

        assertEquals(0, state.attempt)
        assertEquals("AUTH", state.blocked)
    }
}
