package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener

/**
 * Listens for VCS branch changes and automatically reloads coverage data.
 * Clears stale highlights immediately, then triggers a background reload from GitLab.
 */
class CoverageBranchListener(private val project: Project) : BranchChangeListener {

    private val log = Logger.getInstance(CoverageBranchListener::class.java)

    override fun branchWillChange(branchName: String) {
        // Clear stale coverage before the branch switch completes
        val dataService = CoverageDataService.getInstance(project)
        if (dataService.hasData()) {
            log.info("Coverage: branch changing to '$branchName', clearing stale coverage")
            dataService.clear()
            CoverageHighlighter.clearAllEditors(project)
        }
    }

    override fun branchHasChanged(branchName: String) {
        log.info("Coverage: branch changed to '$branchName', reloading coverage")
        val loadService = CoverageLoadService.getInstance(project)
        if (loadService.validateSettings() == null) {
            loadService.loadFromGitLab(showErrors = false)
        }
    }
}
