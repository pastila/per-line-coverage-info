package com.github.yakov255.perlinecoverageinfo

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Project-level service that orchestrates loading coverage data from GitLab.
 * Used by LoadCoverageAction (manual) and CoverageBranchListener (automatic).
 */
@Service(Service.Level.PROJECT)
class CoverageLoadService(private val project: Project) {

    private val log = Logger.getInstance(CoverageLoadService::class.java)

    /**
     * Validates that GitLab settings are configured.
     * Returns an error message if invalid, null if OK.
     */
    fun validateSettings(): String? {
        val settings = CoverageApiSettings.getInstance()
        return when {
            settings.gitlabDomain.isBlank() -> "GitLab domain is not configured.\nPlease set it in Settings > Tools > GitLab Coverage."
            settings.bearerToken.isBlank() -> "GitLab access token is not configured.\nPlease set it in Settings > Tools > GitLab Coverage."
            settings.gitlabProjectId <= 0 -> "GitLab project is not selected.\nPlease select a project in Settings > Tools > GitLab Coverage."
            else -> null
        }
    }

    /**
     * Loads coverage from GitLab in a background task with progress indicator.
     * @param showErrors If true, shows error dialogs on failure. If false (auto-trigger), logs errors silently.
     */
    fun loadFromGitLab(showErrors: Boolean = true) {
        val validationError = validateSettings()
        if (validationError != null) {
            if (showErrors) {
                Messages.showErrorDialog(project, validationError, "Configuration Error")
            } else {
                log.info("Coverage auto-load skipped: $validationError")
            }
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Coverage from GitLab", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // Run cache cleanup in the background
                    CoverageCacheService.getInstance(project).cleanup()

                    indicator.text = "Resolving coverage pipeline..."
                    indicator.fraction = 0.0

                    val settings = CoverageApiSettings.getInstance()
                    val gitLabClient = GitLabApiClient(settings.gitlabBaseUrl, settings.bearerToken)
                    val resolver = CoverageResolver(gitLabClient, project)
                    val resolved = resolver.resolve()

                    // Check disk cache by commit hash
                    val cache = CoverageCacheService.getInstance(project)
                    val reader = cache.get(resolved.commitHash)
                    if (reader != null) {
                        log.info("Coverage: loaded from COV4 cache (commit ${resolved.commitHash})")
                        val dataService = CoverageDataService.getInstance(project)
                        dataService.setCoverageContext(resolved.commitHash, resolved.gitRoot)
                        dataService.setCov4Reader(reader)

                        ApplicationManager.getApplication().invokeLater {
                            CoverageHighlighter.applyToOpenEditors(project)
                            notifyIfFallback(resolved)
                        }
                        return
                    }

                    val result = downloadArtifacts(indicator, gitLabClient, resolved)

                    // Write COV4 to cache
                    cache.writeCov4(resolved.commitHash, resolved.pipelineId, result.coverage)

                    applyCoverage(result)
                } catch (ex: CoverageApiException) {
                    log.warn("Coverage error", ex)
                    if (showErrors) {
                        val title = errorTitle(ex.kind)
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, ex.userMessage, title)
                        }
                    }
                } catch (ex: Exception) {
                    log.warn("Unexpected coverage error", ex)
                    if (showErrors) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "An unexpected error occurred while loading coverage data:\n${ex.message}",
                                "Coverage Error"
                            )
                        }
                    }
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
    ): CoverageLoadResult {
        val settings = CoverageApiSettings.getInstance()

        indicator.text = "Finding coverage artifacts..."
        indicator.fraction = 0.2

        val jobs = gitLabClient.listPipelineJobs(settings.gitlabProjectId, resolved.pipelineId)
        val artifactJobs = jobs.filter { job ->
            job.status == "success" && job.artifactsFile != null
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
        indicator.fraction = 0.3

        val mergedCoverage = mutableMapOf<String, MutableMap<Int, MutableList<String>>>()
        val totalJobs = artifactJobs.size
        var covtCount = 0

        for ((index, job) in artifactJobs.withIndex()) {
            indicator.text = "Downloading artifact from job '${job.name}'... (${index + 1}/$totalJobs)"
            indicator.fraction = 0.3 + 0.5 * (index.toDouble() / totalJobs)

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
     */
    fun applyCoverage(result: CoverageLoadResult) {
        val dataService = CoverageDataService.getInstance(project)
        dataService.setCoverageContext(result.resolved.commitHash, result.resolved.gitRoot)
        dataService.setCoverageAll(result.coverage)

        log.info("Coverage: loaded coverage for ${result.coverage.size} files from ${result.artifactCount} coverage artifacts at commit ${result.resolved.commitHash}")

        ApplicationManager.getApplication().invokeLater {
            CoverageHighlighter.applyToOpenEditors(project)
            notifyIfFallback(result.resolved)
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
    }
}

data class CoverageLoadResult(
    val coverage: Map<String, Map<Int, List<String>>>,
    val resolved: ResolvedPipeline,
    val artifactCount: Int,
)
