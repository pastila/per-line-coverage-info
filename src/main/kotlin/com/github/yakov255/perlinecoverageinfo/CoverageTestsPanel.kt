package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import icons.BehatIcons
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class CoverageTestsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = CoverageLog.get(CoverageTestsPanel::class.java)

    // ── Normal mode (per-line view) ─────────────────────────────────
    private val titleLabel = JBLabel("No line selected")
    private val rootNode = DefaultMutableTreeNode("Tests")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private var allTests: List<String> = emptyList()
    private var currentLineNumber: Int = 0
    private var currentFilePath: String = ""
    @Volatile private var runAllInProgress: Boolean = false
    private var isTestsTreeView = false

    // ── Affected mode ───────────────────────────────────────────────
    private var affectedModel: AffectedTestsModel? = null
    private var lastDiffMode: ChangedLinesAnalyzer.DiffMode? = null

    private val affectedFilesPane: AffectedFilesPane = AffectedFilesPane(project, ::onAffectedFilesCheckedChanged)

    private fun onAffectedFilesCheckedChanged() {
        val model = affectedModel ?: return
        affectedFilesPane.syncModelFromTree(model)
        updateTestsFromModel()
    }

    private val affectedStatusLabel = JBLabel("")

    // Layout
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val treeScrollPane = JBScrollPane(tree)
    private val normalPanel = JPanel(BorderLayout())
    private val affectedPanel = JPanel(BorderLayout())
    private val splitter = OnePixelSplitter(false, 0.35f)
    private val affectedToolbarRef: ActionToolbar

    init {
        // ── Shared test-tree setup ──────────────────────────────────
        tree.isRootVisible = false
        tree.showsRootHandles = true

        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode
                when (val data = node?.userObject) {
                    is TestNodeData.Dir -> {
                        icon = AllIcons.Nodes.Folder
                        append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        if (data.count > 0) append(" (${data.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is TestNodeData.BehatGroup -> {
                        icon = BehatIcons.Behat
                        append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        val count = node?.childCount ?: 0
                        if (count > 0) append(" ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is TestNodeData.BehatScenario -> {
                        icon = BehatIcons.Behat
                        append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    is TestNodeData.PhpUnitGroup -> {
                        icon = AllIcons.Nodes.TestGroup
                        append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        val count = node?.childCount ?: 0
                        if (count > 0) append(" ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is TestNodeData.PhpUnitMethod -> {
                        icon = AllIcons.Nodes.Test
                        append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    else -> append(node?.userObject?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        // Double-click to navigate to test
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val testName = resolveFullTestName(node) ?: return
                    CoverageTestNavigator.navigateToTest(project, testName)
                }
            }
        })

        // Context menu on test tree
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: java.awt.Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val testName = resolveFullTestName(node) ?: return

                val menu = javax.swing.JPopupMenu()
                if (canRunTest(testName)) {
                    menu.add(javax.swing.JMenuItem("Run Test", AllIcons.Actions.Execute).apply {
                        addActionListener { runTest(testName, debug = false) }
                    })
                    menu.add(javax.swing.JMenuItem("Debug Test", AllIcons.Actions.StartDebugger).apply {
                        addActionListener { runTest(testName, debug = true) }
                    })
                    menu.addSeparator()
                }
                menu.add(javax.swing.JMenuItem("Go to Test").apply {
                    addActionListener { CoverageTestNavigator.navigateToTest(project, testName) }
                })
                menu.show(comp, x, y)
            }
        })

        // ── Actions ─────────────────────────────────────────────────
        val findHeadAction = object : AnAction(
            "Find HEAD", "Find tests affected by committed changes", AllIcons.Vcs.Branch
        ) {
            override fun actionPerformed(e: AnActionEvent) =
                findAffectedTests(ChangedLinesAnalyzer.DiffMode.COMMITTED)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = CoverageDataService.getInstance(project).hasData()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val findWtAction = object : AnAction(
            "Find Working Tree", "Find tests affected by all local changes", AllIcons.Vcs.Changelist
        ) {
            override fun actionPerformed(e: AnActionEvent) =
                findAffectedTests(ChangedLinesAnalyzer.DiffMode.WORKING_TREE)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = CoverageDataService.getInstance(project).hasData()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val refreshAction = object : AnAction(
            "Refresh", "Re-compute affected tests", AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val mode = lastDiffMode ?: return
                findAffectedTests(mode)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = lastDiffMode != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val runAllAction = object : AnAction(
            "Run All",
            "Run all Behat tests in a single launch via --paths",
            AllIcons.Actions.RunAll
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                runAllBehatBundled(debug = false)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    allTests.any { isBehatTest(it) }
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val runAllDebugAction = object : AnAction(
            "Run All With Debug",
            "Debug all Behat tests in a single launch via --paths",
            AllIcons.Actions.StartDebugger
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                runAllBehatBundled(debug = true)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    allTests.any { isBehatTest(it) }
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val removeSelectedAction = object : AnAction(
            "Remove Selected", "Remove selected tests from the list", AllIcons.General.Remove
        ) {
            override fun actionPerformed(e: AnActionEvent) = removeSelectedTests()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = affectedModel != null && tree.selectionCount > 0
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val backAction = object : AnAction(
            "Back", "Return to per-line view", AllIcons.Actions.Back
        ) {
            override fun actionPerformed(e: AnActionEvent) = goBack()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val toggleTestsViewAction = object : ToggleAction(
            "Tree View", "Toggle between tree and flat test list", AllIcons.Actions.GroupByPackage
        ) {
            override fun isSelected(e: AnActionEvent): Boolean = isTestsTreeView
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                isTestsTreeView = state
                buildTree(allTests)
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        // ── Normal mode panel ───────────────────────────────────────
        titleLabel.border = JBUI.Borders.empty(4, 6)
        titleLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        titleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                CoverageTestNavigator.navigateToSourceLine(project, currentFilePath, currentLineNumber)
            }
        })

        val normalToolbar = ActionManager.getInstance().createActionToolbar(
            "CoverageTestsPanelToolbar",
            DefaultActionGroup(toggleTestsViewAction, Separator.getInstance(), runAllAction, runAllDebugAction, Separator.getInstance(), findHeadAction, findWtAction),
            true
        )
        normalToolbar.targetComponent = this

        val normalTop = JPanel(BorderLayout())
        normalTop.add(normalToolbar.component, BorderLayout.WEST)
        normalTop.add(titleLabel, BorderLayout.CENTER)
        normalPanel.add(normalTop, BorderLayout.NORTH)

        // ── Affected mode panel ─────────────────────────────────────
        affectedToolbarRef = ActionManager.getInstance().createActionToolbar(
            "AffectedTestsToolbar",
            DefaultActionGroup(
                findHeadAction, findWtAction, refreshAction,
                Separator.getInstance(),
                toggleTestsViewAction,
                Separator.getInstance(),
                runAllAction, runAllDebugAction, removeSelectedAction,
                Separator.getInstance(),
                backAction,
            ),
            true
        )
        affectedToolbarRef.targetComponent = this

        affectedStatusLabel.border = JBUI.Borders.empty(4, 6)

        val affectedTop = JPanel(BorderLayout())
        affectedTop.add(affectedToolbarRef.component, BorderLayout.WEST)
        affectedTop.add(affectedStatusLabel, BorderLayout.CENTER)

        splitter.firstComponent = affectedFilesPane.component

        affectedPanel.add(affectedTop, BorderLayout.NORTH)
        affectedPanel.add(splitter, BorderLayout.CENTER)

        // ── Card layout ─────────────────────────────────────────────
        cardPanel.add(normalPanel, NORMAL_CARD)
        cardPanel.add(affectedPanel, AFFECTED_CARD)
        add(cardPanel, BorderLayout.CENTER)

        // Start in normal mode
        switchToNormal()

        // F5 = Refresh
        registerKeyboardAction(
            { lastDiffMode?.let { findAffectedTests(it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
        )
    }

    // ── Mode switching ──────────────────────────────────────────────

    private fun switchToNormal() {
        normalPanel.add(treeScrollPane, BorderLayout.CENTER)
        normalPanel.revalidate()
        cardLayout.show(cardPanel, NORMAL_CARD)
    }

    private fun switchToAffected() {
        splitter.secondComponent = treeScrollPane
        cardLayout.show(cardPanel, AFFECTED_CARD)
        // Force toolbar to re-evaluate action enabled state now that
        // the affected card is visible and allTests is populated.
        @Suppress("DEPRECATION")
        affectedToolbarRef.updateActionsImmediately()
    }

    // ── Normal mode ─────────────────────────────────────────────────

    fun showTests(lineNumber: Int, filePath: String, tests: List<String>) {
        switchToNormal()

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
        treeModel.reload()

        if (tests.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No tests covering this line"))
            treeModel.reload()
            return
        }

        // Resolve scenario names via PSI off the EDT, then populate the tree on EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val scenarioLabels = resolveScenarioLabels(tests)
            ApplicationManager.getApplication().invokeLater {
                populateTreeNodes(tests, scenarioLabels)
            }
        }
    }

    /** Resolves Behat scenario display labels via PSI. Must be called off the EDT. */
    private fun resolveScenarioLabels(tests: List<String>): Map<String, String> {
        return tests.filter { isBehatTest(it) }.associate { testName ->
            val featurePath = testName.substringBeforeLast(":", testName)
            val line = testName.substringAfterLast(":", "")
            val label = CoverageTestNavigator.resolveScenarioName(project, featurePath, line) ?: "line $line"
            testName to label
        }
    }

    /** Populates the tree with resolved node data. Must be called on the EDT. */
    private fun populateTreeNodes(tests: List<String>, scenarioLabels: Map<String, String>) {
        rootNode.removeAllChildren()
        val (behatTests, phpunitTests) = tests.partition { isBehatTest(it) }

        if (isTestsTreeView) {
            if (behatTests.isNotEmpty()) {
                val behatGrouped = behatTests.groupBy { it.substringBeforeLast(":", it) }
                buildBehatHierarchy(behatGrouped, scenarioLabels, rootNode)
            }
            if (phpunitTests.isNotEmpty()) {
                val grouped = phpunitTests.groupBy { it.substringBeforeLast("::", "") .ifEmpty { "(no class)" } }
                buildPhpUnitHierarchy(grouped, rootNode)
            }
        } else {
            // Group Behat tests by feature file path
            if (behatTests.isNotEmpty()) {
                val behatGrouped = behatTests.groupBy { name ->
                    name.substringBeforeLast(":", name)
                }
                for ((featurePath, entries) in behatGrouped.toSortedMap()) {
                    val displayPath = CoverageTestNavigator.toProjectRelativeFeaturePath(featurePath, project)
                    val fileNode = DefaultMutableTreeNode(TestNodeData.BehatGroup(featurePath, displayPath))
                    for (entry in entries) {
                        val label = scenarioLabels[entry] ?: "line ${entry.substringAfterLast(":", "")}"
                        fileNode.add(DefaultMutableTreeNode(TestNodeData.BehatScenario(label, entry)))
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
        }

        treeModel.reload()
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }

    // ── Hierarchical tree builders ──────────────────────────────────

    /**
     * Builds a directory-tree structure for Behat tests.
     * Feature file paths are split on "/" into directory nodes, with compact
     * single-child dirs merged (identical logic to AffectedFilesPane).
     */
    private fun buildBehatHierarchy(
        behatGrouped: Map<String, List<String>>,
        scenarioLabels: Map<String, String>,
        parent: DefaultMutableTreeNode,
    ) {
        data class DirEntry(
            val children: MutableMap<String, DirEntry> = sortedMapOf(),
            val files: MutableList<Pair<String, List<String>>> = mutableListOf(),
        )

        val top = DirEntry()
        for ((featurePath, entries) in behatGrouped.toSortedMap()) {
            val displayPath = CoverageTestNavigator.toProjectRelativeFeaturePath(featurePath, project)
            val parts = displayPath.split("/")
            var current = top
            for (i in 0 until parts.size - 1) {
                current = current.children.getOrPut(parts[i]) { DirEntry() }
            }
            current.files.add(featurePath to entries)
        }

        fun countScenarios(entry: DirEntry): Int =
            entry.files.sumOf { it.second.size } + entry.children.values.sumOf { countScenarios(it) }

        fun addNodes(entry: DirEntry, node: DefaultMutableTreeNode) {
            for ((name, child) in entry.children) {
                var collapsed = child
                var display = name
                while (collapsed.files.isEmpty() && collapsed.children.size == 1) {
                    val (cName, cChild) = collapsed.children.entries.first()
                    display = "$display/$cName"
                    collapsed = cChild
                }
                val dirNode = DefaultMutableTreeNode(TestNodeData.Dir(display, countScenarios(collapsed)))
                addNodes(collapsed, dirNode)
                node.add(dirNode)
            }
            for ((featurePath, entries) in entry.files) {
                val fileName = featurePath.substringAfterLast("/")
                val fileNode = DefaultMutableTreeNode(TestNodeData.BehatGroup(featurePath, fileName))
                for (testName in entries) {
                    val label = scenarioLabels[testName] ?: "line ${testName.substringAfterLast(":", "")}"
                    fileNode.add(DefaultMutableTreeNode(TestNodeData.BehatScenario(label, testName)))
                }
                node.add(fileNode)
            }
        }

        addNodes(top, parent)
    }

    /**
     * Builds a namespace-tree structure for PHPUnit tests.
     * Class names are split on "\" into namespace nodes, with compact
     * single-child namespaces merged.
     */
    private fun buildPhpUnitHierarchy(
        grouped: Map<String, List<String>>,
        parent: DefaultMutableTreeNode,
    ) {
        data class NsEntry(
            val children: MutableMap<String, NsEntry> = sortedMapOf(),
            val classes: MutableList<Pair<String, List<String>>> = mutableListOf(),
        )

        val top = NsEntry()
        for ((className, methods) in grouped.toSortedMap()) {
            val parts = className.split("\\")
            var current = top
            for (i in 0 until parts.size - 1) {
                current = current.children.getOrPut(parts[i]) { NsEntry() }
            }
            current.classes.add(className to methods)
        }

        fun countMethods(entry: NsEntry): Int =
            entry.classes.sumOf { it.second.size } + entry.children.values.sumOf { countMethods(it) }

        fun addNodes(entry: NsEntry, node: DefaultMutableTreeNode) {
            for ((name, child) in entry.children) {
                var collapsed = child
                var display = name
                while (collapsed.classes.isEmpty() && collapsed.children.size == 1) {
                    val (cName, cChild) = collapsed.children.entries.first()
                    display = "$display\\$cName"
                    collapsed = cChild
                }
                val nsNode = DefaultMutableTreeNode(TestNodeData.Dir(display, countMethods(collapsed)))
                addNodes(collapsed, nsNode)
                node.add(nsNode)
            }
            for ((fullClassName, methods) in entry.classes) {
                val simpleName = fullClassName.substringAfterLast("\\", fullClassName)
                val classNode = DefaultMutableTreeNode(TestNodeData.PhpUnitGroup(fullClassName, simpleName))
                for (fullName in methods) {
                    val methodName = fullName.substringAfterLast("::", fullName)
                    classNode.add(DefaultMutableTreeNode(TestNodeData.PhpUnitMethod(methodName, fullName)))
                }
                node.add(classNode)
            }
        }

        addNodes(top, parent)
    }

    // ── Affected mode ───────────────────────────────────────────────

    private fun findAffectedTests(mode: ChangedLinesAnalyzer.DiffMode) {
        lastDiffMode = mode
        log.info("Finding affected tests (mode=$mode)")
        object : Task.Backgroundable(project, "Finding affected tests…", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = AffectedTestsService.getInstance(project).compute(mode)
                ApplicationManager.getApplication().invokeLater {
                    populateAffected(result)
                }
            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    val title = if (error is CoverageApiException) "Affected Tests" else "Error"
                    Messages.showErrorDialog(project, error.message ?: "Unknown error", title)
                }
            }
        }.queue()
    }

    private fun populateAffected(result: AffectedTestsService.AffectedTests) {
        val model = AffectedTestsModel(result.perFile.mapValues { (_, v) -> v })
        affectedModel = model

        val modeStr = if (result.mode == ChangedLinesAnalyzer.DiffMode.COMMITTED) "HEAD" else "Working Tree"
        val parts = mutableListOf("${model.displayedTests.size} tests, ${model.allFiles.size} files ($modeStr)")
        if (result.newFiles.isNotEmpty()) parts += "${result.newFiles.size} new"
        if (result.deletedFiles.isNotEmpty()) parts += "${result.deletedFiles.size} deleted"
        if (result.filesWithoutCoverage.isNotEmpty()) parts += "${result.filesWithoutCoverage.size} no coverage"
        affectedStatusLabel.text = parts.joinToString(" · ")

        affectedFilesPane.populate(model)
        updateTestsFromModel()
        switchToAffected()

        log.info("Affected tests populated: ${model.displayedTests.size} tests across ${model.allFiles.size} files")
    }

    private fun updateTestsFromModel() {
        val model = affectedModel ?: return
        val displayed = model.displayedTests
        allTests = displayed.toList()

        val modeStr = if (lastDiffMode == ChangedLinesAnalyzer.DiffMode.COMMITTED) "HEAD" else "Working Tree"
        affectedStatusLabel.text = "${displayed.size} tests, ${model.allFiles.size} files ($modeStr)"

        buildTree(allTests)
    }

    private fun removeSelectedTests() {
        val model = affectedModel ?: return
        val selectedNodes = tree.selectionPaths?.mapNotNull {
            it.lastPathComponent as? DefaultMutableTreeNode
        } ?: return

        val testsToRemove = mutableSetOf<String>()
        for (node in selectedNodes) collectTestNames(node, testsToRemove)
        if (testsToRemove.isEmpty()) return

        model.removeTests(testsToRemove)
        updateTestsFromModel()
        affectedFilesPane.filesTree.repaint()
    }

    private fun collectTestNames(node: DefaultMutableTreeNode, into: MutableSet<String>) {
        when (node.userObject) {
            is TestNodeData.BehatScenario ->
                into += (node.userObject as TestNodeData.BehatScenario).originalTestName
            is TestNodeData.PhpUnitMethod ->
                into += (node.userObject as TestNodeData.PhpUnitMethod).fullTestName
            is TestNodeData.BehatGroup, is TestNodeData.PhpUnitGroup -> {
                for (i in 0 until node.childCount) {
                    collectTestNames(node.getChildAt(i) as DefaultMutableTreeNode, into)
                }
            }
        }
    }

    private fun goBack() {
        affectedModel = null
        lastDiffMode = null
        switchToNormal()
    }

    // ── Shared helpers ──────────────────────────────────────────────

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

    private fun runTest(testName: String, debug: Boolean = false) {
        if (isBehatTest(testName)) {
            runBehatTest(testName, debug)
        } else {
            // For PHPUnit, fall back to navigation for now
            CoverageTestNavigator.navigateToTest(project, testName)
        }
    }

    private fun runBehatTest(testName: String, debug: Boolean = false) {
        if (!BehatTestRunner.isAvailable()) {
            CoverageTestNavigator.navigateToBehatTest(project, testName)
            return
        }

        val rawFeaturePath = testName.substringBeforeLast(":", "")
        val featurePath = CoverageTestNavigator.toProjectRelativeFeaturePath(rawFeaturePath, project)
        val lineStr = testName.substringAfterLast(":", "")
        val lineNumber = lineStr.toIntOrNull()

        val projectDir = project.guessProjectDir() ?: return
        val vf = VfsUtil.findRelativeFile(featurePath, projectDir) ?: return
        val absolutePath = vf.path

        if (lineNumber != null) {
            val scenarioName = CoverageTestNavigator.resolveScenarioName(project, rawFeaturePath, lineStr)
            if (scenarioName != null) {
                BehatTestRunner.runScenario(project, absolutePath, scenarioName, debug)
            } else {
                BehatTestRunner.runFeatureFile(project, absolutePath, debug)
            }
        } else {
            BehatTestRunner.runFeatureFile(project, absolutePath, debug)
        }
    }

    private fun runAllBehatBundled(debug: Boolean = false) {
        if (runAllInProgress) return
        if (!BehatTestRunner.isAvailable()) return
        val behatTests = allTests.filter { isBehatTest(it) }
        if (behatTests.isEmpty()) return
        runBehatBundled(behatTests, debug)
    }

    /**
     * Launches a single Behat process for [tests], using the custom `--paths` option
     * to bundle multiple feature files / scenarios. Lines under the same feature file
     * are grouped together (`--paths foo.feature:10,20`); a test without a `:line`
     * suffix is treated as "run the entire file".
     */
    private fun runBehatBundled(tests: List<String>, debug: Boolean) {
        val pathsByFile = buildPathsByFile(tests)
        if (pathsByFile.isEmpty()) return
        runAllInProgress = true
        BehatTestRunner.runMultiplePathsWithCallback(
            project,
            pathsByFile,
            onFinished = { runAllInProgress = false },
            debug = debug,
        )
    }

    private fun buildPathsByFile(tests: List<String>): Map<String, List<Int>> {
        val projectDir = project.guessProjectDir() ?: return emptyMap()
        val map = linkedMapOf<String, MutableList<Int>>()
        val wholeFile = mutableSetOf<String>()
        for (test in tests) {
            if (!isBehatTest(test)) continue
            val rawFeaturePath = test.substringBeforeLast(":", test)
            val featurePath = CoverageTestNavigator.toProjectRelativeFeaturePath(rawFeaturePath, project)
            val lineStr = test.substringAfterLast(":", "")
            val lineNumber = lineStr.toIntOrNull()
            val vf = VfsUtil.findRelativeFile(featurePath, projectDir) ?: continue
            val absolutePath = vf.path
            val list = map.getOrPut(absolutePath) { mutableListOf() }
            if (lineNumber == null) {
                wholeFile.add(absolutePath)
                list.clear()
            } else if (absolutePath !in wholeFile) {
                if (lineNumber !in list) list.add(lineNumber)
            }
        }
        return map
    }

    private fun canRunTest(testName: String): Boolean {
        return if (isBehatTest(testName)) {
            BehatTestRunner.isAvailable()
        } else {
            false // PHPUnit run not yet implemented
        }
    }

    companion object {
        private const val NORMAL_CARD = "normal"
        private const val AFFECTED_CARD = "affected"

        /** Detects whether a test name is a Behat test (feature file path + line). */
        fun isBehatTest(testName: String): Boolean = CoverageTestNavigator.isBehatTest(testName)

        fun getInstance(project: Project): CoverageTestsPanel? {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Coverage Tests") ?: return null
            val content = toolWindow.contentManager.getContent(0) ?: return null
            return content.component as? CoverageTestsPanel
        }

        fun showTestsInPanel(project: Project, lineNumber: Int, filePath: String, tests: List<String>) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Coverage Tests") ?: return
            // Switch to the Tests tab if another tab (Log, Artifacts) is currently active
            toolWindow.contentManager.getContent(0)
                ?.let { toolWindow.contentManager.setSelectedContent(it) }
            toolWindow.show {
                val panel = getInstance(project) ?: return@show
                panel.showTests(lineNumber, filePath, tests)
            }
        }
    }
}
