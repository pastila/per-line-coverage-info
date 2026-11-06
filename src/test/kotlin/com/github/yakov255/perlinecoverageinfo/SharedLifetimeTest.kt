package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SharedLifetimeTest {
    @Test
    fun `cancelled decoding does not interrupt other owners of the same snapshot`() {
        val file = Files.createTempFile("cancel-read", ".cov4").toFile()
        Cov4Writer.write(mapOf("a.php" to mapOf(1 to listOf("t"))), file)
        val first = Cov4Reader(file)
        val second = first.retain()
        try {
            // Plain unit tests have no IDE Application/ProgressManager service.
            val indicator = object : com.intellij.openapi.progress.ProgressIndicator by com.intellij.openapi.progress.DumbProgressIndicator.INSTANCE {
                override fun checkCanceled() { throw com.intellij.openapi.progress.ProcessCanceledException() }
                override fun isCanceled() = true
            }
            try {
                CoverageCancellation.withIndicator(indicator) { first.getCoverage("a.php") }
                fail("Cancelled read must stop")
            } catch (_: com.intellij.openapi.progress.ProcessCanceledException) { }
            first.close()
            assertEquals(mapOf(1 to listOf("t")), second.getCoverage("a.php"))
        } finally { first.close(); second.close(); file.delete() }
    }

    @Test
    fun `each owner closes once and last owner disposes immediately`() {
        val closes = AtomicInteger()
        val lifetime = SharedLifetime { closes.incrementAndGet() }
        val first = lifetime.acquire()
        val second = lifetime.acquire()
        first.close()
        first.close()
        assertEquals(0, closes.get())
        second.close()
        assertEquals(1, closes.get())
        try { lifetime.acquire(); fail("Closed resource cannot be acquired") } catch (_: IllegalStateException) { }
    }

    @Test
    fun `failed reads release their temporary ownership`() {
        val closes = AtomicInteger()
        val lifetime = SharedLifetime { closes.incrementAndGet() }
        val owner = lifetime.acquire()
        try { lifetime.withLease { error("failed read") } } catch (_: IllegalStateException) { }
        owner.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun `snapshot remains readable during a close and closes after read completes`() {
        val file = Files.createTempFile("snapshot-read", ".bin").toFile()
        file.writeBytes(ByteArray(128 * 1024) { 7 })
        val snapshot = Cov4Snapshot(file)
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val read = pool.submit<Int> {
                snapshot.readSection(0, snapshot.size) { input ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    input.skipNBytes(100_000)
                    input.readUnsignedByte()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            snapshot.close()
            release.countDown()
            assertEquals(7, read.get(5, TimeUnit.SECONDS))
            try { snapshot.read(0, 1); fail("Completed read must release the closed snapshot") } catch (_: IllegalStateException) { }
        } finally {
            release.countDown()
            snapshot.close()
            pool.shutdownNow()
            file.delete()
        }
    }
}
