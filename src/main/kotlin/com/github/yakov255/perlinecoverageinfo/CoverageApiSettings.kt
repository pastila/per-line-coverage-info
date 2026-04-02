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
        var apiUrl: String = "https://coverage.yakov255.ru/",
        var bearerToken: String = "4f47a77e3a9d9478611535ee804718f0a73fff0179751d3d1131814ce4252d18",
        var mergeBaseBranch: String = "behat-run-necessary-tests",
        /** Branch name sent to the coverage API. Leave empty to use the same value as mergeBaseBranch. */
        var apiBranchName: String = "master"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var apiUrl: String
        get() = state.apiUrl
        set(value) { state.apiUrl = value }

    var bearerToken: String
        get() = state.bearerToken
        set(value) { state.bearerToken = value }

    var mergeBaseBranch: String
        get() = state.mergeBaseBranch
        set(value) { state.mergeBaseBranch = value }

    /**
     * Branch name used in API calls (e.g. "master" when the coverage server stores
     * data under master regardless of which branch actually ran the tests).
     * Falls back to [mergeBaseBranch] when empty.
     */
    var apiBranchName: String
        get() = state.apiBranchName
        set(value) { state.apiBranchName = value }

    /** The branch name to pass to the coverage API. */
    val effectiveApiBranch: String
        get() = state.apiBranchName.trim().ifEmpty { state.mergeBaseBranch }

    companion object {
        fun getInstance(): CoverageApiSettings = service()
    }
}