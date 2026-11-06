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
import java.util.concurrent.ConcurrentLinkedQueue
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
        val releaseLoader = CountDownLatch(1)
        val callersReady = CountDownLatch(4)
        val callerThreads = ConcurrentLinkedQueue<Thread>()
        try {
            val futures = (1..4).map {
                pool.submit(Callable {
                    callerThreads.add(Thread.currentThread())
                    callersReady.countDown()
                    try {
                        coordinator.singleFlight("boom") {
                            calls.incrementAndGet()
                            check(releaseLoader.await(10, TimeUnit.SECONDS))
                            throw IllegalStateException("expected")
                        }
                        "ok"
                    } catch (e: IllegalStateException) {
                        "caught"
                    }
                })
            }
            assertTrue(callersReady.await(5, TimeUnit.SECONDS))
            // Keep the failing loader alive until the other callers actually join it.
            // An immediate throw allows later callers to legitimately start new work.
            fun allCallersWaiting() = callerThreads.all {
                it.state == Thread.State.WAITING || it.state == Thread.State.TIMED_WAITING
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!allCallersWaiting() && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("All callers should be waiting on the in-flight work", allCallersWaiting())
            releaseLoader.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertTrue(results.all { it == "caught" })
        } finally {
            releaseLoader.countDown()
            pool.shutdownNow()
            coordinator.dispose()
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
        assertTrue(coordinator.isWithinRefreshCooldown(root, "abc"))
        assertFalse(coordinator.isWithinRefreshCooldown(root, "def"))
        assertFalse(coordinator.isWithinRefreshCooldown(File("/other"), "abc"))
    }

    @Test
    fun `refresh cooldown does not retain every visited head`() {
        val coordinator = GitLabCoordinator()
        val root = File("/repo")
        try {
            coordinator.markRefreshed(root, "old")
            repeat(300) { coordinator.markRefreshed(root, "head-$it") }
            assertFalse(coordinator.isWithinRefreshCooldown(root, "old"))
            assertTrue(coordinator.isWithinRefreshCooldown(root, "head-299"))
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun `disposing coordinator clears refresh cooldowns`() {
        val coordinator = GitLabCoordinator()
        val root = File("/repo")
        coordinator.markRefreshed(root, "abc")
        coordinator.dispose()
        assertFalse(coordinator.isWithinRefreshCooldown(root, "abc"))
    }

    @Test
    fun `sharedReader shares data with independent ownership`() {
        val coordinator = GitLabCoordinator()
        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(mapOf("src/a.php" to mapOf(1 to listOf("t"))), tempFile)
            val opener = { Cov4Reader(tempFile) }
            val first = coordinator.sharedReader("c1", "raketa", opener)
            val second = coordinator.sharedReader("c1", "raketa", opener)
            assertSame(first!!.allFilePaths, second!!.allFilePaths)
            first.close()
            coordinator.clearReaders()
            assertEquals(mapOf(1 to listOf("t")), second.getCoverage("src/a.php"))
            second.close()
            try {
                second.getCoverage("src/a.php")
                org.junit.Assert.fail("Last owner should close the snapshot")
            } catch (_: IllegalStateException) { }
            coordinator.dispose()
        } finally {
            tempFile.delete()
        }
    }
}
