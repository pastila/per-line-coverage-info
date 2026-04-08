package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener

/**
 * Listens for VCS branch changes and automatically reloads coverage data.
 * Uses offline-first approach: shows cached coverage immediately, then fetches fresh data.
 */
class CoverageBranchListener(private val project: Project) : BranchChangeListener {

    private val log = CoverageLog.get(CoverageBranchListener::class.java)

    override fun branchWillChange(branchName: String) {
        // Don't clear coverage — offline-first will replace it with cached data
        log.info("Coverage: branch changing to '$branchName'")
    }

    override fun branchHasChanged(branchName: String) {
        log.info("Coverage: branch changed to '$branchName', loading coverage (offline-first)")
        val loadService = CoverageLoadService.getInstance(project)
        if (loadService.validateSettings() == null) {
            loadService.loadOfflineFirst()
        }
    }
}
