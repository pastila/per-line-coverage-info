package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GitLabCoordinatorTest {

    @Test
    fun `singleFlight coalesces concurrent identical work`() {
        val coordinator = GitLabCoordinator()
        val calls = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = (1..8).map {
                pool.submit(Callable {
                    start.await()
                    coordinator.singleFlight("key") {
                        calls.incrementAndGet()
                        Thread.sleep(50)
                        "result"
                    }
                })
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertTrue(results.all { it == "result" })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `singleFlight propagates exception to all waiters`() {
        val coordinator = GitLabCoordinator()
        val calls = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val futures = (1..4).map {
                pool.submit(Callable {
                    start.await()
                    try {
                        coordinator.singleFlight("boom") {
                            calls.incrementAndGet()
                            throw IllegalStateException("expected")
                        }
                        "ok"
                    } catch (e: IllegalStateException) {
                        "caught"
                    }
                })
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertTrue(results.all { it == "caught" })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `memoize caches within ttl and reloads after expiry`() {
        val coordinator = GitLabCoordinator()
        val calls = AtomicInteger(0)
        val loader = {
            calls.incrementAndGet()
            "v${calls.get()}"
        }

        assertEquals("v1", coordinator.memoize("name", "key", 200, loader))
        assertEquals("v1", coordinator.memoize("name", "key", 200, loader))
        assertEquals(1, calls.get())

        Thread.sleep(300)
        assertEquals("v2", coordinator.memoize("name", "key", 200, loader))
        assertEquals(2, calls.get())
    }

    @Test
    fun `refresh cooldown tracks per git root and head`() {
        val coordinator = GitLabCoordinator()
        val root = File("/repo")
        coordinator.markRefreshed(root, "abc")
        assertTrue(coordinator.isWithinRefreshCooldown(root, "abc", 60_000))
        assertFalse(coordinator.isWithinRefreshCooldown(root, "def", 60_000))
        assertFalse(coordinator.isWithinRefreshCooldown(File("/other"), "abc", 60_000))
    }

    @Test
    fun `sharedReader returns the same instance for the same key`() {
        val coordinator = GitLabCoordinator()
        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(mapOf("src/a.php" to mapOf(1 to listOf("t"))), tempFile)
            val opener = { Cov4Reader(tempFile) }
            val first = coordinator.sharedReader("c1", "raketa", opener)
            val second = coordinator.sharedReader("c1", "raketa", opener)
            assertSame(first, second)
        } finally {
            tempFile.delete()
        }
    }
}
