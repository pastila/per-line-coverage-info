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

    enum class TestFilter { BOTH, MASTER_ONLY, FEATURE_ONLY }

    private val titleLabel = JBLabel("No line selected")
    private val testTree = TestTreeView(project)
    private var currentLineNumber: Int = 0
    private var currentFilePath: String = ""
    private var currentPrimary: List<String> = emptyList()
    private var currentBaseline: List<String> = emptyList()
    private var currentHasBaseline: Boolean = false
    private var currentFilter: TestFilter = TestFilter.BOTH

    init {
        val runAllAction = object : AnAction(
            "Run All", "Run all Behat tests in a single launch", AllIcons.Actions.RunAll
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
            "Debug all Behat tests in a single launch",
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

        val filterBoth = filterAction("Both", "Show tests from master and from this branch", TestFilter.BOTH)
        val filterMaster = filterAction("Master Only", "Show only tests that exist on master", TestFilter.MASTER_ONLY)
        val filterFeature = filterAction("New on This Branch", "Show only tests added on the current branch", TestFilter.FEATURE_ONLY)

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "CoveringLinePanelToolbar",
            DefaultActionGroup(
                runAllAction, runAllDebugAction,
                Separator.getInstance(),
                filterBoth, filterMaster, filterFeature,
            ),
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

    fun showTests(
        lineNumber: Int,
        filePath: String,
        primaryTests: List<String>,
        baselineTests: List<String> = emptyList(),
        hasBaseline: Boolean = false,
    ) {
        currentLineNumber = lineNumber
        currentFilePath = filePath
        currentPrimary = primaryTests
        currentBaseline = baselineTests
        currentHasBaseline = hasBaseline
        if (!hasBaseline && currentFilter != TestFilter.BOTH) {
            currentFilter = TestFilter.BOTH
        }
        refreshDisplay()
    }

    private fun refreshDisplay() {
        val displayed = computeDisplayedTests()
        val featureOnlyCount = if (currentHasBaseline) {
            CoverageDiff.featureOnly(currentPrimary, currentBaseline).size
        } else 0

        val fileName = currentFilePath.substringAfterLast("/")
        val dataService = CoverageDataService.getInstance(project)
        val commitInfo = buildCommitInfo(dataService)
        val newSuffix = if (featureOnlyCount > 0) " <span style='color:#5078d8'>[+$featureOnlyCount new]</span>" else ""

        titleLabel.text = if (currentPrimary.isEmpty()) {
            "<html><a style='text-decoration:underline'>$fileName:$currentLineNumber</a> — not covered$commitInfo</html>"
        } else {
            "<html><a style='text-decoration:underline'>$fileName:$currentLineNumber</a> — ${displayed.size} test(s)$newSuffix$commitInfo</html>"
        }

        testTree.setTests(displayed)
    }

    private fun computeDisplayedTests(): List<String> =
        computeDisplayed(currentPrimary, currentBaseline, currentHasBaseline, currentFilter)

    private fun filterAction(text: String, description: String, filter: TestFilter): ToggleAction {
        return object : ToggleAction(text, description, null) {
            override fun isSelected(e: AnActionEvent): Boolean = currentFilter == filter
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state && currentFilter != filter) {
                    currentFilter = filter
                    refreshDisplay()
                }
            }
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.isVisible = currentHasBaseline
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
    }

    private fun buildCommitInfo(dataService: CoverageDataService): String {
        val commitHash = dataService.coverageCommitHash ?: return ""
        val shortHash = commitHash.take(8)
        val staleMarker = if (dataService.isStale) " ⚠ stale" else ""
        return " <span style='color:gray;font-size:smaller'>(from $shortHash$staleMarker)</span>"
    }

    companion object {
        const val TAB_TITLE = "Covering Line"

        /**
         * Pure helper exposed for tests. Computes which test names should be shown given a filter.
         * MASTER_ONLY shows tests that exist on **both** sides (i.e. the line is still covered on
         * master); FEATURE_ONLY shows tests that exist only on the current branch.
         */
        @JvmStatic
        internal fun computeDisplayed(
            primary: List<String>,
            baseline: List<String>,
            hasBaseline: Boolean,
            filter: TestFilter,
        ): List<String> {
            if (!hasBaseline) return primary
            return when (filter) {
                TestFilter.BOTH -> CoverageDiff.union(primary, baseline)
                TestFilter.MASTER_ONLY -> primary.filter { it in baseline }
                TestFilter.FEATURE_ONLY -> CoverageDiff.featureOnly(primary, baseline)
            }
        }

        fun getInstance(project: Project): CoveringLinePanel? {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Coverage Tests") ?: return null
            val content = toolWindow.contentManager.findContent(TAB_TITLE) ?: return null
            return content.component as? CoveringLinePanel
        }

        fun showTestsInPanel(
            project: Project,
            lineNumber: Int,
            filePath: String,
            tests: List<String>,
            baselineTests: List<String> = emptyList(),
            hasBaseline: Boolean = false,
        ) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Coverage Tests") ?: return
            val content = toolWindow.contentManager.findContent(TAB_TITLE) ?: return
            toolWindow.contentManager.setSelectedContent(content)
            toolWindow.show {
                val panel = content.component as? CoveringLinePanel ?: return@show
                panel.showTests(lineNumber, filePath, tests, baselineTests, hasBaseline)
            }
        }
    }
}
