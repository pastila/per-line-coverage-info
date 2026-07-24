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
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import icons.BehatIcons
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel

/**
 * Shared "tests tree" widget used by both the per-line and affected-by-changes panels.
 *
 * Owns the [CheckboxTree], its model, the renderer, the double-click / popup-menu listeners,
 * the flat-vs-tree toggle state, the Behat scenario-name resolution + tree builders,
 * and the local toolbar (Tree View, Select All, Deselect All).
 *
 * Callers feed it a list of test names via [setTests]; it groups them into a tree.
 * Checkboxes allow the user to exclude individual tests; [checkedTestNames] returns
 * only the currently checked test leaves.
 */
internal class TestTreeView(private val project: Project) {

    var isTreeView: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    @Volatile
    var runAllInProgress: Boolean = false
        private set

    private val rootNode = CheckedTreeNode("Tests")
    private val treeModel: DefaultTreeModel get() = tree.model as DefaultTreeModel

    val tree: CheckboxTree = CheckboxTree(object : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: JTree?, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? CheckedTreeNode ?: return
            val r = textRenderer
            when (val data = node.userObject) {
                is TestNodeData.Dir -> {
                    r.icon = AllIcons.Nodes.Folder
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (data.count > 0) r.append(" (${data.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TestNodeData.BehatGroup -> {
                    r.icon = BehatIcons.Behat
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val count = node.childCount
                    if (count > 0) r.append(" ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TestNodeData.BehatScenario -> {
                    r.icon = BehatIcons.Behat
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                is TestNodeData.PhpUnitGroup -> {
                    r.icon = AllIcons.Nodes.TestGroup
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val count = node.childCount
                    if (count > 0) r.append(" ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                else -> r.append(node.userObject?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }, rootNode, CheckboxTreeBase.CheckPolicy(true, true, true, true))

    private val scrollPane = JBScrollPane(tree)
    val component: JComponent

    private val toolbar: ActionToolbar
    private val statusLabel = JBLabel()
    private var allTests: List<String> = emptyList()
    private var emptyMessage: String = "No tests"

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? CheckedTreeNode ?: return
                    val testName = resolveFullTestName(node) ?: return
                    CoverageTestNavigator.navigateToTest(project, testName)
                }
            }
        })

        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: java.awt.Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? CheckedTreeNode ?: return
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

        val toggleViewAction = object : ToggleAction(
            "Tree View", "Toggle between tree and flat test list", AllIcons.Actions.GroupByPackage
        ) {
            override fun isSelected(e: AnActionEvent): Boolean = isTreeView
            override fun setSelected(e: AnActionEvent, state: Boolean) { isTreeView = state }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val selectAllAction = object : AnAction(
            "Select All", "Select all tests", AllIcons.Actions.Selectall
        ) {
            override fun actionPerformed(e: AnActionEvent) = selectAllTests()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val deselectAllAction = object : AnAction(
            "Deselect All", "Deselect all tests", AllIcons.Actions.Unselectall
        ) {
            override fun actionPerformed(e: AnActionEvent) = deselectAllTests()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val expandAllAction = object : AnAction(
            "Expand All", "Expand all nodes", AllIcons.Actions.Expandall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                for (i in 0 until tree.rowCount) tree.expandRow(i)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val collapseAllAction = object : AnAction(
            "Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                for (i in tree.rowCount - 1 downTo 1) tree.collapseRow(i)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val runAllAction = object : AnAction(
            "Run All", "Run all Behat tests in a single launch", AllIcons.Actions.RunAll
        ) {
            override fun actionPerformed(e: AnActionEvent) = runAllBehatBundled(debug = false)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    checkedTestNames().any { isBehatTest(it) }
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val runAllDebugAction = object : AnAction(
            "Run All With Debug",
            "Debug all Behat tests in a single launch",
            AllIcons.Actions.StartDebugger
        ) {
            override fun actionPerformed(e: AnActionEvent) = runAllBehatBundled(debug = true)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    checkedTestNames().any { isBehatTest(it) }
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        toolbar = ActionManager.getInstance().createActionToolbar(
            "TestTreeViewToolbar",
            DefaultActionGroup(
                toggleViewAction, expandAllAction, collapseAllAction,
                Separator.getInstance(),
                selectAllAction, deselectAllAction,
                Separator.getInstance(),
                runAllAction, runAllDebugAction,
            ),
            true
        )
        toolbar.targetComponent = tree

        statusLabel.border = JBUI.Borders.empty(2, 6)

        val bar = JPanel(BorderLayout())
        bar.add(toolbar.component, BorderLayout.WEST)
        bar.add(statusLabel, BorderLayout.CENTER)

        val panel = JPanel(BorderLayout())
        panel.add(bar, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)
        component = panel

        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                refreshCheckedCount()
                toolbar.updateActionsAsync()
            }
        })
    }

    // ── Public API ──────────────────────────────────────────────────

    fun setTests(tests: List<String>) {
        allTests = tests
        rebuild()
    }

    fun setEmptyMessage(message: String) {
        emptyMessage = message
        if (allTests.isEmpty()) rebuild()
    }

    fun setStatusText(text: String) {
        statusLabel.text = text
    }

    fun currentTests(): List<String> = allTests

    /** Returns the test names of all currently checked leaf nodes. */
    fun checkedTestNames(): Set<String> {
        val out = mutableSetOf<String>()
        collectCheckedTestNames(rootNode, out)
        return out
    }

    fun selectAllTests() {
        setAllChecked(rootNode, true)
        tree.repaint()
    }

    fun deselectAllTests() {
        setAllChecked(rootNode, false)
        tree.repaint()
    }

    fun hasAnyBehatTest(): Boolean = allTests.any { isBehatTest(it) }

    private fun refreshCheckedCount() {
        val count = checkedTestNames().size
        statusLabel.text = if (count == 0 && allTests.isEmpty()) "" else "selected $count test${if (count != 1) "s" else ""}"
    }

    /** Resolves all test-leaf names under the user's tree selection. */
    fun collectSelectedTestNames(): Set<String> {
        val nodes = tree.selectionPaths?.mapNotNull { it.lastPathComponent as? CheckedTreeNode }
            ?: return emptySet()
        val out = mutableSetOf<String>()
        for (node in nodes) collectTestNames(node, out)
        return out
    }

    fun selectionCount(): Int = tree.selectionCount

    /**
     * Launches a single Behat process for all currently checked Behat tests.
     * Tracks [runAllInProgress] so callers can disable their toolbar buttons.
     */
    fun runAllBehatBundled(debug: Boolean) {
        if (runAllInProgress) return
        if (!BehatTestRunner.isAvailable()) return
        val behatTests = checkedTestNames().filter { isBehatTest(it) }
        if (behatTests.isEmpty()) return
        val pathsByFile = buildPathsByFile(behatTests)
        if (pathsByFile.isEmpty()) return
        runAllInProgress = true
        BehatTestRunner.runMultiplePathsWithCallback(
            project,
            pathsByFile,
            onFinished = { runAllInProgress = false },
            debug = debug,
        )
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun rebuild() {
        rootNode.removeAllChildren()
        treeModel.reload()

        if (allTests.isEmpty()) {
            rootNode.add(CheckedTreeNode(emptyMessage))
            treeModel.reload()
            return
        }

        val tests = allTests
        if (DumbService.isDumb(project)) {
            populateTreeNodes(tests, emptyMap())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val scenarioLabels = resolveScenarioLabels(tests)
            ApplicationManager.getApplication().invokeLater {
                if (allTests !== tests) return@invokeLater
                populateTreeNodes(tests, scenarioLabels)
            }
        }
    }

    private fun resolveScenarioLabels(tests: List<String>): Map<String, String> {
        return tests.filter { isBehatTest(it) }.associate { testName ->
            val featurePath = testName.substringBeforeLast(":", testName)
            val line = testName.substringAfterLast(":", "")
            val label = CoverageTestNavigator.resolveScenarioName(project, featurePath, line) ?: "line $line"
            testName to label
        }
    }

    private fun populateTreeNodes(tests: List<String>, scenarioLabels: Map<String, String>) {
        rootNode.removeAllChildren()
        val (behatTests, phpunitTests) = tests.partition { isBehatTest(it) }

        if (isTreeView) {
            if (behatTests.isNotEmpty()) {
                val behatGrouped = behatTests.groupBy { it.substringBeforeLast(":", it) }
                buildBehatHierarchy(behatGrouped, scenarioLabels, rootNode)
            }
            if (phpunitTests.isNotEmpty()) {
                val grouped = phpunitTests.groupBy { it.substringBeforeLast("::", "").ifEmpty { "(no class)" } }
                buildPhpUnitHierarchy(grouped, rootNode)
            }
        } else {
            if (behatTests.isNotEmpty()) {
                val behatGrouped = behatTests.groupBy { it.substringBeforeLast(":", it) }
                for ((featurePath, entries) in behatGrouped.toSortedMap()) {
                    val displayPath = CoverageTestNavigator.toProjectRelativeFeaturePath(featurePath, project)
                    val fileNode = CheckedTreeNode(TestNodeData.BehatGroup(featurePath, displayPath))
                    fileNode.isChecked = true
                    for (entry in entries) {
                        val label = scenarioLabels[entry] ?: "line ${entry.substringAfterLast(":", "")}"
                        val leaf = CheckedTreeNode(TestNodeData.BehatScenario(label, entry))
                        leaf.isChecked = true
                        fileNode.add(leaf)
                    }
                    rootNode.add(fileNode)
                }
            }
            if (phpunitTests.isNotEmpty()) {
                val grouped = phpunitTests.groupBy { name ->
                    val fqcn = name.substringBeforeLast("::", "")
                    fqcn.ifEmpty { "(no class)" }
                }
                for ((className, methods) in grouped.toSortedMap()) {
                    val classNode = CheckedTreeNode(TestNodeData.PhpUnitGroup(className))
                    classNode.isChecked = true
                    for (fullName in methods) {
                        val methodName = fullName.substringAfterLast("::", fullName)
                        val leaf = CheckedTreeNode(TestNodeData.PhpUnitMethod(methodName, fullName))
                        leaf.isChecked = true
                        classNode.add(leaf)
                    }
                    rootNode.add(classNode)
                }
            }
        }

        treeModel.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
        refreshCheckedCount()
    }

    private fun buildBehatHierarchy(
        behatGrouped: Map<String, List<String>>,
        scenarioLabels: Map<String, String>,
        parent: CheckedTreeNode,
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

        fun addNodes(entry: DirEntry, node: CheckedTreeNode) {
            for ((name, child) in entry.children) {
                var collapsed = child
                var display = name
                while (collapsed.files.isEmpty() && collapsed.children.size == 1) {
                    val (cName, cChild) = collapsed.children.entries.first()
                    display = "$display/$cName"
                    collapsed = cChild
                }
                val dirNode = CheckedTreeNode(TestNodeData.Dir(display, countScenarios(collapsed)))
                addNodes(collapsed, dirNode)
                node.add(dirNode)
            }
            for ((featurePath, entries) in entry.files) {
                val fileName = featurePath.substringAfterLast("/")
                val fileNode = CheckedTreeNode(TestNodeData.BehatGroup(featurePath, fileName))
                fileNode.isChecked = true
                for (testName in entries) {
                    val label = scenarioLabels[testName] ?: "line ${testName.substringAfterLast(":", "")}"
                    val leaf = CheckedTreeNode(TestNodeData.BehatScenario(label, testName))
                    leaf.isChecked = true
                    fileNode.add(leaf)
                }
                node.add(fileNode)
            }
        }

        addNodes(top, parent)
    }

    private fun buildPhpUnitHierarchy(
        grouped: Map<String, List<String>>,
        parent: CheckedTreeNode,
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

        fun addNodes(entry: NsEntry, node: CheckedTreeNode) {
            for ((name, child) in entry.children) {
                var collapsed = child
                var display = name
                while (collapsed.classes.isEmpty() && collapsed.children.size == 1) {
                    val (cName, cChild) = collapsed.children.entries.first()
                    display = "$display\\$cName"
                    collapsed = cChild
                }
                val nsNode = CheckedTreeNode(TestNodeData.Dir(display, countMethods(collapsed)))
                addNodes(collapsed, nsNode)
                node.add(nsNode)
            }
            for ((fullClassName, methods) in entry.classes) {
                val simpleName = fullClassName.substringAfterLast("\\", fullClassName)
                val classNode = CheckedTreeNode(TestNodeData.PhpUnitGroup(fullClassName, simpleName))
                classNode.isChecked = true
                for (fullName in methods) {
                    val methodName = fullName.substringAfterLast("::", fullName)
                    val leaf = CheckedTreeNode(TestNodeData.PhpUnitMethod(methodName, fullName))
                    leaf.isChecked = true
                    classNode.add(leaf)
                }
                node.add(classNode)
            }
        }

        addNodes(top, parent)
    }

    private fun resolveFullTestName(node: CheckedTreeNode): String? {
        return when (val data = node.userObject) {
            is TestNodeData.BehatScenario -> data.originalTestName
            is TestNodeData.BehatGroup -> data.featurePath
            is TestNodeData.PhpUnitMethod -> data.fullTestName
            is TestNodeData.PhpUnitGroup -> data.className
            else -> null
        }
    }

    private fun collectTestNames(node: CheckedTreeNode, into: MutableSet<String>) {
        when (node.userObject) {
            is TestNodeData.BehatScenario ->
                into += (node.userObject as TestNodeData.BehatScenario).originalTestName
            is TestNodeData.PhpUnitMethod ->
                into += (node.userObject as TestNodeData.PhpUnitMethod).fullTestName
            is TestNodeData.BehatGroup, is TestNodeData.PhpUnitGroup, is TestNodeData.Dir -> {
                for (i in 0 until node.childCount) {
                    collectTestNames(node.getChildAt(i) as CheckedTreeNode, into)
                }
            }
            else -> {
                for (i in 0 until node.childCount) {
                    collectTestNames(node.getChildAt(i) as CheckedTreeNode, into)
                }
            }
        }
    }

    private fun collectCheckedTestNames(node: CheckedTreeNode, into: MutableSet<String>) {
        when (node.userObject) {
            is TestNodeData.BehatScenario -> {
                if (node.isChecked) into += (node.userObject as TestNodeData.BehatScenario).originalTestName
            }
            is TestNodeData.PhpUnitMethod -> {
                if (node.isChecked) into += (node.userObject as TestNodeData.PhpUnitMethod).fullTestName
            }
            is TestNodeData.BehatGroup, is TestNodeData.PhpUnitGroup, is TestNodeData.Dir -> {
                for (i in 0 until node.childCount) {
                    collectCheckedTestNames(node.getChildAt(i) as CheckedTreeNode, into)
                }
            }
            else -> {
                for (i in 0 until node.childCount) {
                    collectCheckedTestNames(node.getChildAt(i) as CheckedTreeNode, into)
                }
            }
        }
    }

    private fun setAllChecked(node: CheckedTreeNode, checked: Boolean) {
        node.isChecked = checked
        for (i in 0 until node.childCount) {
            setAllChecked(node.getChildAt(i) as CheckedTreeNode, checked)
        }
    }

    private fun runTest(testName: String, debug: Boolean) {
        if (isBehatTest(testName)) {
            runBehatTest(testName, debug)
        } else {
            CoverageTestNavigator.navigateToTest(project, testName)
        }
    }

    private fun runBehatTest(testName: String, debug: Boolean) {
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
            ApplicationManager.getApplication().executeOnPooledThread {
                val scenarioName = CoverageTestNavigator.resolveScenarioName(project, rawFeaturePath, lineStr)
                invokeLater {
                    if (scenarioName != null) {
                        BehatTestRunner.runScenario(project, absolutePath, scenarioName, debug)
                    } else {
                        BehatTestRunner.runFeatureFile(project, absolutePath, debug)
                    }
                }
            }
        } else {
            BehatTestRunner.runFeatureFile(project, absolutePath, debug)
        }
    }

    private fun canRunTest(testName: String): Boolean =
        isBehatTest(testName) && BehatTestRunner.isAvailable()

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

    companion object {
        fun isBehatTest(testName: String): Boolean = CoverageTestNavigator.isBehatTest(testName)
    }
}
