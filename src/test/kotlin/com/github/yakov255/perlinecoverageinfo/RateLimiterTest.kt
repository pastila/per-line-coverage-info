package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `burst capacity is honoured`() {
        val limiter = RateLimiter(capacity = 3.0, tokensPerSecond = 0.0)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `tokens refill over time`() {
        val limiter = RateLimiter(capacity = 1.0, tokensPerSecond = 2.0)
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        Thread.sleep(600)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `acquire blocks until a token is available`() {
        val limiter = RateLimiter(capacity = 1.0, tokensPerSecond = 1.0)
        assertTrue(limiter.acquire(100))
        val started = System.currentTimeMillis()
        // Second token only arrives after ~1s of refill.
        assertTrue(limiter.acquire(3_000))
        val elapsed = System.currentTimeMillis() - started
        assertTrue("expected to wait ~1s, took $elapsed ms", elapsed >= 800)
    }

    @Test
    fun `acquire returns false on timeout without refill`() {
        val limiter = RateLimiter(capacity = 1.0, tokensPerSecond = 0.0)
        assertTrue(limiter.acquire(100))
        assertFalse(limiter.acquire(100))
    }
}
