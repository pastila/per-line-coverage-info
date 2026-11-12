package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * App-level helpers shared by all project windows.
 *
 * Coverage work happens in the covd daemon, so the only cross-window state left here is the
 * shared [Cov4Reader] cache: a large `.cov4` is indexed once per commit even when several
 * windows are open. Each caller owns a retained handle; immutable snapshots survive replacement and deletion.
 */
@Service(Service.Level.APP)
class GitLabCoordinator : Disposable {

    private val log = CoverageLog.get(GitLabCoordinator::class.java)
    private val readerCache = ReaderLruCache(MAX_READER_CACHE_ENTRIES, log)

    fun sharedReader(
        commitHash: String,
        component: String,
        open: () -> Cov4Reader?,
        variant: String = "",
    ): Cov4Reader? =
        readerCache.getOrOpen("$commitHash|$component|$variant", open)

    /** Drops cached readers for a rewritten .cov4 file so the next lookup reopens it. */
    fun evictSharedReaders(commitHash: String, component: String) {
        readerCache.removeAll("$commitHash|$component|")
    }

    override fun dispose() {
        readerCache.clear()
    }

    companion object {
        private const val MAX_READER_CACHE_ENTRIES = 4

        fun getInstance(): GitLabCoordinator =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(GitLabCoordinator::class.java)
    }
}

private class ReaderLruCache(private val maxEntries: Int, private val log: CoverageLog) {

    private val map = LinkedHashMap<String, Cov4Reader>(16, 0.75f, true)
    private val lock = Any()

    fun getOrOpen(key: String, open: () -> Cov4Reader?): Cov4Reader? {
        synchronized(lock) {
            map[key]?.let {
                log.debug("GitLab coordinator: reusing cached reader ($key)")
                return it.retain()
            }
        }
        val reader = open() ?: return null
        synchronized(lock) {
            map[key]?.let {
                reader.close()
                return it.retain()
            }
            if (map.size >= maxEntries) {
                val eldest = map.entries.first()
                log.debug("GitLab coordinator: evicting reader (${eldest.key})")
                map.remove(eldest.key)?.close()
            }
            map[key] = reader
            return reader.retain()
        }
    }

    fun removeAll(keyPrefix: String) {
        synchronized(lock) {
            val keys = map.keys.filter { it.startsWith(keyPrefix) }
            keys.forEach { map.remove(it)?.close() }
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun clear() {
        synchronized(lock) {
            map.values.forEach { it.close() }
            map.clear()
        }
    }
}
