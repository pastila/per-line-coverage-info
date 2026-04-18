package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level persistent state tracking the user's explicit artifact selection.
 *
 * When the user manually loads a specific cached artifact (via "Load Selected" or
 * "Load This Coverage"), its commit hash is saved here. On subsequent startups
 * [CoverageLoadService.loadOfflineFirst] loads this artifact instead of
 * auto-resolving the best match from git history.
 *
 * The pin is cleared when the user triggers "Fetch Coverage" ([CoverageLoadService.loadFromGitLab]),
 * indicating they want fresh auto-resolved data again. It is also cleared if the
 * pinned artifact has been deleted from the cache.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "CoverageUserSelection",
    storages = [Storage("coverageUserSelection.xml")]
)
class CoverageUserSelectionService : PersistentStateComponent<CoverageUserSelectionService.State> {

    data class State(var pinnedCommitHash: String? = null)

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /** Commit hash pinned by the user, or null if no explicit selection has been made. */
    var pinnedCommitHash: String?
        get() = state.pinnedCommitHash
        set(value) { state.pinnedCommitHash = value }

    companion object {
        fun getInstance(project: Project): CoverageUserSelectionService = project.service()
    }
}
