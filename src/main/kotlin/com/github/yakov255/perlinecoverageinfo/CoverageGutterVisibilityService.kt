package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level persistent toggle controlling whether coverage gutter
 * highlights are shown. Survives IDE restarts via workspace.xml.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "CoverageGutterVisibility",
    storages = [Storage("coverageGutterVisibility.xml")]
)
class CoverageGutterVisibilityService : PersistentStateComponent<CoverageGutterVisibilityService.State> {

    data class State(var visible: Boolean = true)

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var visible: Boolean
        get() = state.visible
        set(value) { state.visible = value }

    companion object {
        fun getInstance(project: Project): CoverageGutterVisibilityService = project.service()
    }
}
