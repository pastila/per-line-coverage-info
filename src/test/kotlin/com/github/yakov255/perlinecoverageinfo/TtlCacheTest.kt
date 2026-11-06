package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TtlCacheTest {

    @Test
    fun `put and get round-trip`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000)
        cache.put("a", "1")
        assertEquals("1", cache.get("a"))
        assertNull(cache.get("missing"))
    }

    @Test
    fun `value expires after ttl`() {
        var now = 0L
        val cache = TtlCache<String, String>(ttlMs = 50, clockMs = { now })
        cache.put("a", "1")
        now = 49
        assertEquals("1", cache.get("a"))
        now = 50
        assertNull(cache.get("a"))
    }

    @Test
    fun `zero ttl disables caching`() {
        val cache = TtlCache<String, String>(ttlMs = 0)
        cache.put("a", "1")
        assertNull(cache.get("a"))
    }

    @Test
    fun `invalidate removes value`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000)
        cache.put("a", "1")
        cache.invalidate("a")
        assertNull(cache.get("a"))
    }

    @Test
    fun `clear removes all values`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `computeIfAbsent loads once and caches`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000)
        var loads = 0
        assertEquals("v", cache.computeIfAbsent("a") { loads++; "v" })
        assertEquals("v", cache.computeIfAbsent("a") { loads++; "v" })
        assertEquals(1, loads)
    }

    @Test
    fun `reading a new key releases expired values of unrelated keys`() {
        var now = 0L
        val cache = TtlCache<String, String>(ttlMs = 50, clockMs = { now })
        cache.put("old-a", "1")
        cache.put("old-b", "2")
        now = 50
        assertNull(cache.get("new-key"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `writing a new key releases expired values`() {
        var now = 0L
        val cache = TtlCache<String, String>(ttlMs = 50, clockMs = { now })
        cache.put("old-a", "1")
        cache.put("old-b", "2")
        now = 50
        cache.put("new", "3")
        assertEquals(1, cache.size)
        assertEquals("3", cache.get("new"))
    }

    @Test
    fun `capacity evicts oldest writes and reads do not extend lifetime`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000, maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        assertEquals("1", cache.get("a"))
        cache.put("c", "3")
        assertEquals(2, cache.size)
        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun `rewriting a key preserves expiry order and extends its ttl`() {
        var now = 0L
        val cache = TtlCache<String, String>(ttlMs = 50, clockMs = { now })
        cache.put("a", "old")
        now = 20
        cache.put("b", "b")
        now = 40
        cache.put("a", "new")
        now = 70
        assertEquals("new", cache.get("a"))
        assertEquals(1, cache.size)
        assertNull(cache.get("b"))
        now = 90
        assertNull(cache.get("a"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `rewriting at capacity does not evict another value`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000, maxEntries = 2)
        cache.put("a", "old")
        cache.put("b", "b")
        cache.put("a", "new")
        assertEquals(2, cache.size)
        assertEquals("b", cache.get("b"))
        cache.put("c", "c")
        assertEquals("new", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("c", cache.get("c"))
    }

    @Test
    fun `computeIfAbsent reloads an evicted value`() {
        val cache = TtlCache<String, Int>(ttlMs = 60_000, maxEntries = 1)
        var loads = 0
        assertEquals(1, cache.computeIfAbsent("a") { ++loads })
        assertEquals(2, cache.computeIfAbsent("b") { ++loads })
        assertEquals(3, cache.computeIfAbsent("a") { ++loads })
    }

    @Test
    fun `concurrent reads writes and invalidations respect capacity`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000, maxEntries = 32)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (1..4).map { worker ->
                pool.submit(Callable {
                    start.await()
                    repeat(1000) { index ->
                        val key = "$worker:$index"
                        cache.put(key, key)
                        cache.get(key)?.let { assertEquals(key, it) }
                        assertTrue(cache.size <= 32)
                        if (index % 3 == 0) cache.invalidate(key)
                        if (index % 100 == 0) cache.clear()
                    }
                })
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(cache.size <= 32)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive capacity is rejected`() {
        TtlCache<String, String>(ttlMs = 50, maxEntries = 0)
    }

}
