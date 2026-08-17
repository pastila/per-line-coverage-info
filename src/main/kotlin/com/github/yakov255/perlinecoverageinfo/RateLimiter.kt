package com.github.yakov255.perlinecoverageinfo

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe token-bucket rate limiter.
 *
 * [capacity] is the maximum number of tokens (burst size); tokens are
 * replenished continuously at [tokensPerSecond]. [tryAcquire] is non-blocking,
 * [acquire] blocks until a token is available or [timeoutMs] elapses.
 */
class RateLimiter(
    private val capacity: Double,
    private val tokensPerSecond: Double,
) {

    private val lock = ReentrantLock()

    private var tokens = capacity
    private var lastRefillNanos = System.nanoTime()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(tokensPerSecond >= 0) { "tokensPerSecond must be non-negative" }
    }

    fun tryAcquire(): Boolean = lock.withLock {
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    fun acquire(timeoutMs: Long): Boolean {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            lock.withLock {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return true
                }
                if (System.nanoTime() >= deadlineNanos) return false
            }
            Thread.sleep(50)
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillNanos) / 1e9
        if (elapsedSec > 0) {
            tokens = minOf(capacity, tokens + elapsedSec * tokensPerSecond)
            lastRefillNanos = now
        }
    }
}
