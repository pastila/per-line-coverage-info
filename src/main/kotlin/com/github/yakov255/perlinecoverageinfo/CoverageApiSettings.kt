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
        var bearerToken: String = ""
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

    companion object {
        fun getInstance(): CoverageApiSettings = service()
    }
}