package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level state for the GitHub-based update checker.
 *
 *  - [dontCheckAgain]    user opted out of update notifications
 *  - [lastCheckedAtMs]   timestamp of the last GitHub Releases API call (throttling)
 *  - [lastSeenVersion]   tag of the latest release the user was already notified about,
 *                        so we don't notify twice for the same version
 */
@State(
    name = "GitHubUpdateCheckSettings",
    storages = [Storage("coverageGitHubUpdateSettings.xml")]
)
@Service(Service.Level.APP)
class GitHubUpdateCheckSettings : PersistentStateComponent<GitHubUpdateCheckSettings.State> {

    data class State(
        @Volatile var dontCheckAgain: Boolean = false,
        @Volatile var lastCheckedAtMs: Long = 0L,
        @Volatile var lastSeenVersion: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var dontCheckAgain: Boolean
        get() = state.dontCheckAgain
        set(value) { state.dontCheckAgain = value }

    var lastCheckedAtMs: Long
        get() = state.lastCheckedAtMs
        set(value) { state.lastCheckedAtMs = value }

    var lastSeenVersion: String
        get() = state.lastSeenVersion
        set(value) { state.lastSeenVersion = value }

    companion object {
        fun getInstance(): GitHubUpdateCheckSettings = service()
    }
}
