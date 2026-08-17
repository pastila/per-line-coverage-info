package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        val cache = TtlCache<String, String>(ttlMs = 50)
        cache.put("a", "1")
        assertEquals("1", cache.get("a"))
        Thread.sleep(120)
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
}
