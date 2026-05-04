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
        log.info("Coverage: offline-first load started")
        val cache = CoverageCacheService.getInstance(project)
        val dataService = CoverageDataService.getInstance(project)
        val selectionService = CoverageUserSelectionService.getInstance(project)

        // If the user explicitly pinned a specific artifact, honour that choice.
        val pinned = selectionService.pinnedCommitHash
        if (pinned != null) {
            val reader = cache.get(pinned)
            if (reader != null) {
                val alreadyLoaded = dataService.coverageCommitHash == pinned && dataService.hasData()
                log.info("Coverage: offline-first pinned — commit ${pinned.take(8)}, alreadyLoaded=$alreadyLoaded")
                if (!alreadyLoaded) {
                    val root = findGitRoot()
                    if (root != null) dataService.setCoverageContext(pinned, root, stale = false)
                    dataService.setCov4Reader(reader)
                    cache.updateLastUsed(pinned)
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
            val commits = getRecentCommits(gitRoot, 200)
            if (commits.isNotEmpty()) {
                val cachedCommit = cache.findCachedCommit(commits)
                if (cachedCommit != null) {
                    val reader = cache.get(cachedCommit)
                    if (reader != null) {
                        val alreadyLoaded = dataService.coverageCommitHash == cachedCommit && dataService.hasData()
                        log.info("Coverage: offline-first hit — commit ${cachedCommit.take(8)}, alreadyLoaded=$alreadyLoaded")
                        if (!alreadyLoaded) {
                            dataService.setCoverageContext(cachedCommit, gitRoot, stale = true)
                            dataService.setCov4Reader(reader)
                            cache.updateLastUsed(cachedCommit)
                            ApplicationManager.getApplication().invokeLater {
                                CoverageHighlighter.applyToOpenEditors(project)
                                NotificationGroupManager.getInstance()
                                    .getNotificationGroup("Coverage Notifications")
                                    .createNotification(
                                        "Showing cached coverage",
                                        "Coverage from commit ${cachedCommit.take(8)}. Fetching fresh data…",
                                        NotificationType.INFORMATION,
                                    )
                                    .notify(project)
                            }
                        }
                        // Always try to fetch fresh in background (errors are silent)
                        loadFromGitLab(showErrors = false)
                        return
                    }
                }
            }
        }

        // No exact commit match in git history — fall back to the most recently
        // cached artifact (same logic as CoverageResolver.fallback on the GitLab path).
        val latestArtifact = cache.listArtifacts().firstOrNull()
        if (latestArtifact != null && gitRoot != null) {
            val reader = cache.get(latestArtifact.commitHash)
            if (reader != null) {
                val alreadyLoaded = dataService.coverageCommitHash == latestArtifact.commitHash && dataService.hasData()
                log.info("Coverage: offline-first fallback — commit ${latestArtifact.commitHash.take(8)}, alreadyLoaded=$alreadyLoaded")
                if (!alreadyLoaded) {
                    dataService.setCoverageContext(latestArtifact.commitHash, gitRoot, stale = true)
                    dataService.setCov4Reader(reader)
                    cache.updateLastUsed(latestArtifact.commitHash)
                    ApplicationManager.getApplication().invokeLater {
                        CoverageHighlighter.applyToOpenEditors(project)
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Coverage Notifications")
                            .createNotification(
                                "Showing cached coverage",
                                "Coverage from commit ${latestArtifact.commitHash.take(8)} (may not match current code). Fetching fresh data…",
                                NotificationType.WARNING,
                            )
                            .notify(project)
                    }
                }
                // Always try to fetch fresh from GitLab in background
                loadFromGitLab(showErrors = false)
                return
            }
        }

        // No cache at all — go straight to GitLab
        log.info("Coverage: no cached artifacts found, loading from GitLab")
        loadFromGitLab(showErrors = false)
    }

    /**
     * Loads coverage from GitLab in a background task with progress indicator.
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Coverage from GitLab", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // User triggered a GitLab fetch — clear any manually pinned artifact
                    // so auto-resolution takes over from now on.
                    CoverageUserSelectionService.getInstance(project).pinnedCommitHash = null

                    // Run cache cleanup in the background
                    CoverageCacheService.getInstance(project).cleanup()

                    indicator.text = "Resolving coverage pipeline..."
                    indicator.fraction = 0.0

                    val settings = CoverageApiSettings.getInstance()
                    val gitLabClient = GitLabApiClient(settings.gitlabBaseUrl, settings.bearerToken)
                    val resolver = CoverageResolver(gitLabClient, project)
                    val dual = resolver.resolveDual()
                    val resolved = dual.primary
                    log.info("Coverage: resolved primary pipeline ${resolved.pipelineId} at commit ${resolved.commitHash.take(8)}")
                    if (dual.baseline != null) {
                        log.info("Coverage: resolved baseline pipeline ${dual.baseline.pipelineId} at commit ${dual.baseline.commitHash.take(8)}")
                    } else {
                        log.info("Coverage: no baseline pipeline (single-coverage mode)")
                    }

                    // Check disk cache by commit hash
                    val cache = CoverageCacheService.getInstance(project)
                    val reader = cache.get(resolved.commitHash)
                    if (reader != null) {
                        log.info("Coverage: loaded primary from COV4 cache (commit ${resolved.commitHash})")
                        cache.updateLastUsed(resolved.commitHash)
                        val dataService = CoverageDataService.getInstance(project)
                        dataService.setCoverageContext(resolved.commitHash, resolved.gitRoot)
                        dataService.setCov4Reader(reader)

                        loadOrFetchBaseline(indicator, gitLabClient, cache, dual.baseline, baseProgress = 0.7)

                        ApplicationManager.getApplication().invokeLater {
                            CoverageHighlighter.applyToOpenEditors(project)
                            notifyIfFallback(resolved)
                        }
                        return
                    }

                    log.info("Coverage: cache miss — downloading primary artifacts for pipeline ${resolved.pipelineId}")
                    val result = downloadArtifacts(indicator, gitLabClient, resolved, fractionStart = 0.1, fractionEnd = 0.7)

                    // Write COV4 to cache, then drop the in-memory merged map and use a
                    // reader-backed view of the freshly-written file. This keeps steady-state
                    // memory bounded (only the Cov4Reader index + lazily-decoded files) and
                    // unifies the post-load state with the cache-hit path above.
                    cache.writeCov4(resolved.commitHash, resolved.pipelineId, result.coverage)

                    val freshReader = cache.get(resolved.commitHash)
                    if (freshReader != null) {
                        // Stage the primary reader on the data service first so the baseline path
                        // can populate alongside it before we trigger highlight refresh.
                        val dataService = CoverageDataService.getInstance(project)
                        dataService.setCoverageContext(result.resolved.commitHash, result.resolved.gitRoot, stale = false)
                        dataService.setCov4Reader(freshReader)
                        loadOrFetchBaseline(indicator, gitLabClient, cache, dual.baseline, baseProgress = 0.85)
                        applyCoverageFromReader(freshReader, result.resolved, result.artifactCount)
                    } else {
                        log.warn("Coverage: failed to reopen freshly-written .cov4, falling back to in-memory map")
                        applyCoverage(result)
                        loadOrFetchBaseline(indicator, gitLabClient, cache, dual.baseline, baseProgress = 0.85)
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
                    onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
                }
            }
        })
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
        val artifactJobs = jobs.filter { job ->
            job.status == "success" && job.artifactsFile != null
                && job.name.contains("behat", ignoreCase = true)
        }

        if (artifactJobs.isEmpty()) {
            throw CoverageApiException(
                buildString {
                    appendLine("No jobs with artifacts found in pipeline #${resolved.pipelineId}.")
                    appendLine()
                    appendLine("Pipeline has ${jobs.size} job(s): ${jobs.joinToString(", ") { "${it.name} (${it.status})" }}")
                    appendLine()
                    append("Make sure the CI pipeline produces downloadable artifacts.")
                },
                details = mapOf(
                    "pipelineId" to resolved.pipelineId.toString(),
                    "totalJobs" to jobs.size.toString(),
                    "jobNames" to jobs.joinToString(", ") { it.name },
                ),
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        log.info("Coverage: found ${artifactJobs.size} jobs with artifacts in pipeline ${resolved.pipelineId}")

        indicator.text = "Downloading coverage artifacts..."
        indicator.fraction = fractionStart + (fractionEnd - fractionStart) * 0.15

        val mergedCoverage = mutableMapOf<String, MutableMap<Int, MutableList<String>>>()
        val totalJobs = artifactJobs.size
        var covtCount = 0

        for ((index, job) in artifactJobs.withIndex()) {
            indicator.text = "Downloading artifact from job '${job.name}'... (${index + 1}/$totalJobs)"
            val span = fractionEnd - fractionStart
            indicator.fraction = fractionStart + span * (0.15 + 0.8 * (index.toDouble() / totalJobs))

            if (indicator.isCanceled) {
                throw CoverageApiException(
                    "Coverage loading was cancelled",
                    kind = CoverageErrorKind.NO_DATA,
                )
            }

            val zipBytes = gitLabClient.downloadJobArtifacts(settings.gitlabProjectId, job.id)
            log.info("Coverage: downloaded ${zipBytes.size} bytes from job ${job.name} (id=${job.id})")

            val parsed = BinaryCoverageParser.parseZipArtifact(zipBytes)
            if (parsed == null) {
                log.info("Coverage: no .covt file in artifact from job ${job.name}, skipping")
                continue
            }
            covtCount++
            log.info("Coverage: parsed ${parsed.size} files from job ${job.name}")

            for ((filePath, lineMap) in parsed) {
                val existingFileMap = mergedCoverage.getOrPut(filePath) { mutableMapOf() }
                for ((lineNum, testNames) in lineMap) {
                    val existingTests = existingFileMap.getOrPut(lineNum) { mutableListOf() }
                    for (testName in testNames) {
                        if (testName !in existingTests) {
                            existingTests.add(testName)
                        }
                    }
                }
            }
        }

        if (covtCount == 0) {
            throw CoverageApiException(
                buildString {
                    appendLine("No coverage data (.covt) found in any artifact of pipeline #${resolved.pipelineId}.")
                    appendLine()
                    appendLine("Downloaded artifacts from ${artifactJobs.size} job(s): ${artifactJobs.joinToString(", ") { it.name }}")
                    appendLine()
                    append("Make sure the CI jobs produce .covt or .covt.gz files in their artifacts.")
                },
                details = mapOf(
                    "pipelineId" to resolved.pipelineId.toString(),
                    "artifactJobs" to artifactJobs.size.toString(),
                ),
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        val finalCoverage: Map<String, Map<Int, List<String>>> = mergedCoverage.mapValues { (_, lineMap) ->
            lineMap.mapValues { (_, tests) -> tests.toList() }
        }

        return CoverageLoadResult(
            coverage = finalCoverage,
            resolved = resolved,
            artifactCount = covtCount,
        )
    }

    /**
     * Applies loaded coverage data to the project and refreshes highlights.
     * If the previous coverage was stale (offline-first cache), notifies that it's been refreshed.
     */
    fun applyCoverage(result: CoverageLoadResult) {
        val dataService = CoverageDataService.getInstance(project)
        val wasStale = dataService.isStale
        val previousCommit = dataService.coverageCommitHash

        dataService.setCoverageContext(result.resolved.commitHash, result.resolved.gitRoot, stale = false)
        dataService.setCoverageAll(result.coverage)

        log.info("Coverage: loaded coverage for ${result.coverage.size} files from ${result.artifactCount} coverage artifacts at commit ${result.resolved.commitHash}")

        ApplicationManager.getApplication().invokeLater {
            CoverageHighlighter.applyToOpenEditors(project)
            notifyIfFallback(result.resolved)
            notifyRefreshedFromStale(wasStale, previousCommit, result.resolved)
        }
    }

    /**
     * Reader-backed counterpart to [applyCoverage]. Used right after a fresh
     * download: the merged map is written to `.cov4`, reopened as a
     * [Cov4Reader], and handed to [CoverageDataService] so the in-memory map
     * can be garbage-collected.
     */
    private fun applyCoverageFromReader(reader: Cov4Reader, resolved: ResolvedPipeline, artifactCount: Int) {
        val dataService = CoverageDataService.getInstance(project)
        val wasStale = dataService.isStale
        val previousCommit = dataService.coverageCommitHash

        dataService.setCoverageContext(resolved.commitHash, resolved.gitRoot, stale = false)
        dataService.setCov4Reader(reader)

        log.info("Coverage: loaded coverage via reader for ${reader.allFilePaths.size} files from $artifactCount coverage artifacts at commit ${resolved.commitHash}")

        ApplicationManager.getApplication().invokeLater {
            CoverageHighlighter.applyToOpenEditors(project)
            notifyIfFallback(resolved)
            notifyRefreshedFromStale(wasStale, previousCommit, resolved)
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
     * [CoverageDataService]. Cache hits skip the download. Failures (missing artifacts,
     * network errors) are logged and the data service is left without a baseline so
     * single-coverage rendering remains functional.
     */
    private fun loadOrFetchBaseline(
        indicator: ProgressIndicator,
        gitLabClient: GitLabApiClient,
        cache: CoverageCacheService,
        baseline: ResolvedPipeline?,
        baseProgress: Double,
    ) {
        val dataService = CoverageDataService.getInstance(project)
        if (baseline == null) {
            dataService.clearBaseline()
            return
        }
        try {
            indicator.text = "Loading baseline coverage…"
            indicator.fraction = baseProgress

            val cached = cache.get(baseline.commitHash)
            if (cached != null) {
                cache.updateLastUsed(baseline.commitHash)
                dataService.setBaselineCov4Reader(cached, baseline.commitHash)
                log.info("Coverage: baseline loaded from cache (commit ${baseline.commitHash.take(8)})")
                indicator.fraction = 1.0
                return
            }

            log.info("Coverage: baseline cache miss — downloading artifacts for pipeline ${baseline.pipelineId}")
            val result = downloadArtifacts(indicator, gitLabClient, baseline,
                fractionStart = baseProgress,
                fractionEnd = 1.0,
            )
            cache.writeCov4(baseline.commitHash, baseline.pipelineId, result.coverage)
            val freshReader = cache.get(baseline.commitHash)
            if (freshReader != null) {
                dataService.setBaselineCov4Reader(freshReader, baseline.commitHash)
                log.info("Coverage: baseline downloaded and attached (commit ${baseline.commitHash.take(8)})")
            } else {
                log.warn("Coverage: failed to reopen freshly-written baseline .cov4")
                dataService.clearBaseline()
            }
        } catch (ex: CoverageApiException) {
            log.warn("Coverage: baseline load failed (${ex.kind}): ${ex.userMessage}")
            dataService.clearBaseline()
        } catch (ex: Exception) {
            log.warn("Coverage: baseline load failed: ${ex.message}", ex)
            dataService.clearBaseline()
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
                    cache.writeCov4(hash, pipelineId = 0L, coverage)

                    val gitRoot = findGitRootFromFile(file.parentFile) ?: findGitRoot()
                    val dataService = CoverageDataService.getInstance(project)

                    val reader = cache.get(hash)
                    if (reader != null) {
                        if (gitRoot != null) dataService.setCoverageContext(hash, gitRoot, stale = false)
                        dataService.setCov4Reader(reader)
                        cache.updateLastUsed(hash)
                    } else {
                        log.warn("Coverage: failed to reopen freshly-written .cov4 for local file, falling back to in-memory map")
                        if (gitRoot != null) dataService.setCoverageContext(hash, gitRoot, stale = false)
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

        // Persist the user's explicit choice so it survives IDE restarts.
        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = commitHash

        ApplicationManager.getApplication().executeOnPooledThread {
            val reader = cache.get(commitHash)
            if (reader == null) {
                log.warn("Coverage: loadFromCache — no .cov4 found for commit $commitHash")
                onComplete?.let { ApplicationManager.getApplication().invokeLater(it) }
                return@executeOnPooledThread
            }

            if (gitRoot != null) {
                dataService.setCoverageContext(commitHash, gitRoot, stale = false)
            } else {
                log.warn("Coverage: loadFromCache — could not determine git root; coverage context not updated")
            }
            cache.updateLastUsed(commitHash)
            dataService.setCov4Reader(reader)
            log.info("Coverage: loaded from cache for commit ${commitHash.take(8)} (${reader.allFilePaths.size} files)")

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
                "No coverage artifacts found for this commit."
            else -> ex.userMessage
        }
    }
}

data class CoverageLoadResult(
    val coverage: Map<String, Map<Int, List<String>>>,
    val resolved: ResolvedPipeline,
    val artifactCount: Int,
)
