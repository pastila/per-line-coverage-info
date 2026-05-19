package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings for the MCP server (port and enabled flag).
 *
 * Replaces the former per-project `CoverageMcpSettings`. Existing per-project
 * settings stored in `coverageMcpSettings.xml` will not be automatically
 * migrated — users need to re-enable MCP and re-set the port in
 * Settings → Tools → GitLab Coverage → MCP Server.
 */
@State(
    name = "CoverageMcpAppSettings",
    storages = [Storage("coverageMcpAppSettings.xml")]
)
@Service(Service.Level.APP)
class CoverageMcpAppSettings : PersistentStateComponent<CoverageMcpAppSettings.State> {

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
        fun getInstance(): CoverageMcpAppSettings = service()
    }
}
