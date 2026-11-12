package com.github.yakov255.perlinecoverageinfo

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** User name part of the single credential entry this plugin owns. */
private const val TOKEN_USER = "gitlab"

/** Test escape hatch, inert by default: `-Dcoverage.tokenStore=memory` keeps the token in the process. */
internal const val TOKEN_STORE_PROPERTY = "coverage.tokenStore"

private const val TOKEN_WRITER_THREAD = "coverage-token-writer"

/**
 * The credential store entry for the GitLab access token. Shared with the tests, which read the
 * entry back from [PasswordSafe].
 */
internal fun gitlabTokenAttributes(): CredentialAttributes = CredentialAttributes(
    generateServiceName("PerLineCoverageInfo", "gitlabAccessToken"),
    TOKEN_USER,
)

/**
 * Where the GitLab access token lives. The token is a credential, not a setting: keeping it behind
 * this interface is what keeps it out of the plain-text `coverageApiSettings.xml`.
 */
interface TokenStore {
    /** Current token, `""` when none is stored. */
    fun read(): String

    /** Stores [token]; an empty value removes the entry. */
    fun write(token: String)
}

/** Process-only store: used when there is no Application (plain unit tests) and by tests. */
class InMemoryTokenStore(initial: String = "") : TokenStore {

    private val value = AtomicReference(initial)

    override fun read(): String = value.get()

    override fun write(token: String) {
        value.set(token)
    }
}

/**
 * The IDE credential store ([PasswordSafe]): the OS keychain where one exists, the IDE's own
 * encrypted store otherwise.
 *
 * Both calls may hit the keychain and assert on the EDT, so [TokenCache] makes every call from a
 * background thread and never from the UI thread.
 */
class PasswordSafeTokenStore(
    private val attributes: CredentialAttributes = gitlabTokenAttributes(),
) : TokenStore {

    private val log = CoverageLog.get(PasswordSafeTokenStore::class.java)

    override fun read(): String = try {
        PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
    } catch (e: Exception) {
        log.warn("Coverage: cannot read the GitLab token from the credential store: ${e::class.simpleName}", e)
        ""
    }

    override fun write(token: String) {
        try {
            PasswordSafe.instance.set(
                attributes,
                if (token.isEmpty()) null else Credentials(TOKEN_USER, token),
            )
        } catch (e: Exception) {
            log.warn("Coverage: cannot write the GitLab token to the credential store: ${e::class.simpleName}", e)
        }
    }
}

/**
 * In-memory cache in front of a [TokenStore].
 *
 * - Reads return the cached value. A miss is loaded off the EDT; on the EDT the getter returns
 *   `""` at once and asks for a background load, because the credential store may talk to the OS
 *   keychain.
 * - Writes update the cache first, so `validateSettings()` and the settings fingerprint see a new
 *   token immediately, also right after a set on the EDT. The store is written by one daemon
 *   thread in submission order, and a burst of writes collapses into the last value.
 * - [background] is `null` when there is no Application: every call then goes straight to
 *   [store], which is what plain unit tests want.
 */
class TokenCache internal constructor(
    private val store: TokenStore,
    private val background: ((() -> Unit) -> Unit)?,
    private val onEdt: () -> Boolean,
) {

    private val cached = AtomicReference<String?>(null)
    private val loadRequested = AtomicBoolean(false)

    private val pendingWrite = AtomicReference<String?>(null)
    private val writeScheduled = AtomicBoolean(false)
    private val writer = AtomicReference<ExecutorService?>(null)

    /** Current token; never blocks the EDT. */
    fun get(): String {
        cached.get()?.let { return it }
        val run = background
        if (run == null) {
            val loaded = store.read()
            cached.compareAndSet(null, loaded)
            return cached.get().orEmpty()
        }
        if (onEdt()) {
            requestLoad(run)
            return cached.get().orEmpty()
        }
        val loaded = store.read()
        cached.compareAndSet(null, loaded)
        return cached.get().orEmpty()
    }

    /** Updates the cache at once and stores the token in the background. */
    fun set(token: String) {
        cached.set(token)
        if (background == null) {
            store.write(token)
            return
        }
        pendingWrite.set(token)
        if (!writeScheduled.compareAndSet(false, true)) return
        writer().execute(::drainWrites)
    }

    /** Blocks until every queued write reached the store; for tests only. */
    internal fun flush(timeoutMs: Long = 10_000): Boolean {
        val executor = writer.get() ?: return true
        if (Thread.currentThread().name == TOKEN_WRITER_THREAD) return true
        val barrier = CountDownLatch(1)
        try {
            executor.execute { barrier.countDown() }
        } catch (_: RejectedExecutionException) {
            return true
        }
        return try {
            barrier.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun requestLoad(run: (() -> Unit) -> Unit) {
        if (!loadRequested.compareAndSet(false, true)) return
        run {
            try {
                val loaded = store.read()
                cached.compareAndSet(null, loaded)
            } finally {
                loadRequested.set(false)
            }
        }
    }

    private fun writer(): ExecutorService {
        writer.get()?.let { return it }
        val created = Executors.newSingleThreadExecutor { task ->
            Thread(task, TOKEN_WRITER_THREAD).apply { isDaemon = true }
        }
        if (writer.compareAndSet(null, created)) return created
        created.shutdown()
        return writer.get() ?: created
    }

    private fun drainWrites() {
        while (true) {
            val next = pendingWrite.getAndSet(null)
            if (next == null) {
                writeScheduled.set(false)
                // A set may have raced with the reset: take the job back instead of losing it.
                if (pendingWrite.get() != null && writeScheduled.compareAndSet(false, true)) continue
                return
            }
            store.write(next)
        }
    }
}

/** The store a real IDE uses; without an Application (or with the memory flag) it stays in the process. */
internal fun defaultTokenStore(): TokenStore {
    val application = ApplicationManager.getApplication()
    val memoryOnly = application == null ||
        System.getProperty(TOKEN_STORE_PROPERTY).equals("memory", ignoreCase = true)
    return if (memoryOnly) InMemoryTokenStore() else PasswordSafeTokenStore()
}

internal fun defaultTokenCache(store: TokenStore): TokenCache {
    val application = ApplicationManager.getApplication()
        ?: return TokenCache(store, background = null, onEdt = { false })
    return TokenCache(
        store = store,
        background = { task -> application.executeOnPooledThread(task) },
        onEdt = { application.isDispatchThread },
    )
}
