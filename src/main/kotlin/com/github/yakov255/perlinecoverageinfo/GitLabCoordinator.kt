package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level coordinator that owns the single, shared GitLab "worker"
 * for the whole JVM.
 *
 * Every project window, poller and resolver should talk to GitLab through this
 * service. Identical requests (same ref, same pipeline, same merge-base) are
 * served from a shared TTL cache, and actual API calls are serialized on a
 * single worker thread and rate-limited — so N open windows of the same repo
 * produce the same network traffic as a single window.
 *
 * Higher-level coordination that is keyed by the repository:
 * - [sharedReader]: shared [Cov4Reader] instances so windows don't re-read
 *   large coverage files.
 * - [singleFlight] / [memoize]: coalesce concurrent identical work and cache
 *   its result (e.g. pipeline resolution, artifact download).
 * - [markRefreshed] / [isWithinRefreshCooldown]: avoid re-resolving coverage
 *   that was just refreshed.
 */
@Service(Service.Level.APP)
class GitLabCoordinator : Disposable {

    private val log = CoverageLog.get(GitLabCoordinator::class.java)

    @Volatile
    private var api: CachedGitLabApi? = null
    private var clientBaseUrl: String? = null
    private var clientToken: String? = null

    private val lock = Any()

    private val readerCache = ReaderLruCache(MAX_READER_CACHE_ENTRIES, log)
    private val singleFlight = ConcurrentHashMap<String, CompletableFuture<*>>()
    private val memoizedCaches = ConcurrentHashMap<String, TtlCache<String, Any>>()
    private val refreshCooldowns = ConcurrentHashMap<String, Long>()

    /**
     * Returns the shared, cached, rate-limited GitLab API for the current
     * settings. Recreates the underlying client when the domain or token
     * changes; the TTL caches are reset so stale data is not served after a
     * configuration change.
     */
    fun api(): GitLabApi {
        val settings = CoverageApiSettings.getInstance()
        val baseUrl = settings.gitlabBaseUrl
        val token = settings.bearerToken

        api?.let { existing ->
            if (clientBaseUrl == baseUrl && clientToken == token) return existing
        }

        synchronized(lock) {
            api?.let { existing ->
                if (clientBaseUrl == baseUrl && clientToken == token) return existing
            }
            log.info("GitLab coordinator: creating shared API client (baseUrl=$baseUrl)")
            val previous = api
            val cached = CachedGitLabApi(GitLabApiClient(baseUrl, token))
            api = cached
            clientBaseUrl = baseUrl
            clientToken = token
            previous?.shutdown()
            // Settings changed — drop cached resolution results so nothing stale is served.
            val memoizedCount = memoizedCaches.size
            val cooldownCount = refreshCooldowns.size
            val readerCount = readerCache.size()
            memoizedCaches.clear()
            refreshCooldowns.clear()
            readerCache.clear()
            log.info("GitLab coordinator: settings changed — cleared in-memory caches (memoized=$memoizedCount, cooldowns=$cooldownCount, readers=$readerCount)")
            return cached
        }
    }

    /**
     * Returns a shared [Cov4Reader] for the given commit/component, opening it
     * via [open] on first access. Readers are immutable and [Cov4Reader.close]
     * is a no-op, so sharing an instance across project windows is safe.
     */
    fun sharedReader(commitHash: String, component: String, open: () -> Cov4Reader?): Cov4Reader? =
        readerCache.getOrOpen("$commitHash|$component", open)

    /**
     * Coalesces concurrent identical work: if another thread is already running
     * [key], this call joins that in-flight work instead of running [loader]
     * again. On success/failure the result is shared with all waiters.
     */
    fun <T> singleFlight(key: String, loader: () -> T): T {
        val existing = singleFlight[key]
        if (existing != null) {
            log.debug("GitLab coordinator: joining in-flight work ($key)")
            @Suppress("UNCHECKED_CAST")
            return (existing as CompletableFuture<T>).joinAndUnwrap()
        }
        val future = CompletableFuture<T>()
        val raced = singleFlight.putIfAbsent(key, future)
        if (raced != null) {
            log.debug("GitLab coordinator: joining in-flight work ($key)")
            @Suppress("UNCHECKED_CAST")
            return (raced as CompletableFuture<T>).joinAndUnwrap()
        }
        log.debug("GitLab coordinator: starting work ($key)")
        try {
            val value = loader()
            future.complete(value)
            return value
        } catch (t: Throwable) {
            future.completeExceptionally(t)
            throw t
        } finally {
            singleFlight.remove(key)
        }
    }

    /**
     * Single-flight + TTL memoization keyed by [name]/[key] across the whole
     * JVM. Repeated calls within [ttlMs] hit the shared cache and cost nothing;
     * concurrent first calls share one [loader] execution.
     */
    fun <T : Any> memoize(name: String, key: String, ttlMs: Long, loader: () -> T): T {
        val cache = memoizedCaches.getOrPut(name) { TtlCache<String, Any>(ttlMs) }
        @Suppress("UNCHECKED_CAST")
        cache.get(key)?.let {
            log.debug("GitLab coordinator: memoized hit ($name:$key)")
            return it as T
        }
        return singleFlight("$name:$key") {
            @Suppress("UNCHECKED_CAST")
            cache.get(key)?.let {
                log.debug("GitLab coordinator: memoized hit ($name:$key)")
                return@singleFlight it as T
            }
            val value = loader()
            cache.put(key, value)
            value
        }
    }

    /** Records a successful coverage refresh of [gitRoot] at [headSha]. */
    fun markRefreshed(gitRoot: File, headSha: String) {
        refreshCooldowns["$gitRoot|$headSha"] = System.currentTimeMillis()
    }

    /** True if [gitRoot]@[headSha] was refreshed less than [cooldownMs] ago. */
    fun isWithinRefreshCooldown(gitRoot: File, headSha: String, cooldownMs: Long): Boolean {
        val last = refreshCooldowns["$gitRoot|$headSha"] ?: return false
        return System.currentTimeMillis() - last < cooldownMs
    }

    override fun dispose() {
        synchronized(lock) {
            api?.shutdown()
            api = null
        }
        singleFlight.clear()
        memoizedCaches.clear()
        refreshCooldowns.clear()
        readerCache.clear()
    }

    companion object {
        private const val MAX_READER_CACHE_ENTRIES = 6

        fun getInstance(): GitLabCoordinator = service()
    }
}

private fun <T> CompletableFuture<T>.joinAndUnwrap(): T {
    return try {
        join()
    } catch (e: CompletionException) {
        throw e.cause ?: e
    }
}

/**
 * Small LRU for [Cov4Reader] instances. [Cov4Reader.close] is a no-op, so
 * evicted readers are simply dropped (their underlying byte arrays become
 * garbage collectable).
 */
private class ReaderLruCache(private val maxEntries: Int, private val log: CoverageLog) {

    private val map = object : LinkedHashMap<String, Cov4Reader>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cov4Reader>?): Boolean =
            size > maxEntries
    }
    private val lock = Any()

    fun getOrOpen(key: String, open: () -> Cov4Reader?): Cov4Reader? {
        synchronized(lock) {
            map[key]?.let {
                log.debug("GitLab coordinator: reusing cached reader ($key)")
                return it
            }
        }
        val reader = open() ?: return null
        synchronized(lock) {
            map[key]?.let { return it }
            if (map.size >= maxEntries) {
                val eldest = map.entries.first()
                log.debug("GitLab coordinator: evicting reader (${eldest.key})")
                map.remove(eldest.key)
            }
            map[key] = reader
            return reader
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }
}
