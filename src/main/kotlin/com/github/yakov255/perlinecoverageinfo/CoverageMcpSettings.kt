package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "CoverageMcpSettings",
    storages = [Storage("coverageMcpSettings.xml")]
)
@Service(Service.Level.PROJECT)
class CoverageMcpSettings : PersistentStateComponent<CoverageMcpSettings.State> {

    data class State(
        var mcpPort: Int = 17178,
        var mcpEnabled: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var mcpPort: Int
        get() = state.mcpPort
        set(value) { state.mcpPort = value }

    var mcpEnabled: Boolean
        get() = state.mcpEnabled
        set(value) { state.mcpEnabled = value }

    companion object {
        fun getInstance(project: Project): CoverageMcpSettings = project.service()
    }
}
