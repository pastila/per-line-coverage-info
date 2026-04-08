package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CoverageLogServiceTest {

    private fun entry(message: String, level: CoverageLogService.Level = CoverageLogService.Level.INFO) =
        CoverageLogService.LogEntry(
            timestampMs = 0L,
            level = level,
            loggerName = "TestLogger",
            message = message,
            throwable = null,
        )

    @Test
    fun appendStoresEntryAndSnapshotReturnsCopy() {
        val service = CoverageLogService()
        service.append(entry("first"))
        service.append(entry("second"))

        val snapshot = service.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("first", snapshot[0].message)
        assertEquals("second", snapshot[1].message)

        // snapshot is a defensive copy — appending after must not mutate it
        service.append(entry("third"))
        assertEquals(2, snapshot.size)
        assertNotSame(snapshot, service.snapshot())
    }

    @Test
    fun ringBufferEvictsOldestWhenCapExceeded() {
        val service = CoverageLogService()
        val total = CoverageLogService.MAX_ENTRIES + 50

        for (i in 0 until total) service.append(entry("msg-$i"))

        val snapshot = service.snapshot()
        assertEquals(CoverageLogService.MAX_ENTRIES, snapshot.size)
        // Oldest 50 evicted; first surviving entry is msg-50
        assertEquals("msg-50", snapshot.first().message)
        assertEquals("msg-${total - 1}", snapshot.last().message)
    }

    @Test
    fun addListenerBackfillsAndReceivesNewEntries() {
        val service = CoverageLogService()
        service.append(entry("before-1"))
        service.append(entry("before-2"))

        val received = mutableListOf<String>()
        val backfill = service.addListener { e -> received.add(e.message) }

        assertEquals(listOf("before-1", "before-2"), backfill.map { it.message })
        assertTrue("listener should not have fired during backfill", received.isEmpty())

        service.append(entry("after-1"))
        service.append(entry("after-2"))

        assertEquals(listOf("after-1", "after-2"), received)
    }

    @Test
    fun removeListenerStopsNotifications() {
        val service = CoverageLogService()
        val received = mutableListOf<String>()
        val listener = CoverageLogService.Listener { e -> received.add(e.message) }

        service.addListener(listener)
        service.append(entry("kept"))
        service.removeListener(listener)
        service.append(entry("dropped"))

        assertEquals(listOf("kept"), received)
    }

    @Test
    fun clearWipesBufferButLeavesListeners() {
        val service = CoverageLogService()
        val received = mutableListOf<String>()
        service.addListener { e -> received.add(e.message) }

        service.append(entry("a"))
        service.clear()
        assertTrue(service.snapshot().isEmpty())

        // listener still active after clear
        service.append(entry("b"))
        assertEquals(listOf("a", "b"), received)
    }

    @Test
    fun listenerExceptionDoesNotBreakAppend() {
        val service = CoverageLogService()
        service.addListener { throw RuntimeException("boom") }
        service.append(entry("survives"))

        assertEquals(1, service.snapshot().size)
        assertEquals("survives", service.snapshot().first().message)
    }

    @Test
    fun appendIsThreadSafe() {
        val service = CoverageLogService()
        val threads = 8
        val perThread = 200
        val latch = CountDownLatch(threads)

        repeat(threads) { t ->
            Thread {
                try {
                    repeat(perThread) { i -> service.append(entry("t$t-$i")) }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Total appends = threads * perThread = 1600, well under MAX_ENTRIES (2000),
        // so nothing should have been evicted.
        assertEquals(threads * perThread, service.snapshot().size)
    }
}
