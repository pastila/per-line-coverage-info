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
        var gitlabDomain: String = "gitlab.com",
        var bearerToken: String = "",
        var gitlabProjectId: Long = 0,
        var gitlabProjectName: String = "",
        var coverageBranch: String = "behat-run-necessary-tests",
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

    val gitlabBaseUrl: String
        get() = "https://${state.gitlabDomain.trimEnd('/')}"

    companion object {
        fun getInstance(): CoverageApiSettings = service()
    }
}