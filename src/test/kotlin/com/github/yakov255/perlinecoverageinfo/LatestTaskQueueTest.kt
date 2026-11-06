package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestTaskQueueTest {
    @Test
    fun `only newest pending snapshot runs and finished results are invalidated`() {
        val tasks = ArrayList<Runnable>()
        val seen = ArrayList<Int>()
        val tickets = ArrayList<LatestTaskQueue.Ticket>()
        val queue = LatestTaskQueue<Int>(Executor { tasks.add(it) }, { throw AssertionError(it) }) { value, ticket ->
            seen.add(value)
            tickets.add(ticket)
        }
        queue.submit(1)
        queue.submit(2)
        queue.submit(3)
        assertEquals(1, tasks.size)
        tasks.removeAt(0).run()
        assertEquals(listOf(3), seen)
        queue.submit(4)
        assertTrue(tickets.single().cancelled)
        queue.close()
        tasks.removeAt(0).run()
        assertEquals(listOf(3), seen)
    }

    @Test
    fun `replacement cancels running work without interrupting its thread`() {
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val queue = LatestTaskQueue<Int>(pool, errors::add) { value, ticket ->
            seen.add(value)
            if (value == 1) {
                ticket.onCancel { cancelled.countDown() }
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertFalse(Thread.currentThread().isInterrupted)
                ticket.checkCancelled()
            } else finished.countDown()
        }
        try {
            queue.submit(1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.submit(2)
            queue.submit(3)
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), seen)
            assertTrue(errors.isEmpty())
        } finally {
            release.countDown()
            queue.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `cancelling pending work releases it and queue accepts later requests`() {
        val tasks = ArrayList<Runnable>()
        val seen = ArrayList<Int>()
        val queue = LatestTaskQueue<Int>(Executor { tasks.add(it) }, { throw AssertionError(it) }) { value, _ -> seen.add(value) }
        queue.submit(1)
        queue.cancel()
        queue.submit(2)
        assertEquals(1, tasks.size)
        tasks.single().run()
        assertEquals(listOf(2), seen)
        queue.close()
        queue.submit(3)
        assertEquals(listOf(2), seen)
    }
}
