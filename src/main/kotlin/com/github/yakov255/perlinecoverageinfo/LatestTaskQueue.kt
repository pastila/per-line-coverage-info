package com.github.yakov255.perlinecoverageinfo

import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/** One running task and one replaceable pending value; cancellation never interrupts shared I/O. */
internal class LatestTaskQueue<T>(
    private val executor: Executor,
    private val onError: (Throwable) -> Unit,
    private val work: (T, Ticket) -> Unit,
) : AutoCloseable {
    class Ticket {
        @Volatile var cancelled = false
            private set
        private var listener: (() -> Unit)? = null

        @Synchronized fun onCancel(action: () -> Unit) {
            if (cancelled) action() else listener = action
        }
        @Synchronized fun cancel() {
            cancelled = true
            listener?.invoke()
            listener = null
        }
        @Synchronized fun clearListener() { listener = null }
        fun checkCancelled() { if (cancelled) throw CancellationException() }
    }

    private data class Request<T>(val value: T, val ticket: Ticket)
    private val lock = Any()
    private var pending: Request<T>? = null
    private var latest: Ticket? = null
    private var running = false
    private var closed = false

    fun submit(value: T) {
        val start = synchronized(lock) {
            if (closed) return
            latest?.cancel()
            val ticket = Ticket()
            latest = ticket
            pending = Request(value, ticket)
            if (running) false else { running = true; true }
        }
        if (start) {
            try {
                executor.execute(::drain)
            } catch (e: RuntimeException) {
                synchronized(lock) { running = false; pending = null; latest?.cancel() }
                onError(e)
            }
        }
    }

    fun cancel() = synchronized(lock) {
        latest?.cancel()
        pending = null
    }

    override fun close() = synchronized(lock) {
        closed = true
        latest?.cancel()
        pending = null
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                pending?.also { pending = null } ?: run { running = false; return }
            }
            try {
                request.ticket.checkCancelled()
                work(request.value, request.ticket)
            } catch (_: CancellationException) {
                // A replacement or disposal invalidated this request.
            } catch (e: Throwable) {
                onError(e)
            } finally {
                request.ticket.clearListener()
            }
        }
    }
}
