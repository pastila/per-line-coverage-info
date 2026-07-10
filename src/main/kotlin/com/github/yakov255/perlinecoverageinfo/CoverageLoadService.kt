package com.github.yakov255.perlinecoverageinfo

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * Project-level service that orchestrates loading coverage data from GitLab.
 * Supports offline-first: shows cached (possibly stale) coverage immediately,
 * then fetches fresh coverage in the background.
 *
 * Used by LoadCoverageAction (manual) and CoverageHeadTracker (automatic).
 */
@Service(Service.Level.PROJECT)
class CoverageLoadService(private val project: Project) {

    private val log = CoverageLog.get(CoverageLoadService::class.java)

    @Volatile
    private var loadingCommitHash: String? = null

    @Volatile
    private var pendingReload = false

    /**
     * Validates that the plugin is enabled and GitLab settings are configured.
     * Returns an error message if invalid, null if OK.
     */
    fun validateSettings(): String? {
        val settings = CoverageApiSettings.getInstance()
        if (!settings.enabled) return "Coverage plugin is disabled. Enable it in Settings > Tools > GitLab Coverage."
        return when {
            settings.gitlabDomain.isBlank() -> "GitLab domain is not configured.\nPlease set it in Settings > Tools > GitLab Coverage."
            settings.bearerToken.isBlank() -> "GitLab access token is not configured.\nPlease set it in Settings > Tools > GitLab Coverage."
            settings.gitlabProjectId <= 0 -> "GitLab project is not selected.\nPlease select a project in Settings > Tools > GitLab Coverage."
            else -> null
        }
    }

    /**
     * Offline-first coverage loading:
     * 1. Walks git history to find any cached .cov4 file
     * 2. Shows cached coverage immediately (marked as stale)
     * 3. Fetches fresh coverage from GitLab in background
     * 4. When fresh data arrives, replaces stale data and refreshes highlights
     *
     * If no cache is found, falls through to normal GitLab loading.
     */
    fun loadOfflineFirst() {
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: offline-first load skipped — gutter visibility is off")
            return
        }

        log.info("Coverage: offline-first load started")

        if (loadingCommitHash != null) {
            pendingReload = true
            log.info("Coverage: load already in progress, will reload after completion")
            return
        }

        val cache = CoverageCacheService.getInstance(project)
        val dataService = CoverageDataService.getInstance(project)
        val selectionService = CoverageUserSelectionService.getInstance(project)

        // If the user explicitly pinned a specific artifact, honour that choice.
        val pinned = selectionService.pinnedCommitHash
        if (pinned != null) {
            val root = findGitRoot()
            val component = if (root != null) detectComponent(root) ?: "raketa" else "raketa"
            val reader = cache.get(pinned, component)
            if (reader != null) {
                val alreadyLoaded = dataService.coverageCommitHash == pinned && dataService.hasData()
                log.info("Coverage: offline-first pinned — commit ${pinned.take(8)}, alreadyLoaded=$alreadyLoaded")
                if (!alreadyLoaded) {
                    if (root != null) {
                        dataService.setCoverageContext(pinned, root, stale = false)
                        computeAndStoreWarningContext(root)
                    }
                    dataService.setCov4Reader(reader)
                    cache.updateLastUsed(pinned, component)
                    ApplicationManager.getApplication().invokeLater {
                        CoverageHighlighter.applyToOpenEditors(project)
                    }
                }
                // Don't auto-refresh from GitLab — user chose this artifact deliberately.
                return
            }
            // Pinned artifact was deleted or expired — clear the pin and fall through.
            log.info("Coverage: pinned commit ${pinned.take(8)} not found in cache, clearing pin")
            selectionService.pinnedCommitHash = null
        }

        // Try to find cached coverage matching a recent HEAD commit
        val gitRoot = findGitRoot()
        if (gitRoot != null) {
            val component = detectComponent(gitRoot)
            if (component != null) {
                val commits = getRecentCommits(gitRoot, 200)
                if (commits.isNotEmpty()) {
                    val cachedCommit = cache.findCachedCommit(commits)
                    if (cachedCommit != null) {
                        val reader = cache.get(cachedCommit, component)
                        if (reader != null) {
                            val alreadyLoaded = dataService.coverageCommitHash == cachedCommit && dataService.hasData()
                            log.info("Coverage: offline-first hit — commit ${cachedCommit.take(8)} (component=$component), alreadyLoaded=$alreadyLoaded")
                            if (!alreadyLoaded) {
                                dataService.setCoverageContext(cachedCommit, gitRoot, stale = true)
                                computeAndStoreWarningContext(gitRoot)
                                dataService.setCov4Reader(reader)
                                cache.updateLastUsed(cachedCommit, component)
                                ApplicationManager.getApplication().invokeLater {
                                    CoverageHighlighter.applyToOpenEditors(project)
                                }
                            }
                            // Always try to fetch fresh in background (errors are silent)
                            loadFromGitLab(showErrors = false)
                            return
                        }
                    }
                }

                // No exact commit match — fall back to the most recently cached artifact for this component.
                val latestArtifact = cache.listArtifacts().firstOrNull { it.component == component }
                if (latestArtifact != null) {
                    val reader = cache.get(latestArtifact.commitHash, latestArtifact.component)
                    if (reader != null) {
                        val alreadyLoaded = dataService.coverageCommitHash == latestArtifact.commitHash && dataService.hasData()
                        log.info("Coverage: offline-first fallback — commit ${latestArtifact.commitHash.take(8)} (component=$component), alreadyLoaded=$alreadyLoaded")
                        if (!alreadyLoaded) {
                            dataService.setCoverageContext(latestArtifact.commitHash, gitRoot, stale = true)
                            computeAndStoreWarningContext(gitRoot)
                            dataService.setCov4Reader(reader)
                            cache.updateLastUsed(latestArtifact.commitHash, latestArtifact.component)
                            ApplicationManager.getApplication().invokeLater {
                                CoverageHighlighter.applyToOpenEditors(project)
                            }
                        }
                        loadFromGitLab(showErrors = false)
                        return
                    }
                }
            }
        }

        // No cache at all — go straight to GitLab
        log.info("Coverage: no cached artifacts found, loading from GitLab")
        loadFromGitLab(showErrors = false)
    }

    /**
     * Loads coverage from GitLab in two phases:
     * 1. Resolution (silent, no progress indicator) — resolve pipeline, check cache
     * 2. Download (visible, with progress) — only if artifacts need fetching
     *
     * @param showErrors If true, shows error dialogs on failure. If false, errors are only logged and [onError] is called.
     * @param onComplete Called on the EDT when the task finishes (success or failure), so callers can refresh UI.
     * @param onError Called on the EDT with a short friendly message when the load fails. Ignored when [showErrors]=true.
     */
    fun loadFromGitLab(showErrors: Boolean = true, onComplete: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val validationError = validateSettings()
        if (validationError != null) {
            if (showErrors) {
                Messages.showErrorDialog(project, validationError, "Configuration Error")
            } else {
                log.info("Coverage auto-load skipped: $validationError")
            }
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        }

        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: GitLab load skipped — gutter visibility is off")
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        }

        if (loadingCommitHash != null) {
            pendingReload = true
            log.info("Coverage: GitLab load already in progress, will reload after completion")
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        }

        loadingCommitHash = "" // sentinel — refined to actual commit after resolution

        // Phase 1: resolve pipeline and check cache — no progress indicator
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                resolvePhase(showErrors, onComplete, onError)
            } catch (ex: Exception) {
                log.warn("Coverage: unexpected error during resolve phase: ${ex.message}", ex)
                if (showErrors) {
                    val msg = "Failed to resolve coverage: ${ex.message}"
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, msg, "Coverage Error")
                    }
                }
                finishLoad(onComplete)
            }
        }
    }

    /**
     * Resolution phase: clears pinned, cleans cache, resolves pipeline, checks cache.
     * If both primary and baseline are cached — applies silently.
     * Otherwise starts [startDownloadTask] with progress indicator.
     */
    private fun resolvePhase(showErrors: Boolean, onComplete: (() -> Unit)?, onError: ((String) -> Unit)?) {
        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = null

        CoverageCacheService.getInstance(project).cleanup()

        val settings = CoverageApiSettings.getInstance()
        val gitLabClient = GitLabApiClient(settings.gitlabBaseUrl, settings.bearerToken)
        val resolver = CoverageResolver(gitLabClient, project)
        val dual = resolver.resolveDual()
        val resolved = dual.primary
        loadingCommitHash = resolved.commitHash
        log.info("Coverage: resolved primary pipeline ${resolved.pipelineId} at commit ${resolved.commitHash.take(8)}")
        if (dual.baseline != null) {
            log.info("Coverage: resolved baseline pipeline ${dual.baseline.pipelineId} at commit ${dual.baseline.commitHash.take(8)}")
        } else {
            log.info("Coverage: no baseline pipeline (single-coverage mode)")
        }

        val cache = CoverageCacheService.getInstance(project)
        val dataService = CoverageDataService.getInstance(project)
        val component = detectComponent(resolved.gitRoot)
        if (component == null) {
            throw CoverageApiException(
                "Project is not at the repository root and not inside a recognized component directory.\n" +
                    "Recognized components: ${ComponentConfig.allEntries().joinToString(", ") { it.serviceDir }}",
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        // If the same commit is already loaded, skip the reader swap
        if (dataService.coverageCommitHash == resolved.commitHash && dataService.hasData()) {
            log.info("Coverage: commit ${resolved.commitHash.take(8)} already loaded, skipping reader swap")
            dataService.setCoverageContext(resolved.commitHash, resolved.gitRoot)
            computeAndStoreWarningContext(resolved.gitRoot)
            loadOrFetchBaseline(null, gitLabClient, cache, dual.baseline)
            finishLoad(onComplete)
            return
        }

        val primaryCached = cache.get(resolved.commitHash, component)
        val baselineCached = dual.baseline?.let { cache.get(it.commitHash, component) }

        if (primaryCached != null && baselineCached != null) {
            // Both cached — apply silently, no progress shown
            log.info("Coverage: primary and baseline both cached — applying silently (component=$component)")
            cache.updateLastUsed(resolved.commitHash, component)
            dataService.setCoverageContext(resolved.commitHash, resolved.gitRoot)
            computeAndStoreWarningContext(resolved.gitRoot)
            dataService.setCov4Reader(primaryCached)
            loadOrFetchBaseline(null, gitLabClient, cache, dual.baseline, preloadedReader = baselineCached)
            ApplicationManager.getApplication().invokeLater {
                CoverageHighlighter.applyToOpenEditors(project)
                notifyIfFallback(resolved)
            }
            finishLoad(onComplete)
            return
        }

        // Phase 2: cache miss for primary or baseline — download with progress
        startDownloadTask(gitLabClient, dual, cache, dataService, primaryCached, baselineCached, resolved,
            showErrors, onComplete, onError)
    }

    /**
     * Download phase: runs inside [Task.Backgroundable] so the user sees progress
     * in the status bar while artifacts are being fetched.
     */
    private fun startDownloadTask(
        gitLabClient: GitLabApiClient,
        dual: DualResolved,
        cache: CoverageCacheService,
        dataService: CoverageDataService,
        primaryCached: Cov4Reader?,
        baselineCached: Cov4Reader?,
        resolved: ResolvedPipeline,
        showErrors: Boolean,
        onComplete: (() -> Unit)?,
        onError: ((String) -> Unit)?,
    ) {
        val component = detectComponent(resolved.gitRoot)
        if (component == null) {
            finishLoad(onComplete)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Coverage", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val wasStale = dataService.isStale
                    val previousCommit = dataService.coverageCommitHash

                    if (primaryCached == null) {
                        log.info("Coverage: cache miss — downloading primary artifacts for pipeline ${resolved.pipelineId}")
                        var fellBackToBaseline = false
                        val result = try {
                            downloadArtifacts(indicator, gitLabClient, resolved,
                                fractionStart = 0.0, fractionEnd = 0.6)
                        } catch (e: CoverageApiException) {
                            if (e.kind == CoverageErrorKind.NO_DATA && dual.baseline != null) {
                                log.info("Coverage: no ${component} jobs in primary pipeline, falling back to baseline pipeline ${dual.baseline.pipelineId}")
                                dataService.clearBaseline()
                                fellBackToBaseline = true
                                downloadArtifacts(indicator, gitLabClient, dual.baseline,
                                    fractionStart = 0.0, fractionEnd = 0.6)
                            } else {
                                throw e
                            }
                        }

                        for (cr in result.componentResults) {
                            cache.writeCov4(result.resolved.commitHash, result.resolved.pipelineId, cr.coverage, component = cr.component)
                        }

                        val freshReader = cache.get(result.resolved.commitHash, component)
                        if (freshReader != null) {
                            dataService.setCoverageContext(result.resolved.commitHash, result.resolved.gitRoot, stale = false)
                            computeAndStoreWarningContext(result.resolved.gitRoot)
                            dataService.setCov4Reader(freshReader)
                        } else {
                            log.warn("Coverage: failed to reopen freshly-written .cov4, falling back to in-memory map")
                            val fallback = result.componentResults.firstOrNull { it.component == component }
                                ?: result.componentResults.first()
                            dataService.setCoverageContext(result.resolved.commitHash, result.resolved.gitRoot, stale = false)
                            computeAndStoreWarningContext(result.resolved.gitRoot)
                            dataService.setCoverageAll(fallback.coverage)
                        }

                        if (!fellBackToBaseline) {
                            loadOrFetchBaseline(indicator, gitLabClient, cache, dual.baseline, preloadedReader = baselineCached, baseProgress = 0.6)
                        }
                    } else {
                        cache.updateLastUsed(resolved.commitHash, component)
                        dataService.setCoverageContext(resolved.commitHash, resolved.gitRoot)
                        computeAndStoreWarningContext(resolved.gitRoot)
                        dataService.setCov4Reader(primaryCached)
                        loadOrFetchBaseline(indicator, gitLabClient, cache, dual.baseline, preloadedReader = baselineCached, baseProgress = 0.6)
                    }

                    log.info("Coverage: loaded coverage for commit ${resolved.commitHash.take(8)}")
                    ApplicationManager.getApplication().invokeLater {
                        CoverageHighlighter.applyToOpenEditors(project)
                        notifyIfFallback(resolved)
                        notifyRefreshedFromStale(wasStale, previousCommit, resolved)
                    }
                } catch (ex: CoverageApiException) {
                    log.warn("Coverage: GitLab load failed (${ex.kind}): ${ex.userMessage}")
                    val friendly = friendlyApiError(ex)
                    if (showErrors) {
                        val title = errorTitle(ex.kind)
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, ex.userMessage, title)
                        }
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("Coverage Notifications")
                                .createNotification(
                                    "Coverage unavailable",
                                    friendly,
                                    NotificationType.WARNING,
                                )
                                .notify(project)
                        }
                    }
                    onError?.let { ApplicationManager.getApplication().invokeLater { it(friendly) } }
                } catch (ex: Exception) {
                    log.warn("Coverage: unexpected error during GitLab load: ${ex.message}", ex)
                    val friendly = "Unexpected error: ${ex.message}"
                    if (showErrors) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "An unexpected error occurred while loading coverage data:\n${ex.message}",
                                "Coverage Error"
                            )
                        }
                    }
                    onError?.let { ApplicationManager.getApplication().invokeLater { it(friendly) } }
                } finally {
                    finishLoad(onComplete)
                }
            }
        })
    }

    /** Resets loading state and handles pending reloads, callbacks, and poller start. */
    private fun finishLoad(onComplete: (() -> Unit)?) {
        loadingCommitHash = null
        if (pendingReload) {
            pendingReload = false
            log.info("Coverage: pending reload detected, starting new load")
            ApplicationManager.getApplication().invokeLater {
                loadFromGitLab(showErrors = false)
            }
        }
        onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
        if (CoverageGutterVisibilityService.getInstance(project).visible) {
            CoveragePipelinePoller.getInstance(project).start()
        }
    }

    /**
     * Downloads and parses coverage artifacts for an already-resolved pipeline.
     */
    fun downloadArtifacts(
        indicator: ProgressIndicator,
        gitLabClient: GitLabApiClient,
        resolved: ResolvedPipeline,
        fractionStart: Double = 0.2,
        fractionEnd: Double = 0.8,
    ): CoverageLoadResult {
        val settings = CoverageApiSettings.getInstance()

        indicator.text = "Finding coverage artifacts..."
        indicator.fraction = fractionStart

        val jobs = gitLabClient.listPipelineJobs(settings.gitlabProjectId, resolved.pipelineId)

        val component = ComponentConfig.detectComponent(
            project.basePath ?: "",
            resolved.gitRoot,
        ) ?: throw CoverageApiException(
            "Project is not at the repository root and not inside a recognized component directory.\n\n" +
                "Open the project at the repository root or inside a recognized component directory.\n" +
                "Recognized components: ${ComponentConfig.allEntries().joinToString(", ") { it.serviceDir }}\n\n" +
                "Pipeline has ${jobs.size} job(s): ${jobs.joinToString(", ") { "${it.name} (${it.status})" }}",
            details = mapOf("pipelineId" to resolved.pipelineId.toString()),
            kind = CoverageErrorKind.NO_DATA,
        )

        val componentBehatJobs = jobs.filter { job ->
            job.name.contains("behat", ignoreCase = true) && job.name.contains(component.jobPrefix, ignoreCase = true)
        }

        if (componentBehatJobs.isEmpty()) {
            throw CoverageApiException(
                "No ${component.serviceDir} behat jobs found in pipeline #${resolved.pipelineId}.",
                details = mapOf("pipelineId" to resolved.pipelineId.toString()),
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        // Wait for any running/pending jobs to finish.
        var currentJobs = componentBehatJobs
        val runningStatuses = setOf("running", "pending", "created", "waiting_for_resource")
        while (currentJobs.any { it.status in runningStatuses }) {
            if (pendingReload) {
                log.info("Coverage: reload pending, cancelling wait for running jobs")
                throw CoverageApiException("Reload requested", kind = CoverageErrorKind.NO_DATA)
            }
            if (indicator.isCanceled) {
                throw CoverageApiException("Coverage loading was cancelled", kind = CoverageErrorKind.NO_DATA)
            }
            val running = currentJobs.filter { it.status in runningStatuses }
            indicator.text = "Waiting for ${component.serviceDir} behat: ${running.size} job(s) still running..."
            Thread.sleep(10_000L)

            try {
                val freshJobs = gitLabClient.listPipelineJobs(settings.gitlabProjectId, resolved.pipelineId)
                currentJobs = freshJobs.filter { job ->
                    job.name.contains("behat", ignoreCase = true) && job.name.contains(component.jobPrefix, ignoreCase = true)
                }
            } catch (e: CoverageApiException) {
                if (e.kind == CoverageErrorKind.NETWORK) {
                    log.warn("Coverage: network error during wait, will retry in 10s")
                    // Keep currentJobs as-is, retry next iteration
                } else {
                    throw e
                }
            }
        }

        val failedJobs = currentJobs.filter { it.status != "success" }
        if (failedJobs.isNotEmpty()) {
            throw CoverageApiException(
                "Not all ${component.serviceDir} behat jobs succeeded in pipeline #${resolved.pipelineId}.\n\n" +
                    "Failing jobs: ${failedJobs.joinToString(", ") { "${it.name} (${it.status})" }}",
                details = mapOf("pipelineId" to resolved.pipelineId.toString()),
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        val artifactJobs = currentJobs.filter { job ->
            job.artifactsFile != null || job.artifacts.isNotEmpty()
        }
        if (artifactJobs.isEmpty()) {
            throw CoverageApiException(
                "No coverage artifacts for component '${component.serviceDir}' in pipeline #${resolved.pipelineId}.",
                details = mapOf("pipelineId" to resolved.pipelineId.toString()),
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        log.info("Coverage: component '${component.serviceDir}' — ${artifactJobs.size} behat jobs ready")
        val targetJobs = artifactJobs
        val artifactComponent = component.serviceDir

        if (indicator.isCanceled) {
            throw CoverageApiException(
                "Coverage loading was cancelled",
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        val totalJobs = targetJobs.size
        val span = fractionEnd - fractionStart
        indicator.text = "Downloading coverage artifacts..."
        indicator.fraction = fractionStart + span * 0.15

        val progressCount = AtomicInteger(0)
        val batchStartTime = System.nanoTime()
        val totalBytesReceived = AtomicLong(0)
        val futures = targetJobs.map { job ->
            var jobBytesReceived = 0L
            val onProgress: (Long, Long) -> Unit = { received, total ->
                val delta = received - jobBytesReceived
                if (delta > 0) {
                    jobBytesReceived = received
                    val allBytes = totalBytesReceived.addAndGet(delta)
                    val elapsedSec = (System.nanoTime() - batchStartTime) / 1_000_000_000.0
                    val speed = if (elapsedSec > 0) allBytes / elapsedSec else 0.0
                    val totalStr = if (total > 0) "/${formatBytes(total)}" else ""
                    val text2 = "${job.name} — ${formatBytes(received)}$totalStr @ ${formatSpeed(speed)}"
                    ApplicationManager.getApplication().invokeLater {
                        indicator.text2 = text2
                    }
                }
            }

            gitLabClient.downloadSingleArtifactFileAsync(
                settings.gitlabProjectId,
                job.id,
                "coverage-reports/coverage.covt.gz",
                onProgress,
            ).thenApply { covtBytes ->
                log.info("Coverage: downloaded ${covtBytes.size} bytes from job ${job.name} (id=${job.id})")
                val parsed = BinaryCoverageParser.parsePossiblyGzippedCovtBytes(covtBytes)
                log.info("Coverage: parsed ${parsed.size} files from job ${job.name}")

                val done = progressCount.incrementAndGet()
                val text = "Downloading artifacts... ($done/$totalJobs)"
                val fraction = fractionStart + span * (0.15 + 0.8 * (done.toDouble() / totalJobs))
                ApplicationManager.getApplication().invokeLater {
                    indicator.text = text
                    indicator.fraction = fraction
                }

                parsed
            }
        }

        try {
            CompletableFuture.allOf(*futures.toTypedArray()).join()
        } catch (e: CompletionException) {
            futures.forEach { it.cancel(true) }
            val cause = e.cause ?: e
            if (cause is CoverageApiException) throw cause
            throw CoverageApiException(
                "Failed to download coverage artifacts",
                cause,
                kind = CoverageErrorKind.NETWORK,
            )
        }

        if (indicator.isCanceled) {
            futures.forEach { it.cancel(true) }
            throw CoverageApiException(
                "Coverage loading was cancelled",
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        val totalFutures = futures.size
        ApplicationManager.getApplication().invokeLater {
            indicator.text = "Processing reports..."
            indicator.text2 = ""
            indicator.fraction = fractionStart + span * 0.95
        }

        val mergedCoverage = mutableMapOf<String, MutableMap<Int, MutableList<String>>>()
        for ((futureIndex, future) in futures.withIndex()) {
            val parsed = try {
                future.join()
            } catch (e: CompletionException) {
                val cause = e.cause ?: e
                if (cause is CoverageApiException) throw cause
                throw CoverageApiException(
                    "Failed to download coverage artifacts",
                    cause,
                    kind = CoverageErrorKind.NETWORK,
                )
            }
            for ((filePath, lineMap) in parsed) {
                val existingFileMap = mergedCoverage.getOrPut(filePath) { mutableMapOf() }
                for ((lineNum, testNames) in lineMap) {
                    val existingTests = existingFileMap.getOrPut(lineNum) { mutableListOf() }
                    val unionSet = mutableSetOf<String>()
                    unionSet.addAll(existingTests)
                    unionSet.addAll(testNames)
                    existingTests.clear()
                    existingTests.addAll(unionSet)
                }
            }
            val processFraction = fractionStart + span * (0.95 + 0.04 * ((futureIndex + 1).toDouble() / totalFutures))
            ApplicationManager.getApplication().invokeLater {
                indicator.fraction = processFraction
            }
        }

        val finalCoverage: Map<String, Map<Int, List<String>>> = mergedCoverage.mapValues { (_, lineMap) ->
            lineMap.mapValues { (_, tests) -> tests.toList() }
        }

        return CoverageLoadResult(
            resolved = resolved,
            componentResults = listOf(ComponentCoverage(
                coverage = finalCoverage,
                component = artifactComponent,
                artifactCount = totalJobs,
            )),
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec < 1024 -> "%.0f B/s".format(bytesPerSec)
        bytesPerSec < 1024.0 * 1024.0 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
    }

    /**
     * Applies loaded coverage data to the project and refreshes highlights.
     * If the previous coverage was stale (offline-first cache), notifies that it's been refreshed.
     */
    fun applyCoverage(result: CoverageLoadResult) {
        val dataService = CoverageDataService.getInstance(project)
        val wasStale = dataService.isStale
        val previousCommit = dataService.coverageCommitHash
        val cr = result.componentResults.first()

        dataService.setCoverageContext(result.resolved.commitHash, result.resolved.gitRoot, stale = false)
        computeAndStoreWarningContext(result.resolved.gitRoot)
        dataService.setCoverageAll(cr.coverage)

        log.info("Coverage: loaded coverage for ${cr.coverage.size} files from ${cr.artifactCount} coverage artifacts at commit ${result.resolved.commitHash}")

        ApplicationManager.getApplication().invokeLater {
            CoverageHighlighter.applyToOpenEditors(project)
            notifyIfFallback(result.resolved)
            notifyRefreshedFromStale(wasStale, previousCommit, result.resolved)
        }
    }

    private fun notifyRefreshedFromStale(
        wasStale: Boolean,
        previousCommit: String?,
        resolved: ResolvedPipeline,
    ) {
        if (wasStale && previousCommit != resolved.commitHash) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Coverage Notifications")
                .createNotification(
                    "Coverage updated",
                    "Fresh coverage from commit ${resolved.commitHash.take(8)} (was ${previousCommit?.take(8) ?: "unknown"}).",
                    NotificationType.INFORMATION,
                )
                .notify(project)
        }
    }

    /**
     * Resolves the baseline (master) coverage for dual-coverage mode and attaches it to
     * [CoverageDataService]. When [indicator] is non-null, shows progress and downloads
     * baseline on cache miss. When [indicator] is null, only loads from cache silently
     * (clears baseline on miss, returns false).
     *
     * @return true if baseline was applied or not needed; false if cache miss with silent mode
     */
    private fun loadOrFetchBaseline(
        indicator: ProgressIndicator?,
        gitLabClient: GitLabApiClient,
        cache: CoverageCacheService,
        baseline: ResolvedPipeline?,
        preloadedReader: Cov4Reader? = null,
        baseProgress: Double = 0.0,
    ): Boolean {
        val dataService = CoverageDataService.getInstance(project)
        if (baseline == null) {
            dataService.clearBaseline()
            return true
        }
        val component = detectComponent(baseline.gitRoot)
        if (component == null) {
            dataService.clearBaseline()
            log.info("Coverage: baseline skipped — cannot determine component")
            return false
        }
        try {
            val cached = preloadedReader ?: cache.get(baseline.commitHash, component)
            if (cached != null) {
                cache.updateLastUsed(baseline.commitHash, component)
                dataService.setBaselineCov4Reader(cached, baseline.commitHash)
                log.info("Coverage: baseline loaded from cache (commit ${baseline.commitHash.take(8)}, component=$component)")
                if (indicator != null) indicator.fraction = 1.0
                return true
            }

            if (indicator == null) {
                dataService.clearBaseline()
                log.info("Coverage: baseline not in cache, clearing (will download next time)")
                return false
            }

            log.info("Coverage: baseline cache miss — downloading artifacts for pipeline ${baseline.pipelineId}")
            indicator.text = "Loading baseline coverage…"
            indicator.fraction = baseProgress
            val result = downloadArtifacts(indicator, gitLabClient, baseline,
                fractionStart = baseProgress,
                fractionEnd = 1.0,
            )
            val baselineComponent = result.componentResults.first().component
            cache.writeCov4(baseline.commitHash, baseline.pipelineId, result.componentResults.first().coverage, component = baselineComponent)
            val freshReader = cache.get(baseline.commitHash, baselineComponent)
            if (freshReader != null) {
                dataService.setBaselineCov4Reader(freshReader, baseline.commitHash)
                log.info("Coverage: baseline downloaded and attached (commit ${baseline.commitHash.take(8)})")
            } else {
                log.warn("Coverage: failed to reopen freshly-written baseline .cov4")
                dataService.clearBaseline()
            }
            return true
        } catch (ex: CoverageApiException) {
            log.warn("Coverage: baseline load failed (${ex.kind}): ${ex.userMessage}")
            dataService.clearBaseline()
            return false
        } catch (ex: Exception) {
            log.warn("Coverage: baseline load failed: ${ex.message}", ex)
            dataService.clearBaseline()
            return false
        }
    }

    private fun notifyIfFallback(resolved: ResolvedPipeline) {
        if (resolved.fallback) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Coverage Notifications")
                .createNotification(
                    "Coverage loaded from latest pipeline",
                    "Coverage may not exactly match your current code.\n${resolved.fallbackReason}",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
    }

    /**
     * Loads coverage from a local .covt or .covt.gz file, writes it to the disk cache,
     * and activates it in the same reader-backed way as other load paths.
     *
     * A stable "commit hash" is derived from the SHA-1 of the file bytes so that loading
     * the same file twice reuses the existing cache entry.  [pipelineId] is stored as 0
     * to indicate the artifact came from a local file rather than a CI pipeline.
     *
     * [onComplete] is called on the EDT when done (success or failure), so callers can
     * refresh any UI that depends on the artifact list.
     */
    fun loadFromLocalFile(file: File, onComplete: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Local Coverage File", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Reading ${file.name}…"
                    val bytes = if (file.name.endsWith(".covt.gz")) {
                        GZIPInputStream(file.inputStream()).use { it.readBytes() }
                    } else {
                        file.readBytes()
                    }

                    indicator.text = "Parsing coverage data…"
                    val coverage = BinaryCoverageParser.parseCovtBytes(bytes)
                    log.info("Coverage: parsed ${coverage.size} files from local file ${file.name}")

                    // Derive a stable key from file content so the same file → same cache entry.
                    val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
                    val hash = sha1.joinToString("") { "%02x".format(it) }

                    val cache = CoverageCacheService.getInstance(project)
                    cache.writeCov4(hash, pipelineId = 0L, coverage, component = "raketa")

                    val gitRoot = findGitRootFromFile(file.parentFile) ?: findGitRoot()
                    val dataService = CoverageDataService.getInstance(project)
                    val component = if (gitRoot != null) detectComponent(gitRoot) ?: "raketa" else "raketa"

                    val reader = cache.get(hash, component)
                    if (reader != null) {
                        if (gitRoot != null) {
                            dataService.setCoverageContext(hash, gitRoot, stale = false)
                            computeAndStoreWarningContext(gitRoot)
                        }
                        dataService.setCov4Reader(reader)
                        cache.updateLastUsed(hash, component)
                    } else {
                        log.warn("Coverage: failed to reopen freshly-written .cov4 for local file, falling back to in-memory map")
                        if (gitRoot != null) {
                            dataService.setCoverageContext(hash, gitRoot, stale = false)
                            computeAndStoreWarningContext(gitRoot)
                        }
                        dataService.setCoverageAll(coverage)
                    }

                    ApplicationManager.getApplication().invokeLater {
                        CoverageHighlighter.applyToOpenEditors(project)
                        onComplete?.invoke()
                    }
                } catch (ex: Exception) {
                    log.warn("Coverage: failed to load local file ${file.name}", ex)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to parse coverage file:\n${ex.message}",
                            "Coverage Error",
                        )
                        onComplete?.invoke()
                    }
                }
            }
        })
    }

    /**
     * Loads coverage from a locally cached .cov4 file for the given commit hash.
     * Used when the user explicitly selects an artifact from the Artifacts panel.
     *
     * Runs I/O on a pooled thread, then applies highlights on the EDT.
     * [onComplete] is invoked on the EDT when done (whether successful or not).
     */
    fun loadFromCache(commitHash: String, onComplete: (() -> Unit)? = null) {
        val cache = CoverageCacheService.getInstance(project)
        val dataService = CoverageDataService.getInstance(project)
        val gitRoot = findGitRoot()
        val component = if (gitRoot != null) detectComponent(gitRoot) ?: "raketa" else "raketa"

        // Persist the user's explicit choice so it survives IDE restarts.
        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = commitHash

        ApplicationManager.getApplication().executeOnPooledThread {
            val reader = cache.get(commitHash, component)
            if (reader == null) {
                log.warn("Coverage: loadFromCache — no .cov4 found for commit $commitHash (component=$component)")
                onComplete?.let { ApplicationManager.getApplication().invokeLater(it) }
                return@executeOnPooledThread
            }

            if (gitRoot != null) {
                dataService.setCoverageContext(commitHash, gitRoot, stale = false)
                computeAndStoreWarningContext(gitRoot)
            } else {
                log.warn("Coverage: loadFromCache — could not determine git root; coverage context not updated")
            }
            cache.updateLastUsed(commitHash, component)
            dataService.setCov4Reader(reader)
            log.info("Coverage: loaded from cache for commit ${commitHash.take(8)} (component=$component, ${reader.allFilePaths.size} files)")

            ApplicationManager.getApplication().invokeLater {
                CoverageHighlighter.applyToOpenEditors(project)
                onComplete?.invoke()
            }
        }
    }

    /**
     * Finds the git root for the project base directory.
     */
    private fun findGitRoot(): File? {
        val basePath = project.basePath ?: return null
        val projectDir = File(basePath)
        val gitRootPath = CoverageResolver.runGitCommand(projectDir, "rev-parse", "--show-toplevel")
            ?: return null
        return File(gitRootPath)
    }

    /** Computes all warning-context fields (HEAD, merge-base, behind-counts) and stores them. */
    private fun computeAndStoreWarningContext(gitRoot: File) {
        val dataService = CoverageDataService.getInstance(project)
        val coverageBranch = CoverageApiSettings.getInstance().coverageBranch.trim()
        val headHash = CoverageResolver.runGitCommand(gitRoot, "rev-parse", "HEAD")
        val mergeBase = if (coverageBranch.isNotEmpty()) {
            CoverageResolver.runGitCommand(gitRoot, "merge-base", "HEAD", coverageBranch)
                ?: CoverageResolver.runGitCommand(gitRoot, "merge-base", "HEAD", "origin/$coverageBranch")
        } else null

        val primaryBehindBy = if (headHash != null && dataService.coverageCommitHash != null && headHash != dataService.coverageCommitHash) {
            val count = countBehind(gitRoot, dataService.coverageCommitHash!!, headHash)
            if (count != null && count > 0) count else null
        } else null

        val baselineBehindBy = if (mergeBase != null && dataService.baselineCommitHash != null && mergeBase != dataService.baselineCommitHash) {
            val count = countBehind(gitRoot, dataService.baselineCommitHash!!, mergeBase)
            if (count != null && count > 0) count else null
        } else null

        dataService.setWarningContext(headHash, mergeBase, primaryBehindBy, baselineBehindBy)
        if (mergeBase != null) {
            log.info("Coverage: stored warning context — head=${headHash?.take(8)}, mergeBase=${mergeBase.take(8)}, primaryBehindBy=$primaryBehindBy, baselineBehindBy=$baselineBehindBy")
        } else {
            log.info("Coverage: stored warning context — head=${headHash?.take(8)}, mergeBase=null")
        }
    }

    private fun countBehind(gitRoot: File, actual: String, expected: String): Int? {
        val output = CoverageResolver.runGitCommand(gitRoot, "rev-list", "--count", "$actual..$expected")
        return output?.trim()?.toIntOrNull()
    }

    /** Walks up from [dir] to find the nearest `.git` directory. */
    private fun findGitRootFromFile(dir: File?): File? {
        var current = dir
        while (current != null) {
            if (File(current, ".git").exists()) return current
            current = current.parentFile
        }
        return null
    }

    /**
     * Returns the last [count] commit hashes from the current HEAD.
     */
    private fun getRecentCommits(gitRoot: File, count: Int): List<String> {
        val output = CoverageResolver.runGitCommand(gitRoot, "log", "--format=%H", "-n", count.toString())
            ?: return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    /** Detects the component for the current project, or null if unknown. */
    private fun detectComponent(gitRoot: File): String? {
        val basePath = project.basePath ?: return null
        val entry = ComponentConfig.detectComponent(basePath, gitRoot)
        if (entry != null) {
            log.info("Coverage: detected component '${entry.serviceDir}'")
            return entry.serviceDir
        }
        log.info("Coverage: no component detected for project at $basePath")
        return null
    }

    companion object {
        fun getInstance(project: Project): CoverageLoadService = project.service()

        fun errorTitle(kind: CoverageErrorKind): String = when (kind) {
            CoverageErrorKind.NETWORK -> "Connection Error"
            CoverageErrorKind.GITLAB_API -> "GitLab API Error"
            CoverageErrorKind.PARSE -> "Response Error"
            CoverageErrorKind.ARTIFACT_PARSE -> "Coverage Artifact Error"
            CoverageErrorKind.GIT -> "Git Error"
            CoverageErrorKind.NO_DATA -> "No Coverage Data"
            CoverageErrorKind.PROJECT_SETUP -> "Configuration Error"
        }

        fun friendlyApiError(ex: CoverageApiException): String = when {
            ex.kind == CoverageErrorKind.GITLAB_API && ex.details["httpStatus"] == "401" ->
                "GitLab token expired or invalid. Update it in Settings → Tools → GitLab Coverage."
            ex.kind == CoverageErrorKind.GITLAB_API && ex.details["httpStatus"] == "403" ->
                "Access denied. Check your GitLab token permissions."
            ex.kind == CoverageErrorKind.GITLAB_API && ex.details["httpStatus"] == "404" ->
                "Pipeline or artifacts not found on GitLab."
            ex.kind == CoverageErrorKind.NETWORK ->
                "Cannot connect to GitLab. Check your network connection."
            ex.kind == CoverageErrorKind.NO_DATA ->
                ex.userMessage
            else -> ex.userMessage
        }
    }
}

data class ComponentCoverage(
    val coverage: Map<String, Map<Int, List<String>>>,
    val component: String,
    val artifactCount: Int,
)

data class CoverageLoadResult(
    val resolved: ResolvedPipeline,
    val componentResults: List<ComponentCoverage>,
)
