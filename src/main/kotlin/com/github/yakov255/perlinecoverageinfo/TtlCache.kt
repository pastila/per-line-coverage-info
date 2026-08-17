package com.github.yakov255.perlinecoverageinfo

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal thread-safe TTL cache.
 *
 * Values are stored with an expiry timestamp and evicted lazily on read.
 * A [ttlMs] <= 0 disables caching entirely (every access is a miss).
 */
class TtlCache<K, V>(private val ttlMs: Long) {

    private class Entry<V>(val value: V, val expiresAtMs: Long)

    private val map = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        if (ttlMs <= 0) return null
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAtMs) {
            map.remove(key, entry)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V) {
        if (ttlMs <= 0) return
        map[key] = Entry(value, System.currentTimeMillis() + ttlMs)
    }

    fun computeIfAbsent(key: K, loader: () -> V): V {
        get(key)?.let { return it }
        val value = loader()
        put(key, value)
        return value
    }

    fun invalidate(key: K) {
        map.remove(key)
    }

    fun clear() {
        map.clear()
    }

    val size: Int
        get() = map.size
}
