package com.github.yakov255.perlinecoverageinfo

import java.lang.ref.Cleaner

/** Explicit shared ownership; short reads also hold a reference until their finally block. */
internal class SharedLifetime(private val dispose: () -> Unit) {
    private var references = 0
    private var closed = false

    fun acquire(): AutoCloseable {
        retain()
        return Owner(this)
    }

    fun <T> withLease(action: () -> T): T {
        retain()
        try {
            return action()
        } finally {
            release()
        }
    }

    @Synchronized
    private fun retain() {
        check(!closed) { "Coverage resource is closed" }
        references++
    }

    private fun release() {
        val finish = synchronized(this) {
            check(references > 0)
            references--
            (references == 0).also { if (it) closed = true }
        }
        if (finish) dispose()
    }

    private class Owner(lifetime: SharedLifetime) : AutoCloseable {
        // A fallback for abandoned exceptional paths, never the normal ownership mechanism.
        private val cleanup = cleaner.register(this, Runnable { lifetime.release() })
        override fun close() = cleanup.clean()
    }

    companion object {
        private val cleaner = Cleaner.create()
    }
}
