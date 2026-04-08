package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CoverageTestsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val contentManager = toolWindow.contentManager

        val testsPanel = CoverageTestsPanel(project)
        val testsContent = contentFactory.createContent(testsPanel, "Tests", false).apply {
            isCloseable = false
        }
        contentManager.addContent(testsContent)

        val logPanel = CoverageLogPanel(project)
        val logContent = contentFactory.createContent(logPanel, "Log", false).apply {
            isCloseable = false
        }
        Disposer.register(logContent, logPanel)
        contentManager.addContent(logContent)
    }
}
