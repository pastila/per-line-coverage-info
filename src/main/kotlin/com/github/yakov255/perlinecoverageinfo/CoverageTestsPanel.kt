package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
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

private sealed class TestNodeData(val displayName: String) {
    class BehatGroup(val featurePath: String) : TestNodeData(featurePath)
    class BehatScenario(val label: String, val originalTestName: String) : TestNodeData(label)
    class PhpUnitGroup(val className: String) : TestNodeData(className)
    class PhpUnitMethod(val methodName: String, val fullTestName: String) : TestNodeData(methodName)
}

/** Node payloads for the affected-files pane (CheckboxTree). */
private sealed class FileNodeData {
    /** Directory subtree node. [files] is the set of file paths under this directory. */
    class Dir(val displayName: String, val files: Set<String>) : FileNodeData()

    /** Leaf file node. */
    class FileEntry(val path: String, val displayName: String) : FileNodeData()
}

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

    // ── Affected mode ───────────────────────────────────────────────
    private var affectedModel: AffectedTestsModel? = null
    private var lastDiffMode: ChangedLinesAnalyzer.DiffMode? = null
    private var isTreeView = true

    // Files checkbox tree
    private val filesRoot = CheckedTreeNode(null)
    private val filesTree: CheckboxTree
    private val affectedStatusLabel = JBLabel("")

    // Layout
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val treeScrollPane = JBScrollPane(tree)
    private val normalPanel = JPanel(BorderLayout())
    private val affectedPanel = JPanel(BorderLayout())
    private val splitter = OnePixelSplitter(false, 0.35f)
    private val affectedToolbarRef: ActionToolbar
    private var syncPending = false

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

        // Double-click to navigate to test
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val testName = resolveFullTestName(node) ?: return
                    navigateToTest(testName)
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

                val group = DefaultActionGroup().apply {
                    if (canRunTest(testName)) {
                        add(object : AnAction("Run Test", null, AllIcons.Actions.Execute) {
                            override fun actionPerformed(e: AnActionEvent) {
                                runTest(testName, debug = false)
                            }
                        })
                        add(object : AnAction("Debug Test", null, AllIcons.Actions.StartDebugger) {
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

        // ── Files checkbox tree ─────────────────────────────────────
        filesTree = CheckboxTree(object : CheckboxTree.CheckboxTreeCellRenderer() {
            override fun customizeRenderer(
                tree: JTree?, value: Any?, selected: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                val node = value as? CheckedTreeNode ?: return
                val model = affectedModel ?: return
                val r = textRenderer
                when (val data = node.userObject) {
                    is FileNodeData.Dir -> {
                        r.icon = AllIcons.Nodes.Folder
                        val testCount = data.files
                            .flatMapTo(linkedSetOf<String>()) { model.perFile[it] ?: emptySet() }
                            .size
                        r.append("$testCount ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val delta = model.deltaForFiles(data.files)
                        if (delta > 0) {
                            r.append("(-$delta) ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        r.append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    }
                    is FileNodeData.FileEntry -> {
                        r.icon = AllIcons.FileTypes.Any_type
                        val tests = model.perFile[data.path] ?: emptySet()
                        r.append("${tests.size} ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val delta = model.deltaForFiles(setOf(data.path))
                        if (delta > 0) {
                            r.append("(-$delta) ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        r.append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            }
        }, filesRoot)

        filesTree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (!syncPending) {
                    syncPending = true
                    ApplicationManager.getApplication().invokeLater {
                        syncPending = false
                        syncModelFromTree()
                    }
                }
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

        val runSelectedAction = object : AnAction(
            "Run Selected",
            "Run selected Behat tests in a single launch via --paths",
            AllIcons.Actions.Execute
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                if (runAllInProgress) return
                val sel = selectedBehatTests()
                if (sel.isEmpty()) return
                runBehatBundled(sel, debug = false)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    selectedBehatTests().isNotEmpty()
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

        // ── Normal mode panel ───────────────────────────────────────
        titleLabel.border = JBUI.Borders.empty(4, 6)
        titleLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        titleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                navigateToSourceLine()
            }
        })

        val normalToolbar = ActionManager.getInstance().createActionToolbar(
            "CoverageTestsPanelToolbar",
            DefaultActionGroup(runAllAction, runAllDebugAction, runSelectedAction, Separator.getInstance(), findHeadAction, findWtAction),
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
                runAllAction, runAllDebugAction, runSelectedAction, removeSelectedAction,
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

        // Files pane with its own toolbar
        val treeToggleAction = ToggleAffectedFilesViewAction(
            isTreeView = { isTreeView },
            toggle = { state ->
                isTreeView = state
                refreshFilesTree()
            },
        )

        val expandAllAction = object : AnAction(
            "Expand All", "Expand all nodes", AllIcons.Actions.Expandall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                for (i in 0 until filesTree.rowCount) filesTree.expandRow(i)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val collapseAllAction = object : AnAction(
            "Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                for (i in filesTree.rowCount - 1 downTo 1) filesTree.collapseRow(i)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val checkAllAction = object : AnAction(
            "Check All", "Check all files", AllIcons.Actions.Selectall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                affectedModel?.checkAll()
                refreshFilesTree()
                updateTestsFromModel()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val uncheckAllAction = object : AnAction(
            "Uncheck All", "Uncheck all files", AllIcons.Actions.Unselectall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                affectedModel?.uncheckAll()
                refreshFilesTree()
                updateTestsFromModel()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val filesToolbar = ActionManager.getInstance().createActionToolbar(
            "AffectedFilesToolbar",
            DefaultActionGroup(
                treeToggleAction, expandAllAction, collapseAllAction,
                Separator.getInstance(),
                checkAllAction, uncheckAllAction,
            ),
            true
        )
        filesToolbar.targetComponent = this

        val filesPanel = JPanel(BorderLayout())
        filesPanel.add(filesToolbar.component, BorderLayout.NORTH)
        filesPanel.add(JBScrollPane(filesTree), BorderLayout.CENTER)

        splitter.firstComponent = filesPanel

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

    // ── Affected mode ───────────────────────────────────────────────

    private fun findAffectedTests(mode: ChangedLinesAnalyzer.DiffMode) {
        lastDiffMode = mode
        log.info("Finding affected tests (mode=$mode)")
        object : Task.Backgroundable(project, "Finding affected tests\u2026", true) {
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
        affectedStatusLabel.text = parts.joinToString(" \u00b7 ")

        refreshFilesTree()
        updateTestsFromModel()
        switchToAffected()

        log.info("Affected tests populated: ${model.displayedTests.size} tests across ${model.allFiles.size} files")
    }

    private fun refreshFilesTree() {
        val model = affectedModel ?: return
        filesRoot.removeAllChildren()

        if (isTreeView) {
            buildTreeNodes(model)
        } else {
            buildFlatNodes(model)
        }

        (filesTree.model as DefaultTreeModel).reload()

        // Expand first level in tree mode
        if (isTreeView) {
            for (i in 0 until minOf(filesTree.rowCount, 100)) {
                filesTree.expandRow(i)
            }
        }
    }

    private fun buildTreeNodes(model: AffectedTestsModel) {
        data class DirEntry(
            val children: MutableMap<String, DirEntry> = sortedMapOf(),
            val files: MutableList<String> = mutableListOf(),
        )

        val top = DirEntry()
        for (path in model.allFiles.sorted()) {
            val parts = path.split("/")
            var current = top
            for (i in 0 until parts.size - 1) {
                current = current.children.getOrPut(parts[i]) { DirEntry() }
            }
            current.files.add(path)
        }

        fun allFilesUnder(entry: DirEntry): Set<String> {
            val result = mutableSetOf<String>()
            result.addAll(entry.files)
            for ((_, child) in entry.children) result.addAll(allFilesUnder(child))
            return result
        }

        fun addNodes(entry: DirEntry, parent: CheckedTreeNode) {
            // Directories first (already sorted)
            for ((name, child) in entry.children) {
                // Compact middle packages: collapse single-child dirs with no files
                var collapsed = child
                var display = name
                while (collapsed.files.isEmpty() && collapsed.children.size == 1) {
                    val (cName, cChild) = collapsed.children.entries.first()
                    display = "$display/$cName"
                    collapsed = cChild
                }

                val files = allFilesUnder(collapsed)
                val dirNode = CheckedTreeNode(FileNodeData.Dir(display, files))
                addNodes(collapsed, dirNode)
                parent.add(dirNode)
            }
            // Then files
            for (filePath in entry.files) {
                val fileName = filePath.substringAfterLast("/")
                val node = CheckedTreeNode(FileNodeData.FileEntry(filePath, fileName))
                node.isChecked = filePath in model.checkedFiles
                parent.add(node)
            }
        }

        addNodes(top, filesRoot)
    }

    private fun buildFlatNodes(model: AffectedTestsModel) {
        // Sort by test count descending (bootstrap.php at top)
        val sorted = model.allFiles.sortedByDescending { (model.perFile[it] ?: emptySet()).size }
        for (path in sorted) {
            val node = CheckedTreeNode(FileNodeData.FileEntry(path, path))
            node.isChecked = path in model.checkedFiles
            filesRoot.add(node)
        }
    }

    private fun syncModelFromTree() {
        val model = affectedModel ?: return
        model.uncheckAll()

        fun walk(node: CheckedTreeNode) {
            if (node.userObject is FileNodeData.FileEntry && node.isChecked) {
                model.setChecked((node.userObject as FileNodeData.FileEntry).path, true)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i)
                if (child is CheckedTreeNode) walk(child)
            }
        }
        walk(filesRoot)

        updateTestsFromModel()
        filesTree.repaint()
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
        filesTree.repaint()
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
     * are grouped together (`--paths=foo.feature:10,20`); a test without a `:line`
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
            val featurePath = test.substringBeforeLast(":", test)
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

    /** Collects Behat tests reachable from the current tree selection (recursing into groups). */
    private fun selectedBehatTests(): List<String> {
        val paths = tree.selectionPaths ?: return emptyList()
        val collected = linkedSetOf<String>()
        for (p in paths) {
            val node = p.lastPathComponent as? DefaultMutableTreeNode ?: continue
            collectBehatLeaves(node, collected)
        }
        return collected.toList()
    }

    private fun collectBehatLeaves(node: DefaultMutableTreeNode, into: MutableSet<String>) {
        when (val data = node.userObject) {
            is TestNodeData.BehatScenario -> into.add(data.originalTestName)
            is TestNodeData.BehatGroup -> into.add(data.featurePath)
            else -> {
                for (i in 0 until node.childCount) {
                    val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                    collectBehatLeaves(child, into)
                }
            }
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
        private const val NORMAL_CARD = "normal"
        private const val AFFECTED_CARD = "affected"

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
