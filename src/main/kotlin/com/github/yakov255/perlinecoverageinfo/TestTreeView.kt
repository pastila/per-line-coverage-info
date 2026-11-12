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
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import java.io.File

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
internal class TestTreeView(private val project: Project) : Disposable {

    private val lifetime = CoveragePluginDisposable.getInstance(project)
    private val coverageData = CoverageDataService.getInstance(project)
    private val localCoverage = LocalCoverageService.getInstance(project)
    private val log = CoverageLog.get(TestTreeView::class.java)
    @Volatile private var disposed = false
    private var resolutionTicket = 0L
    private var resolutionPending = false
    private var references: List<BehatTestReferences.Resolved> = emptyList()
    private var problems: List<BehatTestReferences.Problem> = emptyList()
    private var labels: Map<String, String> = emptyMap()
    private var inputAnnotations: Map<String, String> = emptyMap()
    private var inputDetails: Map<String, String> = emptyMap()

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
            toolTipText = null
            when (val data = node.userObject) {
                is TestNodeData.Dir -> {
                    r.icon = AllIcons.Nodes.Folder
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (data.count > 0) r.append(" (${data.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TestNodeData.BehatGroup -> {
                    r.icon = PhpFileIcon.forFeature()
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val count = node.childCount
                    if (count > 0) r.append(" ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TestNodeData.BehatScenario -> {
                    r.icon = PhpFileIcon.forFeature()
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    testAnnotations[data.originalTestName]?.let { r.append(" — $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    toolTipText = testDetails[data.originalTestName]
                }
                is TestNodeData.PhpUnitGroup -> {
                    r.icon = AllIcons.Nodes.TestGroup
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val count = node.childCount
                    if (count > 0) r.append(" ($count)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TestNodeData.PhpUnitMethod -> {
                    r.icon = AllIcons.Nodes.Method
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    testAnnotations[data.fullTestName]?.let { r.append(" — $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    toolTipText = testDetails[data.fullTestName]
                }
                is TestNodeData.UnnamedCoverage -> {
                    r.icon = AllIcons.General.Warning
                    r.append(data.displayName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TestNodeData.UnresolvedGroup -> {
                    r.icon = AllIcons.General.Warning
                    r.append(data.displayName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TestNodeData.Unresolved -> {
                    r.icon = AllIcons.General.Warning
                    r.append("${data.reference} — ${data.reason}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = data.reason
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
    private var testAnnotations: Map<String, String> = emptyMap()
    private var testDetails: Map<String, String> = emptyMap()
    private var testGitRoot: File? = null
    private var emptyMessage: String = "No tests"

    init {
        Disposer.register(lifetime, this)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? CheckedTreeNode ?: return
                    val testName = resolveFullTestName(node) ?: return
                    navigate(testName)
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
                    addActionListener { navigate(testName) }
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

    fun setTests(
        tests: List<String>, annotations: Map<String, String> = emptyMap(),
        details: Map<String, String> = emptyMap(), gitRoot: File? = null,
        sourceRevisions: Map<String, Set<String?>>? = null,
        includeLocal: Boolean = false,
    ) {
        val snapshot = coverageData.snapshot()
        val root = gitRoot ?: snapshot.gitRoot
        testGitRoot = root
        inputAnnotations = annotations
        inputDetails = details
        val ticket = ++resolutionTicket
        references = emptyList()
        problems = emptyList()
        allTests = emptyList()
        resolutionPending = tests.isNotEmpty()
        rebuild()
        if (tests.isEmpty()) return
        val requests = tests.distinct().flatMap { name ->
            val revisions = sourceRevisions?.get(name) ?: setOf(if (sourceRevisions == null) snapshot.commit else null)
            revisions.map { BehatTestReferences.Request(name, it) }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!alive()) return@executeOnPooledThread
            val resolvedRoot = root ?: if (includeLocal) localCoverage.gitRootOrNull() else null
            val result = try {
                val local = if (includeLocal) localCoverage.testFeatureSnapshots(tests.toSet()) else emptyMap()
                val missing = mutableListOf<BehatTestReferences.Problem>()
                val prepared = requests.mapNotNull { request ->
                    if (request.name in local && request.revision == snapshot.commit) {
                        local[request.name]?.let { request.copy(revision = null, source = it) } ?: run {
                            missing += BehatTestReferences.Problem(request, "Local run has no feature snapshot; collect coverage again")
                            null
                        }
                    } else request
                }
                val resolved = BehatReferenceSession(project, resolvedRoot).resolve(prepared)
                resolved.copy(unresolved = resolved.unresolved + missing)
            } catch (error: java.io.IOException) {
                log.warn("Cannot resolve test references: ${error.message}")
                BehatTestReferences.Result(emptyList(), requests.map { BehatTestReferences.Problem(it, error.message ?: "Source lookup failed") })
            }
            ApplicationManager.getApplication().invokeLater {
                if (!alive() || ticket != resolutionTicket) return@invokeLater
                testGitRoot = resolvedRoot
                resolutionPending = false
                applyResolution(result)
            }
        }
    }

    private fun alive(): Boolean = !disposed && !lifetime.isDisposed && !project.isDisposed

    override fun dispose() { disposed = true; resolutionTicket++ }

    private fun applyResolution(result: BehatTestReferences.Result, checked: Set<String>? = null) {
        references = result.resolved
        problems = result.unresolved
        allTests = result.names
        labels = references.associate { it.target to it.label.take(180) }
        testAnnotations = references.groupBy { it.target }.mapValues { (_, entries) ->
            entries.mapNotNull { inputAnnotations[it.origin.requested] }.distinct().joinToString(", ")
        }.filterValues { it.isNotEmpty() }
        testDetails = references.groupBy { it.target }.mapValues { (_, entries) ->
            entries.mapNotNull { inputDetails[it.origin.requested] }.distinct().joinToString("; ")
        }
        rebuild()
        if (checked != null) {
            fun restore(node: CheckedTreeNode): Boolean {
                val name = when (val data = node.userObject) {
                    is TestNodeData.BehatScenario -> data.originalTestName
                    is TestNodeData.PhpUnitMethod -> data.fullTestName
                    else -> null
                }
                val children = (0 until node.childCount).map { restore(node.getChildAt(it) as CheckedTreeNode) }
                node.isChecked = if (name != null) name in checked else children.isNotEmpty() && children.all { it }
                return node.isChecked
            }
            restore(rootNode)
            refreshCheckedCount()
        }
        toolbar.updateActionsAsync()
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

    fun hasAnyBehatTest(): Boolean = !resolutionPending && allTests.any { isBehatTest(it) }

    private fun refreshCheckedCount() {
        val count = checkedTestNames().size
        val ready = allTests.count { !CoverageTestIdentity.isAnonymous(it) }
        statusLabel.text = if (resolutionPending) "Resolving test references…"
            else "Ready: $ready · selected: $count · unresolved: ${problems.size}"
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
        runBehatTests(checkedTestNames(), debug)
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun rebuild() {
        populateTreeNodes(allTests, labels)
    }

    private fun populateTreeNodes(tests: List<String>, scenarioLabels: Map<String, String>) {
        rootNode.removeAllChildren()
        val namedTests = tests.filterNot(CoverageTestIdentity::isAnonymous)
        val (behatTests, phpunitTests) = namedTests.partition { isBehatTest(it) }

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
                    val displayPath = CoverageTestNavigator.toProjectRelativeFeaturePath(featurePath, project, testGitRoot)
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

        if (namedTests.size != tests.size) {
            rootNode.add(CheckedTreeNode(TestNodeData.UnnamedCoverage))
        }
        if (problems.isNotEmpty()) {
            val group = CheckedTreeNode(TestNodeData.UnresolvedGroup(problems.size))
            group.isChecked = false
            group.isEnabled = false
            for (problem in problems) {
                val name = problem.request.name + (problem.request.revision?.let { " @${it.take(8)}" } ?: "")
                group.add(CheckedTreeNode(TestNodeData.Unresolved(name, problem.reason)).apply {
                    isChecked = false
                    isEnabled = false
                })
            }
            rootNode.add(group)
        }
        if (rootNode.childCount == 0) rootNode.add(CheckedTreeNode(if (resolutionPending) "Resolving test references…" else emptyMessage))
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
            val displayPath = CoverageTestNavigator.toProjectRelativeFeaturePath(featurePath, project, testGitRoot)
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
        node.isChecked = checked && node.isEnabled
        for (i in 0 until node.childCount) {
            setAllChecked(node.getChildAt(i) as CheckedTreeNode, checked)
        }
    }

    private fun runTest(testName: String, debug: Boolean) {
        if (isBehatTest(testName)) {
            runBehatTests(setOf(testName), debug)
        } else {
            CoverageTestNavigator.navigateToTest(project, testName, testGitRoot)
        }
    }

    private fun runBehatTests(tests: Set<String>, debug: Boolean) {
        if (runAllInProgress || resolutionPending || !BehatTestRunner.isAvailable()) return
        prepareBehatRun(tests) { paths ->
            if (paths.isEmpty() || !alive()) return@prepareBehatRun
            runAllInProgress = true
            BehatTestRunner.runMultiplePathsWithCallback(project, paths, onFinished = { runAllInProgress = false }, debug = debug)
        }
    }

    /** Shared preparation for individual/bundled Run and Debug; no process is started here. */
    internal fun prepareBehatRun(tests: Set<String>, onPrepared: (Map<String, List<Int>>) -> Unit) {
        validate(tests.filterTo(linkedSetOf(), ::isBehatTest)) { ready -> onPrepared(buildPathsByFile(ready)) }
    }

    private fun navigate(testName: String) {
        if (!isBehatTest(testName)) {
            if (testName.endsWith(".feature")) CoverageTestNavigator.navigateToBehatTest(project, "$testName:1", testGitRoot)
            else CoverageTestNavigator.navigateToTest(project, testName, testGitRoot)
            return
        }
        validate(setOf(testName)) { ready ->
            ready.firstOrNull()?.let { CoverageTestNavigator.navigateToTest(project, it, testGitRoot) }
        }
    }

    private fun validate(tests: Set<String>, onValidated: (List<String>) -> Unit) {
        if (resolutionPending || runAllInProgress || !alive()) return
        val selected = references.filter { it.target in tests }
        if (selected.isEmpty()) { onValidated(emptyList()); return }
        val ticket = resolutionTicket
        val root = testGitRoot
        val previous = references
        val previousProblems = problems
        val checked = checkedTestNames()
        runAllInProgress = true
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!alive()) return@executeOnPooledThread
            val result = try {
                BehatReferenceSession(project, root).resolve(selected.map { it.origin })
            } catch (error: java.io.IOException) {
                BehatTestReferences.Result(emptyList(), selected.map { BehatTestReferences.Problem(it.origin, error.message ?: "Source lookup failed") })
            }
            ApplicationManager.getApplication().invokeLater {
                runAllInProgress = false
                if (!alive() || ticket != resolutionTicket) return@invokeLater
                val merged = BehatTestReferences.Result(previous.filter { it.target !in tests } + result.resolved,
                    (previousProblems + result.unresolved).distinct())
                val checkedOrigins = selected.filter { it.target in checked }.map { it.origin }.toSet()
                applyResolution(merged, (checked - tests) + result.resolved.filter { it.origin in checkedOrigins }.map { it.target })
                onValidated(result.names)
            }
        }
    }

    private fun canRunTest(testName: String): Boolean =
        isBehatTest(testName) && BehatTestRunner.isAvailable()

    private fun buildPathsByFile(tests: List<String>): Map<String, List<Int>> {
        val map = linkedMapOf<String, MutableList<Int>>()
        val wholeFile = mutableSetOf<String>()
        for (test in tests) {
            if (!isBehatTest(test)) continue
            val rawFeaturePath = test.substringBeforeLast(":", test)
            val lineStr = test.substringAfterLast(":", "")
            val lineNumber = lineStr.toIntOrNull()
            val vf = CoverageTestNavigator.resolveFeatureFile(project, rawFeaturePath, testGitRoot) ?: continue
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
