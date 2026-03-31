package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class CoverageTestsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel("No line selected")
    private val searchField = SearchTextField(false)
    private val rootNode = DefaultMutableTreeNode("Tests")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private var allTests: List<String> = emptyList()
    private var currentLineNumber: Int = 0
    private var currentFilePath: String = ""

    init {
        titleLabel.border = JBUI.Borders.empty(4, 6)

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterTests(searchField.text.trim())
            }
        })

        tree.isRootVisible = false
        tree.showsRootHandles = true

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
                    add(object : AnAction("Run Test") {
                        override fun actionPerformed(e: AnActionEvent) {
                            navigateToTest(testName)
                        }
                    })
                    add(object : AnAction("Go to Test") {
                        override fun actionPerformed(e: AnActionEvent) {
                            navigateToTest(testName)
                        }
                    })
                    addSeparator()
                    add(object : AnAction("Copy Test Name") {
                        override fun actionPerformed(e: AnActionEvent) {
                            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                            clipboard.setContents(StringSelection(testName), null)
                        }
                    })
                }
                val popupMenu = ActionManager.getInstance()
                    .createActionPopupMenu("CoverageTestsPanel", group)
                popupMenu.component.show(comp, x, y)
            }
        })

        val topPanel = JPanel(BorderLayout()).apply {
            add(titleLabel, BorderLayout.NORTH)
            add(searchField, BorderLayout.SOUTH)
        }

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    fun showTests(lineNumber: Int, filePath: String, tests: List<String>) {
        currentLineNumber = lineNumber
        currentFilePath = filePath
        allTests = tests

        val fileName = filePath.substringAfterLast("/")
        if (tests.isEmpty()) {
            titleLabel.text = "$fileName:$lineNumber — not covered"
        } else {
            titleLabel.text = "$fileName:$lineNumber — ${tests.size} test(s)"
        }

        searchField.text = ""
        buildTree(tests)
    }

    private fun filterTests(query: String) {
        if (query.isEmpty()) {
            buildTree(allTests)
        } else {
            val lower = query.lowercase()
            buildTree(allTests.filter { it.lowercase().contains(lower) })
        }
    }

    private fun buildTree(tests: List<String>) {
        rootNode.removeAllChildren()

        if (tests.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No tests covering this line"))
            treeModel.reload()
            return
        }

        // Group by class (part before ::)
        val grouped = tests.groupBy { name ->
            val fqcn = name.substringBeforeLast("::", "")
            fqcn.ifEmpty { "(no class)" }
        }

        for ((className, methods) in grouped.toSortedMap()) {
            val classNode = DefaultMutableTreeNode(className)
            for (fullName in methods) {
                val methodName = fullName.substringAfterLast("::", fullName)
                classNode.add(DefaultMutableTreeNode(methodName))
            }
            rootNode.add(classNode)
        }

        treeModel.reload()
        // Expand all nodes
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }

    /** Resolves a tree node back to the full test name (Class::method). */
    private fun resolveFullTestName(node: DefaultMutableTreeNode): String? {
        val parent = node.parent as? DefaultMutableTreeNode
        return when {
            // Leaf node under a class node → "ClassName::methodName"
            node.isLeaf && parent != null && parent != rootNode -> {
                "${parent.userObject}::${node.userObject}"
            }
            // Class node itself → just the class name
            !node.isLeaf && parent == rootNode -> {
                node.userObject.toString()
            }
            else -> null
        }
    }

    private fun navigateToTest(testName: String) {
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

    companion object {
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
