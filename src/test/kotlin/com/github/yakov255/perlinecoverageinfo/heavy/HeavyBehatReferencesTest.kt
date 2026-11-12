package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.TestNodeData
import com.github.yakov255.perlinecoverageinfo.TestTreeView
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.CheckedTreeNode
import java.io.File
import java.nio.file.Files
import javax.swing.tree.TreePath

/** Real git sources, current/unsaved documents, and the same preparation used by Run/Debug. No Behat process. */
class HeavyBehatReferencesTest : BasePlatformTestCase() {
    private lateinit var repo: File
    private val path = "features/Test.feature"
    private val source = "Feature: test\n  Scenario Outline: selected\n    Given <value>\n    Examples:\n      | value |\n      | one   |\n      | two   |\n"

    override fun setUp() {
        super.setUp()
        repo = Files.createTempDirectory("heavy-behat-references-").toFile()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.invalid")
        git("config", "commit.gpgsign", "false")
    }
    override fun tearDown() {
        try { repo.deleteRecursively() } finally { super.tearDown() }
    }

    fun testHistoricalRowAndUnsavedLineShiftStayOneConcreteExampleAtLaunch() {
        val revision = commit(source)
        commit("\n\n$source")
        val view = TestTreeView(project)
        try {
            view.setTests(listOf("$path:6"), gitRoot = repo, sourceRevisions = mapOf("$path:6" to setOf(revision)))
            waitFor { view.checkedTestNames() == setOf("$path:8") }
            val document = FileDocumentManager.getInstance().getDocument(feature())!!
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "\n") }
            var prepared: Map<String, List<Int>>? = null
            view.prepareBehatRun(view.checkedTestNames()) { prepared = it }
            waitFor { prepared != null }
            assertEquals(mapOf(File(repo, path).path to listOf(9)), prepared)
            assertEquals(setOf("$path:9"), view.checkedTestNames())
            assertFalse("An example must never become the outline header", prepared!!.values.flatten().contains(5))
        } finally { Disposer.dispose(view) }
    }

    fun testExampleDeletedAfterDisplayIsExcludedFromLaunchWithAnExplicitReason() {
        val revision = commit(source)
        val view = TestTreeView(project)
        try {
            view.setTests(listOf("$path:2"), gitRoot = repo, sourceRevisions = mapOf("$path:2" to setOf(revision)))
            waitFor { view.checkedTestNames() == setOf("$path:6", "$path:7") }
            val document = FileDocumentManager.getInstance().getDocument(feature())!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText(source.replace("      | one   |\n", "")) }
            var prepared: Map<String, List<Int>>? = null
            view.prepareBehatRun(setOf("$path:6")) { prepared = it }
            waitFor { prepared != null }
            assertTrue(prepared!!.isEmpty())
            val unresolved = nodes(view).mapNotNull { it.userObject as? TestNodeData.Unresolved }
            assertTrue(unresolved.any { it.reason.contains("deleted or changed") })
            assertFalse(view.checkedTestNames().contains("$path:6"))
        } finally { Disposer.dispose(view) }
    }

    fun testInvalidHeadersAndStepsAreSeparateFromSelectableTests() {
        commit(source)
        val view = TestTreeView(project)
        try {
            view.setTests(listOf("$path:5", "$path:3", "$path:6"), gitRoot = repo, sourceRevisions = emptyMap())
            waitFor { nodes(view).count { it.userObject is TestNodeData.Unresolved } == 2 }
            view.selectAllTests()
            assertEquals(setOf("$path:6"), view.checkedTestNames())
            val unresolved = nodes(view).first { it.userObject is TestNodeData.Unresolved }
            assertFalse(unresolved.isEnabled)
            assertFalse(unresolved.isChecked)
            fun labels(container: java.awt.Container): List<String> = container.components.flatMap {
                (if (it is javax.swing.JLabel) listOf(it.text.orEmpty()) else emptyList()) +
                    (if (it is java.awt.Container) labels(it) else emptyList())
            }
            assertTrue(labels(view.component).any { it.contains("Ready: 1") && it.contains("unresolved: 2") })
            view.tree.selectionPath = TreePath(unresolved.path)
            assertTrue(view.collectSelectedTestNames().isEmpty())
        } finally { Disposer.dispose(view) }
    }

    fun testCheckingOneExampleOfAWholeOutlineDoesNotExpandTheLaunchToUncheckedRows() {
        val revision = commit(source)
        val view = TestTreeView(project)
        try {
            view.setTests(listOf("$path:2"), gitRoot = repo, sourceRevisions = mapOf("$path:2" to setOf(revision)))
            waitFor { view.checkedTestNames().size == 2 }
            view.deselectAllTests()
            val second = nodes(view).single { (it.userObject as? TestNodeData.BehatScenario)?.originalTestName == "$path:7" }
            second.isChecked = true
            var prepared: Map<String, List<Int>>? = null
            view.prepareBehatRun(view.checkedTestNames()) { prepared = it }
            waitFor { prepared != null }
            assertEquals(mapOf(File(repo, path).path to listOf(7)), prepared)
        } finally { Disposer.dispose(view) }
    }

    private fun nodes(view: TestTreeView): List<CheckedTreeNode> =
        (view.tree.model.root as CheckedTreeNode).depthFirstEnumeration().asSequence().map { it as CheckedTreeNode }.toList()
    private fun feature() = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(repo, path))!!
    private fun commit(text: String): String {
        File(repo, path).apply { parentFile.mkdirs(); writeText(text) }
        git("add", "-A"); git("commit", "-qm", "test")
        return git("rev-parse", "HEAD").trim()
    }
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return output
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < until) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Test references were not prepared")
    }
}
