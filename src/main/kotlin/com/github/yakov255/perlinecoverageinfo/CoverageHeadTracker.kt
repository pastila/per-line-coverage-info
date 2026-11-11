package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Listens for any Git repository change (HEAD move, branch switch, pull, rebase, etc.) and
 * triggers an offline-first coverage reload when the HEAD revision changes.
 *
 * The state and the debounced reload live in [CoverageHeadMonitor], which the pipeline poller
 * shares: only the repository the coverage belongs to can move the coverage state (submodules and
 * sibling repositories are ignored), and a change the poller noticed first is not loaded twice.
 */
class CoverageHeadTracker(private val project: Project) : GitRepositoryChangeListener {

    override fun repositoryChanged(repository: GitRepository) {
        CoverageHeadMonitor.getInstance(project).onRepositoryChanged(repository)
    }
}
