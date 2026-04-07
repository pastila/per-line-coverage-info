package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import icons.BehatIcons
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

private sealed class TestNodeData(val displayName: String) {
    class BehatGroup(val featurePath: String) : TestNodeData(featurePath)
    class BehatScenario(val label: String, val originalTestName: String) : TestNodeData(label)
    class PhpUnitGroup(val className: String) : TestNodeData(className)
    class PhpUnitMethod(val methodName: String, val fullTestName: String) : TestNodeData(methodName)
}

class CoverageTestsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel("No line selected")
    private val rootNode = DefaultMutableTreeNode("Tests")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private var allTests: List<String> = emptyList()
    private var currentLineNumber: Int = 0
    private var currentFilePath: String = ""

    init {
        titleLabel.border = JBUI.Borders.empty(4, 6)
        titleLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        titleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                navigateToSourceLine()
            }
        })

        tree.isRootVisible = false
        tree.showsRootHandles = true

        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode
                when (val data = node?.userObject) {
                    is TestNodeData.BehatGroup -> {
                        icon = BehatIcons.Behat
                        append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    }
                    is TestNodeData.BehatScenario -> {
                        icon = BehatIcons.Behat
                        append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    is TestNodeData.PhpUnitGroup -> {
                        icon = AllIcons.Nodes.TestGroup
                        append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    }
                    is TestNodeData.PhpUnitMethod -> {
                        icon = AllIcons.Nodes.Test
                        append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    else -> append(node?.userObject?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        // Double-click to navigate to test method
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val testName = resolveFullTestName(node) ?: return
                    navigateToTest(testName)
                }
            }
        })

        // Context menu
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: java.awt.Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val testName = resolveFullTestName(node) ?: return

                val group = DefaultActionGroup().apply {
                    if (canRunTest(testName)) {
                        add(object : AnAction("Run Test") {
                            override fun actionPerformed(e: AnActionEvent) {
                                runTest(testName, debug = false)
                            }
                        })
                        add(object : AnAction("Debug Test") {
                            override fun actionPerformed(e: AnActionEvent) {
                                runTest(testName, debug = true)
                            }
                        })
                    }
                    add(object : AnAction("Go to Test") {
                        override fun actionPerformed(e: AnActionEvent) {
                            navigateToTest(testName)
                        }
                    })
                }
                val popupMenu = ActionManager.getInstance()
                    .createActionPopupMenu("CoverageTestsPanel", group)
                popupMenu.component.show(comp, x, y)
            }
        })

        add(titleLabel, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    fun showTests(lineNumber: Int, filePath: String, tests: List<String>) {
        currentLineNumber = lineNumber
        currentFilePath = filePath
        allTests = tests

        val fileName = filePath.substringAfterLast("/")
        val dataService = CoverageDataService.getInstance(project)
        val commitInfo = buildCommitInfo(dataService)

        if (tests.isEmpty()) {
            titleLabel.text = "<html><a style='text-decoration:underline'>$fileName:$lineNumber</a> — not covered$commitInfo</html>"
        } else {
            titleLabel.text = "<html><a style='text-decoration:underline'>$fileName:$lineNumber</a> — ${tests.size} test(s)$commitInfo</html>"
        }

        buildTree(tests)
    }

    private fun buildCommitInfo(dataService: CoverageDataService): String {
        val commitHash = dataService.coverageCommitHash ?: return ""
        val shortHash = commitHash.take(8)
        val staleMarker = if (dataService.isStale) " ⚠ stale" else ""
        return " <span style='color:gray;font-size:smaller'>(from $shortHash$staleMarker)</span>"
    }

    private fun buildTree(tests: List<String>) {
        rootNode.removeAllChildren()

        if (tests.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No tests covering this line"))
            treeModel.reload()
            return
        }

        val (behatTests, phpunitTests) = tests.partition { isBehatTest(it) }

        // Group Behat tests by feature file path
        if (behatTests.isNotEmpty()) {
            val behatGrouped = behatTests.groupBy { name ->
                name.substringBeforeLast(":", name)
            }
            for ((featurePath, entries) in behatGrouped.toSortedMap()) {
                val fileNode = DefaultMutableTreeNode(TestNodeData.BehatGroup(featurePath))
                for (entry in entries) {
                    val line = entry.substringAfterLast(":", "")
                    val scenarioLabel = resolveScenarioName(featurePath, line) ?: "line $line"
                    fileNode.add(DefaultMutableTreeNode(TestNodeData.BehatScenario(scenarioLabel, entry)))
                }
                rootNode.add(fileNode)
            }
        }

        // Group PHPUnit tests by class (part before ::)
        if (phpunitTests.isNotEmpty()) {
            val grouped = phpunitTests.groupBy { name ->
                val fqcn = name.substringBeforeLast("::", "")
                fqcn.ifEmpty { "(no class)" }
            }
            for ((className, methods) in grouped.toSortedMap()) {
                val classNode = DefaultMutableTreeNode(TestNodeData.PhpUnitGroup(className))
                for (fullName in methods) {
                    val methodName = fullName.substringAfterLast("::", fullName)
                    classNode.add(DefaultMutableTreeNode(TestNodeData.PhpUnitMethod(methodName, fullName)))
                }
                rootNode.add(classNode)
            }
        }

        treeModel.reload()
        // Expand all nodes
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }

    /** Resolves a tree node back to the full test name. */
    private fun resolveFullTestName(node: DefaultMutableTreeNode): String? {
        return when (val data = node.userObject) {
            is TestNodeData.BehatScenario -> data.originalTestName
            is TestNodeData.BehatGroup -> data.featurePath
            is TestNodeData.PhpUnitMethod -> data.fullTestName
            is TestNodeData.PhpUnitGroup -> data.className
            else -> null
        }
    }

    private fun navigateToSourceLine() {
        if (currentFilePath.isEmpty()) return
        val projectDir = project.guessProjectDir() ?: return
        val vf = VfsUtil.findRelativeFile(currentFilePath, projectDir) ?: return
        val line = if (currentLineNumber > 0) currentLineNumber - 1 else 0
        FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, vf, line, 0), true)
    }

    private fun navigateToTest(testName: String) {
        if (isBehatTest(testName)) {
            navigateToBehatTest(testName)
            return
        }

        val methodName = testName.substringAfterLast("::", testName).substringAfterLast("\\", testName)
        val className = testName.substringBeforeLast("::", "").substringAfterLast("\\", "")

        if (className.isNotEmpty()) {
            @Suppress("DEPRECATION")
            val files = FilenameIndex.getFilesByName(project, "$className.php", GlobalSearchScope.projectScope(project))
            if (files.isNotEmpty()) {
                val psiFile = files.first()
                val vf = psiFile.virtualFile ?: return
                val document = psiFile.viewProvider.document ?: return
                val text = document.text
                val methodPattern = "function $methodName"
                val offset = text.indexOf(methodPattern)
                if (offset >= 0) {
                    FileEditorManager.getInstance(project)
                        .openTextEditor(OpenFileDescriptor(project, vf, offset), true)
                } else {
                    FileEditorManager.getInstance(project)
                        .openTextEditor(OpenFileDescriptor(project, vf, 0), true)
                }
                return
            }
        }
    }

    private fun navigateToBehatTest(testName: String) {
        val featurePath = testName.substringBeforeLast(":", "")
        val lineStr = testName.substringAfterLast(":", "")
        val lineNumber = lineStr.toIntOrNull() ?: 0

        val projectDir = project.guessProjectDir() ?: return
        val vf = VfsUtil.findRelativeFile(featurePath, projectDir) ?: return

        val line = if (lineNumber > 0) lineNumber - 1 else 0
        FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, vf, line, 0), true)
    }

    private fun runTest(testName: String, debug: Boolean = false) {
        if (isBehatTest(testName)) {
            runBehatTest(testName, debug)
        } else {
            // For PHPUnit, fall back to navigation for now
            navigateToTest(testName)
        }
    }

    private fun runBehatTest(testName: String, debug: Boolean = false) {
        if (!BehatTestRunner.isAvailable()) {
            navigateToBehatTest(testName)
            return
        }

        val featurePath = testName.substringBeforeLast(":", "")
        val lineStr = testName.substringAfterLast(":", "")
        val lineNumber = lineStr.toIntOrNull()

        val projectDir = project.guessProjectDir() ?: return
        val vf = VfsUtil.findRelativeFile(featurePath, projectDir) ?: return
        val absolutePath = vf.path

        if (lineNumber != null) {
            // Resolve scenario name from the .feature file PSI
            val scenarioName = ReadAction.compute<String?, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(vf) as? GherkinFile ?: return@compute null
                findScenarioAtLine(psiFile, lineNumber)
            }
            if (scenarioName != null) {
                BehatTestRunner.runScenario(project, absolutePath, scenarioName, debug)
            } else {
                BehatTestRunner.runFeatureFile(project, absolutePath, debug)
            }
        } else {
            BehatTestRunner.runFeatureFile(project, absolutePath, debug)
        }
    }

    private fun canRunTest(testName: String): Boolean {
        return if (isBehatTest(testName)) {
            BehatTestRunner.isAvailable()
        } else {
            false // PHPUnit run not yet implemented
        }
    }

    /** Finds the scenario name at a given 1-based line number in a Gherkin file. */
    private fun findScenarioAtLine(gherkinFile: GherkinFile, lineNumber: Int): String? {
        val document = gherkinFile.viewProvider.document ?: return null
        val features = gherkinFile.features
        for (feature in features) {
            for (scenario in feature.scenarios) {
                if (scenario is GherkinStepsHolder) {
                    val scenarioLine = document.getLineNumber(scenario.textOffset) + 1
                    if (scenarioLine == lineNumber) {
                        return scenario.scenarioName
                    }
                }
            }
        }
        // Fallback: find the closest scenario at or before the line
        var closest: GherkinStepsHolder? = null
        for (feature in features) {
            for (scenario in feature.scenarios) {
                if (scenario is GherkinStepsHolder) {
                    val scenarioLine = document.getLineNumber(scenario.textOffset) + 1
                    if (scenarioLine <= lineNumber) {
                        closest = scenario
                    }
                }
            }
        }
        return closest?.scenarioName
    }

    /** Resolves a scenario name from a feature file path and line number string. */
    private fun resolveScenarioName(featurePath: String, lineStr: String): String? {
        val lineNumber = lineStr.toIntOrNull() ?: return null
        val projectDir = project.guessProjectDir() ?: return null
        val vf = VfsUtil.findRelativeFile(featurePath, projectDir) ?: return null
        return ReadAction.compute<String?, Throwable> {
            val psiFile = PsiManager.getInstance(project).findFile(vf) as? GherkinFile ?: return@compute null
            findScenarioAtLine(psiFile, lineNumber)
        }
    }

    companion object {
        /** Detects whether a test name is a Behat test (feature file path + line). */
        private val BEHAT_TEST_PATTERN = Regex("""^.+\.feature:\d+$""")

        fun isBehatTest(testName: String): Boolean = BEHAT_TEST_PATTERN.matches(testName)

        fun getInstance(project: Project): CoverageTestsPanel? {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Coverage Tests") ?: return null
            val content = toolWindow.contentManager.getContent(0) ?: return null
            return content.component as? CoverageTestsPanel
        }

        fun showTestsInPanel(project: Project, lineNumber: Int, filePath: String, tests: List<String>) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Coverage Tests") ?: return
            toolWindow.show {
                val panel = getInstance(project) ?: return@show
                panel.showTests(lineNumber, filePath, tests)
            }
        }
    }
}
