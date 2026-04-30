package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * Tool-window tab showing the tests that cover the line currently selected
 * in the editor (via [CoverageGutterRenderer] click).
 */
class CoveringLinePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel("No line selected")
    private val testTree = TestTreeView(project)
    private var currentLineNumber: Int = 0
    private var currentFilePath: String = ""

    init {
        val toggleViewAction = object : ToggleAction(
            "Tree View", "Toggle between tree and flat test list", AllIcons.Actions.GroupByPackage
        ) {
            override fun isSelected(e: AnActionEvent): Boolean = testTree.isTreeView
            override fun setSelected(e: AnActionEvent, state: Boolean) { testTree.isTreeView = state }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val runAllAction = object : AnAction(
            "Run All", "Run all Behat tests in a single launch via --paths", AllIcons.Actions.RunAll
        ) {
            override fun actionPerformed(e: AnActionEvent) = testTree.runAllBehatBundled(debug = false)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !testTree.runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    testTree.hasAnyBehatTest()
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val runAllDebugAction = object : AnAction(
            "Run All With Debug",
            "Debug all Behat tests in a single launch via --paths",
            AllIcons.Actions.StartDebugger
        ) {
            override fun actionPerformed(e: AnActionEvent) = testTree.runAllBehatBundled(debug = true)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !testTree.runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    testTree.hasAnyBehatTest()
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "CoveringLinePanelToolbar",
            DefaultActionGroup(toggleViewAction, Separator.getInstance(), runAllAction, runAllDebugAction),
            true
        )
        toolbar.targetComponent = this

        titleLabel.border = JBUI.Borders.empty(4, 6)
        titleLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        titleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                CoverageTestNavigator.navigateToSourceLine(project, currentFilePath, currentLineNumber)
            }
        })

        val top = JPanel(BorderLayout())
        top.add(toolbar.component, BorderLayout.WEST)
        top.add(titleLabel, BorderLayout.CENTER)

        add(top, BorderLayout.NORTH)
        add(testTree.component, BorderLayout.CENTER)

        testTree.setEmptyMessage("No tests covering this line")
    }

    fun showTests(lineNumber: Int, filePath: String, tests: List<String>) {
        currentLineNumber = lineNumber
        currentFilePath = filePath

        val fileName = filePath.substringAfterLast("/")
        val dataService = CoverageDataService.getInstance(project)
        val commitInfo = buildCommitInfo(dataService)

        titleLabel.text = if (tests.isEmpty()) {
            "<html><a style='text-decoration:underline'>$fileName:$lineNumber</a> — not covered$commitInfo</html>"
        } else {
            "<html><a style='text-decoration:underline'>$fileName:$lineNumber</a> — ${tests.size} test(s)$commitInfo</html>"
        }

        testTree.setTests(tests)
    }

    private fun buildCommitInfo(dataService: CoverageDataService): String {
        val commitHash = dataService.coverageCommitHash ?: return ""
        val shortHash = commitHash.take(8)
        val staleMarker = if (dataService.isStale) " ⚠ stale" else ""
        return " <span style='color:gray;font-size:smaller'>(from $shortHash$staleMarker)</span>"
    }

    companion object {
        const val TAB_TITLE = "Covering Line"

        fun getInstance(project: Project): CoveringLinePanel? {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Coverage Tests") ?: return null
            val content = toolWindow.contentManager.findContent(TAB_TITLE) ?: return null
            return content.component as? CoveringLinePanel
        }

        fun showTestsInPanel(project: Project, lineNumber: Int, filePath: String, tests: List<String>) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Coverage Tests") ?: return
            val content = toolWindow.contentManager.findContent(TAB_TITLE) ?: return
            toolWindow.contentManager.setSelectedContent(content)
            toolWindow.show {
                val panel = content.component as? CoveringLinePanel ?: return@show
                panel.showTests(lineNumber, filePath, tests)
            }
        }
    }
}
