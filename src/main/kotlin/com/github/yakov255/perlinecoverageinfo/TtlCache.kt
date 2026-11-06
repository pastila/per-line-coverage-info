package com.github.yakov255.perlinecoverageinfo

/**
 * Bounded thread-safe TTL cache.
 *
 * Reads and writes remove all expired entries, including keys never requested again.
 * Entries are ordered by write time; capacity eviction removes the oldest write.
 * Reading does not extend TTL. The clock must be monotonic.
 * A [ttlMs] <= 0 disables caching entirely (every access is a miss).
 */
class TtlCache<K, V>(
    private val ttlMs: Long,
    private val maxEntries: Int = 256,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private class Entry<V>(val value: V, val writtenAtMs: Long)

    private val map = LinkedHashMap<K, Entry<V>>()

    init {
        require(maxEntries > 0) { "Cache capacity must be positive" }
    }

    fun get(key: K): V? = synchronized(map) {
        if (ttlMs <= 0) return@synchronized null
        removeExpired(clockMs())
        map[key]?.value
    }

    fun put(key: K, value: V) = synchronized(map) {
        if (ttlMs <= 0) return@synchronized
        val now = clockMs()
        removeExpired(now)
        // Replacing a value must also move it to the end to preserve expiry order.
        map.remove(key)
        map[key] = Entry(value, now)
        if (map.size > maxEntries) {
            val iterator = map.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    fun computeIfAbsent(key: K, loader: () -> V): V {
        get(key)?.let { return it }
        val value = loader()
        put(key, value)
        return value
    }

    fun invalidate(key: K) {
        synchronized(map) {
            removeExpired(clockMs())
            map.remove(key)
        }
    }

    fun clear() {
        synchronized(map) { map.clear() }
    }

    /** Number of retained entries, including expired ones until the next read or write. */
    val size: Int
        get() = synchronized(map) { map.size }

    private fun removeExpired(now: Long) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.writtenAtMs < ttlMs) break
            iterator.remove()
        }
    }
}
