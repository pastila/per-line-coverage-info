package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-level parent for listeners the plugin attaches to platform objects
 * (documents, editors) that outlive the plugin itself.
 *
 * Disposed when the project closes and when the plugin is unloaded on a dynamic
 * update. Without it, listeners from the old plugin version stay attached to open
 * documents and call into services of the new version — a `ClassCastException`
 * between two `PluginClassLoader`s.
 */
@Service(Service.Level.PROJECT)
class CoveragePluginDisposable : Disposable {

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): CoveragePluginDisposable = project.service()
    }
}
