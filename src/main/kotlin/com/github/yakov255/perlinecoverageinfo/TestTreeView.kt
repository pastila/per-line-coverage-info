package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import icons.BehatIcons
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Shared "tests tree" widget used by both the per-line and affected-by-changes panels.
 *
 * Owns the [Tree], its model, the renderer, the double-click / popup-menu listeners,
 * the flat-vs-tree toggle state, and the Behat scenario-name resolution + tree builders.
 *
 * Callers feed it a list of test names via [setTests]; it groups them into a tree.
 */
internal class TestTreeView(private val project: Project) {

    var isTreeView: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    @Volatile
    var runAllInProgress: Boolean = false
        private set

    private val rootNode = DefaultMutableTreeNode("Tests")
    private val treeModel = DefaultTreeModel(rootNode)
    val tree: Tree = Tree(treeModel)
    private val scrollPane = JBScrollPane(tree)
    val component: JComponent get() = scrollPane

    private var allTests: List<String> = emptyList()
    private var emptyMessage: String = "No tests"

    init {
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
                        val count = node.childCount
                        if (count > 0) append(" ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is TestNodeData.BehatScenario -> {
                        icon = BehatIcons.Behat
                        append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    is TestNodeData.PhpUnitGroup -> {
                        icon = AllIcons.Nodes.TestGroup
                        append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        val count = node.childCount
                        if (count > 0) append(" ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    else -> append(node?.userObject?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val testName = resolveFullTestName(node) ?: return
                    CoverageTestNavigator.navigateToTest(project, testName)
                }
            }
        })

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

    fun currentTests(): List<String> = allTests

    fun hasAnyBehatTest(): Boolean = allTests.any { isBehatTest(it) }

    /** Resolves all test-leaf names under the user's tree selection. */
    fun collectSelectedTestNames(): Set<String> {
        val nodes = tree.selectionPaths?.mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode }
            ?: return emptySet()
        val out = mutableSetOf<String>()
        for (node in nodes) collectTestNames(node, out)
        return out
    }

    fun selectionCount(): Int = tree.selectionCount

    /**
     * Launches a single Behat process for all currently displayed Behat tests.
     * Tracks [runAllInProgress] so callers can disable their toolbar buttons.
     */
    fun runAllBehatBundled(debug: Boolean) {
        if (runAllInProgress) return
        if (!BehatTestRunner.isAvailable()) return
        val behatTests = allTests.filter { isBehatTest(it) }
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
            rootNode.add(DefaultMutableTreeNode(emptyMessage))
            treeModel.reload()
            return
        }

        val tests = allTests
        if (DumbService.isDumb(project)) {
            // Index is rebuilding — build the tree immediately with raw test
            // names (scenario labels will resolve to "line N" without PSI).
            populateTreeNodes(tests, emptyMap())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val scenarioLabels = resolveScenarioLabels(tests)
            ApplicationManager.getApplication().invokeLater {
                // Guard: another rebuild may have replaced allTests in the meantime.
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
                    val fileNode = DefaultMutableTreeNode(TestNodeData.BehatGroup(featurePath, displayPath))
                    for (entry in entries) {
                        val label = scenarioLabels[entry] ?: "line ${entry.substringAfterLast(":", "")}"
                        fileNode.add(DefaultMutableTreeNode(TestNodeData.BehatScenario(label, entry)))
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
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

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

    private fun resolveFullTestName(node: DefaultMutableTreeNode): String? {
        return when (val data = node.userObject) {
            is TestNodeData.BehatScenario -> data.originalTestName
            is TestNodeData.BehatGroup -> data.featurePath
            is TestNodeData.PhpUnitMethod -> data.fullTestName
            is TestNodeData.PhpUnitGroup -> data.className
            else -> null
        }
    }

    private fun collectTestNames(node: DefaultMutableTreeNode, into: MutableSet<String>) {
        when (node.userObject) {
            is TestNodeData.BehatScenario ->
                into += (node.userObject as TestNodeData.BehatScenario).originalTestName
            is TestNodeData.PhpUnitMethod ->
                into += (node.userObject as TestNodeData.PhpUnitMethod).fullTestName
            is TestNodeData.BehatGroup, is TestNodeData.PhpUnitGroup, is TestNodeData.Dir -> {
                for (i in 0 until node.childCount) {
                    collectTestNames(node.getChildAt(i) as DefaultMutableTreeNode, into)
                }
            }
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
