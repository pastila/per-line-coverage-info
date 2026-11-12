package com.github.yakov255.perlinecoverageinfo

/** Local git refs and settings that can make the next coverage answer different. No network reads. */
internal data class CoverageRefreshContext(
    val gitRoot: String,
    val head: String,
    val branch: String?,
    val remoteHead: String?,
    val baselineHead: String?,
    val settings: String,
)

/** Stops idle refreshes but keeps waiting for coverage of a pushed commit. */
internal class CoverageRefreshState(private val nanoTime: () -> Long = System::nanoTime) {
    private var observed: CoverageRefreshContext? = null
    private var settled: CoverageRefreshContext? = null
    private var refreshedAt = 0L
    private var waitingForBaseline = false
    private var waitingForPush: CoverageRefreshContext? = null

    @Synchronized
    fun onReady(context: CoverageRefreshContext, stale: Boolean, baselinePending: Boolean = false) {
        observed = context
        waitingForPush = null
        waitingForBaseline = baselinePending
        settled = context.takeUnless { stale || baselinePending }
        if (settled != null) refreshedAt = nanoTime()
    }

    @Synchronized
    fun shouldPoll(context: CoverageRefreshContext, loadedCommit: String?): Boolean {
        if (context == settled || context == waitingForPush || context.remoteHead == null) return false
        return context != observed || waitingForBaseline || context.remoteHead != loadedCommit
    }

    /** A pipeline cannot appear for an unpublished HEAD until its origin ref changes. */
    @Synchronized
    fun onNotReady(context: CoverageRefreshContext, reason: String?): Boolean {
        observed = context
        settled = null
        val unpublished = reason == "no_pipeline_yet" && context.remoteHead != context.head
        waitingForPush = context.takeIf { unpublished }
        return !unpublished
    }

    @Synchronized
    fun isWithinCooldown(context: CoverageRefreshContext): Boolean =
        context == settled && nanoTime() - refreshedAt < COOLDOWN_NS

    @Synchronized
    fun clear() {
        observed = null
        settled = null
        waitingForBaseline = false
        waitingForPush = null
    }

    companion object {
        private const val COOLDOWN_NS = 60_000_000_000L
    }
}
