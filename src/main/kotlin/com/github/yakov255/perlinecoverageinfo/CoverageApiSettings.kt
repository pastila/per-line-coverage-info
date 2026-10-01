package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "CoverageApiSettings",
    storages = [Storage("coverageApiSettings.xml")]
)
@Service
class CoverageApiSettings : PersistentStateComponent<CoverageApiSettings.State> {

    data class State(
        var gitlabDomain: String = "raketa.dev",
        var bearerToken: String = "",
        var gitlabProjectId: Long = 335,
        var gitlabProjectName: String = "raketa/raketa",
        var coverageBranch: String = "master",
        /** Whether the plugin is enabled for this IDE installation. */
        var enabled: Boolean = false,
        /** Whether the first-run token prompt has been shown. */
        var tokenPrompted: Boolean = false,
        /** Git-root-relative directory watched for locally generated `.covt` files. */
        var localCoverageDir: String = "storage/coverage",
        /** Whether Behat runs started from the IDE write a `.covt` into [localCoverageDir]. */
        var collectLocalCoverage: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var gitlabDomain: String
        get() = state.gitlabDomain
        set(value) { state.gitlabDomain = value }

    var bearerToken: String
        get() = state.bearerToken
        set(value) { state.bearerToken = value }

    var gitlabProjectId: Long
        get() = state.gitlabProjectId
        set(value) { state.gitlabProjectId = value }

    var gitlabProjectName: String
        get() = state.gitlabProjectName
        set(value) { state.gitlabProjectName = value }

    var coverageBranch: String
        get() = state.coverageBranch
        set(value) { state.coverageBranch = value }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var tokenPrompted: Boolean
        get() = state.tokenPrompted
        set(value) { state.tokenPrompted = value }

    var localCoverageDir: String
        get() = state.localCoverageDir
        set(value) { state.localCoverageDir = value }

    var collectLocalCoverage: Boolean
        get() = state.collectLocalCoverage
        set(value) { state.collectLocalCoverage = value }

    val gitlabBaseUrl: String
        get() = "https://${state.gitlabDomain.trimEnd('/')}"

    companion object {
        fun getInstance(): CoverageApiSettings = service()
    }
}