package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import java.util.ArrayDeque

/**
 * Application-level sink for this plugin's own log output.
 *
 * Every call into [CoverageLog] appends a [LogEntry] here in addition to
 * writing through to the IntelliJ [com.intellij.openapi.diagnostic.Logger].
 * The Coverage Tests tool window's Log tab subscribes to this service to
 * display plugin logs live inside the IDE, without users needing to open
 * `idea.log`.
 *
 * Stored as a bounded ring buffer ([MAX_ENTRIES]) so long sessions can't
 * grow memory. Thread-safe: [append] / [subscribe] / [clear] / [snapshot]
 * all synchronize on the buffer.
 */
@Service(Service.Level.APP)
class CoverageLogService {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class LogEntry(
        val timestampMs: Long,
        val level: Level,
        val loggerName: String,
        val message: String,
        val throwable: Throwable?,
    )

    fun interface Listener {
        fun onLogEntry(entry: LogEntry)
    }

    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val listeners = mutableListOf<Listener>()

    fun append(entry: LogEntry) {
        val snapshotListeners: List<Listener>
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            snapshotListeners = listeners.toList()
        }
        // Notify outside the lock so listener callbacks can't deadlock with
        // code that's already holding locks while calling append().
        for (listener in snapshotListeners) {
            try {
                listener.onLogEntry(entry)
            } catch (_: Throwable) {
                // Swallow: a bad listener must not break logging.
            }
        }
    }

    /**
     * Atomically snapshots the current buffer and registers [listener] so it
     * sees every [LogEntry] appended *after* the snapshot — no gaps, no dupes.
     * The listener is removed when [parent] is disposed.
     */
    fun subscribe(parent: Disposable, listener: Listener): List<LogEntry> {
        val backfill = addListener(listener)
        Disposer.register(parent) { removeListener(listener) }
        return backfill
    }

    /**
     * Disposable-free counterpart to [subscribe] — used by tests that don't
     * have an IntelliJ [Disposer] available. Returns the backfill atomically
     * with the listener registration so no entries are missed or duplicated.
     */
    fun addListener(listener: Listener): List<LogEntry> {
        synchronized(buffer) {
            val backfill = buffer.toList()
            listeners.add(listener)
            return backfill
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(buffer) { listeners.remove(listener) }
    }

    fun snapshot(): List<LogEntry> {
        synchronized(buffer) { return buffer.toList() }
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    companion object {
        const val MAX_ENTRIES = 2000
        fun getInstance(): CoverageLogService = service()
    }
}
