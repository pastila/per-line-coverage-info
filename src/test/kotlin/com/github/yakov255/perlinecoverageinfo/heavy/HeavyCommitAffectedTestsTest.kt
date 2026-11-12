package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.AffectedTestsPanel
import com.github.yakov255.perlinecoverageinfo.CoverageCacheService
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.ShowCommitAffectedTestsAction
import com.github.yakov255.perlinecoverageinfo.TestNodeData
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.VcsFullCommitDetails
import com.intellij.vcs.log.VcsLogCommitSelection
import com.intellij.vcs.log.VcsLogDataKeys
import com.intellij.vcs.log.impl.HashImpl
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.util.function.Consumer
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.tree.TreePath

class HeavyCommitAffectedTestsTest : BasePlatformTestCase() {
    private lateinit var repo: File
    override fun setUp() {
        super.setUp()
        repo = Files.createTempDirectory("heavy-commit-tests-").toFile()
        CoverageCacheService.cacheRootOverride = File(repo, "cache")
        CoverageDataService.getInstance(project).clear()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.invalid")
        git("config", "commit.gpgsign", "false")
    }
    override fun tearDown() {
        try {
            CoverageCacheService.cacheRootOverride = null
            repo.deleteRecursively()
        } finally { super.tearDown() }
    }

    fun testGitLogActionIsRegisteredAndSupportsMultipleCommitsFromOneRoot() {
        val action = ActionManager.getInstance().getAction("ShowCommitAffectedTests")
        assertInstanceOf(action, ShowCommitAffectedTestsAction::class.java)
        val group = ActionManager.getInstance().getAction("Vcs.Log.ContextMenu") as DefaultActionGroup
        assertTrue(group.getChildren(null).contains(action))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repo)!!
        val first = CommitId(HashImpl.build("a".repeat(40)), root)
        val second = CommitId(HashImpl.build("b".repeat(40)), root)
        fun enabled(commits: List<CommitId>): Boolean {
            val context = SimpleDataContext.builder().add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                .add(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION, selection(commits)).build()
            val event = TestActionEvent.createTestEvent(action, context)
            action.update(event)
            return event.presentation.isEnabledAndVisible
        }
        assertTrue(enabled(listOf(first)))
        assertTrue(enabled(listOf(first, second)))
        assertFalse(enabled(emptyList()))
        val anotherRoot = root.parent
        assertFalse(enabled(listOf(first, CommitId(second.hash, anotherRoot))))
    }

    fun testPanelKeepsLatestSelectionAndF5RefreshesThoseCommits() {
        write("features/One.feature", "Feature: one\n  Scenario: original\n    Given one\n")
        commit()
        write("features/One.feature", "Feature: one\n  Scenario: original\n    Given changed\n")
        val first = commit()
        write("features/Two.feature", "Feature: two\n  Scenario: added\n    Given two\n")
        val second = commit()
        val panel = AffectedTestsPanel(project)
        try {
            panel.showCommits(repo, listOf(first))
            panel.showCommits(repo, listOf(second))
            waitFor { testNames(panel) == setOf("features/Two.feature:2") }
            val refresh = panel.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0))
            assertNotNull(refresh)
            refresh.actionPerformed(ActionEvent(panel, 0, "refresh"))
            waitFor { testNames(panel) == setOf("features/Two.feature:2") }
            val tree = children(panel).filterIsInstance<CheckboxTree>().first { tree ->
                (tree.model.root as CheckedTreeNode).depthFirstEnumeration().asSequence()
                    .any { (it as CheckedTreeNode).userObject is TestNodeData.BehatScenario }
            }
            val leaf = (tree.model.root as CheckedTreeNode).depthFirstEnumeration().asSequence()
                .map { it as CheckedTreeNode }.first { it.userObject is TestNodeData.BehatScenario }
            val renderer = tree.cellRenderer as CheckboxTree.CheckboxTreeCellRenderer
            renderer.getTreeCellRendererComponent(tree, leaf, false, false, true, 0, false)
            assertTrue(renderer.textRenderer.toString().contains("Added test"))
            assertTrue(renderer.toolTipText.contains(second.take(8)))
            assertNull("No coverage context is needed for navigation", CoverageDataService.getInstance(project).gitRoot)
            tree.selectionPath = TreePath(leaf.path)
            val click = MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 2, false)
            tree.mouseListeners.forEach { it.mouseClicked(click) }
            waitFor { FileEditorManager.getInstance(project).selectedTextEditor != null }
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            assertNotNull(editor)
            assertEquals(File(repo, "features/Two.feature").path,
                FileDocumentManager.getInstance().getFile(editor!!.document)!!.path)
            assertEquals(1, editor.caretModel.logicalPosition.line)
        } finally { Disposer.dispose(panel) }
    }

    private fun selection(selected: List<CommitId>) = object : VcsLogCommitSelection {
        override val rows: IntArray get() = selected.indices.toList().toIntArray()
        override val ids: List<Int> get() = selected.indices.toList()
        override val commits: List<CommitId> get() = selected
        override val cachedMetadata: List<VcsCommitMetadata> get() = emptyList()
        override val cachedFullDetails: List<VcsFullCommitDetails> get() = emptyList()
        override fun requestFullDetails(consumer: Consumer<in List<VcsFullCommitDetails>>) { consumer.accept(emptyList()) }
    }
    private fun children(container: Container): List<java.awt.Component> =
        container.components.flatMap { listOf(it) + if (it is Container) children(it) else emptyList() }
    private fun testNames(panel: JComponent): Set<String> = children(panel).filterIsInstance<CheckboxTree>().flatMap { tree ->
        (tree.model.root as CheckedTreeNode).depthFirstEnumeration().asSequence()
            .mapNotNull { ((it as CheckedTreeNode).userObject as? TestNodeData.BehatScenario)?.originalTestName }.toList()
    }.toSet()
    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < until) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Commit tests were not populated")
    }
    private fun write(path: String, text: String) { File(repo, path).apply { parentFile.mkdirs(); writeText(text) } }
    private fun commit(): String { git("add", "-A"); git("commit", "-qm", "test"); return git("rev-parse", "HEAD").trim() }
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return output
    }
}
