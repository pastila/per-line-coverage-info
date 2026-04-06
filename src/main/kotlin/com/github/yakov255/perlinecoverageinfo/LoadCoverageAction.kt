package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

class LoadCoverageAction : AnAction() {

    private val log = Logger.getInstance(LoadCoverageAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = CoverageApiSettings.getInstance()

        if (settings.gitlabDomain.isBlank()) {
            Messages.showErrorDialog(project, "GitLab domain is not configured.\nPlease set it in Settings > Tools > GitLab Coverage.", "Configuration Error")
            return
        }
        if (settings.bearerToken.isBlank()) {
            Messages.showErrorDialog(project, "GitLab access token is not configured.\nPlease set it in Settings > Tools > GitLab Coverage.", "Configuration Error")
            return
        }
        if (settings.gitlabProjectId <= 0) {
            Messages.showErrorDialog(project, "GitLab project is not selected.\nPlease select a project in Settings > Tools > GitLab Coverage.", "Configuration Error")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Coverage from GitLab", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Resolving coverage pipeline..."
                    indicator.fraction = 0.0

                    val gitLabClient = GitLabApiClient(settings.gitlabBaseUrl, settings.bearerToken)
                    val resolver = CoverageResolver(gitLabClient, project)
                    val resolved = resolver.resolve()

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

                        if (indicator.isCanceled) return

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

                    indicator.text = "Applying coverage highlights..."
                    indicator.fraction = 0.9

                    val dataService = CoverageDataService.getInstance(project)
                    dataService.setCoverageContext(resolved.commitHash, resolved.gitRoot)
                    dataService.setCoverageAll(finalCoverage)

                    log.info("Coverage: loaded coverage for ${finalCoverage.size} files from $covtCount coverage artifacts at commit ${resolved.commitHash}")

                    ApplicationManager.getApplication().invokeLater {
                        CoverageHighlighter.applyToOpenEditors(project)
                    }

                    indicator.fraction = 1.0

                } catch (ex: CoverageApiException) {
                    log.warn("Coverage error", ex)
                    val title = when (ex.kind) {
                        CoverageErrorKind.NETWORK        -> "Connection Error"
                        CoverageErrorKind.GITLAB_API     -> "GitLab API Error"
                        CoverageErrorKind.PARSE          -> "Response Error"
                        CoverageErrorKind.ARTIFACT_PARSE -> "Coverage Artifact Error"
                        CoverageErrorKind.GIT            -> "Git Error"
                        CoverageErrorKind.NO_DATA        -> "No Coverage Data"
                        CoverageErrorKind.PROJECT_SETUP  -> "Configuration Error"
                    }
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, ex.userMessage, title)
                    }
                } catch (ex: Exception) {
                    log.warn("Unexpected coverage error", ex)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "An unexpected error occurred while loading coverage data:\n${ex.message}",
                            "Coverage Error"
                        )
                    }
                }
            }
        })
    }
}
