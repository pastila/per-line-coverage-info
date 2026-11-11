package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCacheTest {

    private class FakeStore(private var value: String = "") : TokenStore {
        var reads = 0
        val writes = mutableListOf<String>()

        override fun read(): String {
            reads++
            return value
        }

        override fun write(token: String) {
            writes += token
            value = token
        }
    }

    private class Queue {
        val tasks = ArrayDeque<() -> Unit>()

        fun submit(task: () -> Unit) {
            tasks.addLast(task)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst()()
        }
    }

    @Test
    fun `without an application the store is used directly`() {
        val store = FakeStore("stored")
        val cache = TokenCache(store, background = null, onEdt = { false })

        assertEquals("stored", cache.get())
        cache.set("new")
        assertEquals("new", store.read())
        assertEquals(listOf("new"), store.writes)
    }

    @Test
    fun `a miss off the edt loads the token synchronously`() {
        val store = FakeStore("stored")
        val queue = Queue()
        val cache = TokenCache(store, queue::submit, onEdt = { false })

        assertEquals("stored", cache.get())
        assertEquals(1, store.reads)
        assertEquals(0, queue.tasks.size)
        assertEquals("stored", cache.get())
        assertEquals(1, store.reads)
    }

    @Test
    fun `a miss on the edt returns at once and loads in the background`() {
        val store = FakeStore("stored")
        val queue = Queue()
        val cache = TokenCache(store, queue::submit, onEdt = { true })

        assertEquals("", cache.get())
        assertEquals("the keychain must not be touched on the EDT", 0, store.reads)
        assertEquals(1, queue.tasks.size)

        queue.runAll()
        assertEquals(1, store.reads)
        assertEquals("stored", cache.get())
        assertEquals(1, store.reads)
    }

    @Test
    fun `a set is visible at once, before the store is written`() {
        val store = FakeStore("stored")
        val queue = Queue()
        val cache = TokenCache(store, queue::submit, onEdt = { true })

        cache.set("typed")

        assertEquals("typed", cache.get())
        assertTrue(cache.flush())
        assertEquals("typed", store.read())
        assertEquals(listOf("typed"), store.writes)
    }

    @Test
    fun `a burst of writes ends with the last value in the store`() {
        val store = FakeStore()
        val queue = Queue()
        val cache = TokenCache(store, queue::submit, onEdt = { false })

        cache.set("one")
        cache.set("two")
        cache.set("three")

        assertTrue(cache.flush())
        assertEquals("three", store.read())
        assertEquals(listOf("three"), store.writes)
        assertEquals("three", cache.get())
    }

    @Test
    fun `an empty set removes the token from the cache and the store`() {
        val store = FakeStore("stored")
        val queue = Queue()
        val cache = TokenCache(store, queue::submit, onEdt = { false })

        cache.set("")
        assertTrue(cache.flush())

        assertEquals("", cache.get())
        assertEquals(listOf(""), store.writes)
    }

    @Test
    fun `flush waits for the writer of a real background thread`() {
        val store = FakeStore()
        val cache = TokenCache(store, { task -> Thread(task).start() }, onEdt = { false })

        cache.set("async")
        assertTrue(cache.flush())
        assertEquals(listOf("async"), store.writes)
    }
}
