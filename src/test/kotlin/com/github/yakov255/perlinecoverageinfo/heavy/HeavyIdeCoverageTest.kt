package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.AffectedTestsService
import com.github.yakov255.perlinecoverageinfo.ChangedLinesAnalyzer
import com.github.yakov255.perlinecoverageinfo.Cov4Reader
import com.github.yakov255.perlinecoverageinfo.Cov4TestWriter
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.CoverageHighlighter
import com.github.yakov255.perlinecoverageinfo.CoverageMcpToolset
import com.github.yakov255.perlinecoverageinfo.CoverageTestsToolWindowFactory
import com.github.yakov255.perlinecoverageinfo.FileCoverageRenderer
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Heavy integration tests: they run inside a real IntelliJ Platform process with the plugin
 * installed and exercise the project services, the editor highlighter, the MCP toolset and a
 * real git repository. Run them with `./gradlew heavyTest`; plain `./gradlew test` skips this
 * package.
 */
class HeavyIdeCoverageTest : BasePlatformTestCase() {

    private val tempDirs = mutableListOf<File>()

    override fun tearDown() {
        try {
            CoverageDataService.getInstance(project).clear()
            tempDirs.forEach { it.deleteRecursively() }
            tempDirs.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testPluginIsLoadedAndRegistersTheCoverageToolWindow() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.github.yakov255.perlinecoverageinfo"))
        assertNotNull("the coverage plugin is not loaded in the IDE", plugin)

        val toolWindowEp = ToolWindowEP.EP_NAME.extensionList.firstOrNull { it.id == "Coverage Tests" }
        assertNotNull("the Coverage Tests tool window extension is not registered", toolWindowEp)
        assertEquals(
            CoverageTestsToolWindowFactory::class.java.name,
            toolWindowEp!!.factoryClass,
        )
    }

    fun testHighlighterMarksCoverageFromCov4InRealEditor() {
        val virtualFile = createRealFile("Sample.txt", "alpha\nbeta\ngamma")
        val projectRoot = File(project.basePath!!)
        val cov4 = writeCov4(
            mapOf(
                "Sample.txt" to mapOf(
                    1 to listOf("ATest::testAlpha"),
                    2 to emptyList(),
                    3 to listOf("ATest::testGamma"),
                ),
            ),
        )

        val data = CoverageDataService.getInstance(project)
        data.setCoverageContext(FIXTURE_COMMIT, projectRoot)
        data.setCov4Reader(Cov4Reader(cov4))

        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        assertNotNull("no document for ${virtualFile.path}", document)
        val editor = EditorFactory.getInstance().createEditor(document!!, project)

        try {
            CoverageHighlighter.applyToEditor(editor, project)

            waitUntil("coverage highlighter is applied to the editor") {
                editor.markupModel.allHighlighters.any {
                    it.getUserData(CoverageHighlighter.COVERAGE_HIGHLIGHTER_KEY) == true
                }
            }

            val highlighter = editor.markupModel.allHighlighters.first {
                it.getUserData(CoverageHighlighter.COVERAGE_HIGHLIGHTER_KEY) == true
            }
            assertTrue(
                "expected a FileCoverageRenderer, got ${highlighter.lineMarkerRenderer}",
                highlighter.lineMarkerRenderer is FileCoverageRenderer,
            )
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }

        assertEquals(listOf("ATest::testAlpha"), data.getCoverage("Sample.txt")?.get(1))
        assertTrue("line 2 must be uncovered", data.getCoverage("Sample.txt")?.get(2)!!.isEmpty())
    }

    fun testAffectedTestsJoinRealGitDiffWithCov4() {
        val repo = Files.createTempDirectory("heavy-git").toFile()
        tempDirs += repo
        git(repo, "init", "-q")
        git(repo, "config", "user.email", "heavy-test@example.invalid")
        git(repo, "config", "user.name", "Heavy Test")

        val source = File(repo, "src/Service.php")
        source.parentFile.mkdirs()
        source.writeText((1..5).joinToString("\n") { "line $it" })
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "initial")
        val commit = git(repo, "rev-parse", "HEAD")

        // Touch line 3 in the working tree; the coverage commit still describes the old file.
        source.writeText((1..5).joinToString("\n") { if (it == 3) "changed $it" else "line $it" })

        val cov4 = writeCov4(
            mapOf(
                "src/Service.php" to (1..5).associateWith { listOf("ATest::test$it") },
            ),
        )
        val data = CoverageDataService.getInstance(project)
        data.setCoverageContext(commit, repo)
        data.setCov4Reader(Cov4Reader(cov4))

        val result = AffectedTestsService.getInstance(project)
            .compute(ChangedLinesAnalyzer.DiffMode.WORKING_TREE)

        assertEquals(setOf("ATest::test3"), result.tests)
        assertEquals(setOf("ATest::test3"), result.perFile["src/Service.php"])
    }

    fun testMcpToolReportsCoverageInsideTheRunningIde() {
        createRealFile("Sample.php", "<?php\necho 1;\n")
        val projectRoot = File(project.basePath!!)
        val cov4 = writeCov4(
            mapOf(
                "Sample.php" to mapOf(
                    1 to listOf("ATest::testBoot"),
                    2 to emptyList(),
                ),
            ),
        )
        val data = CoverageDataService.getInstance(project)
        data.setCoverageContext(FIXTURE_COMMIT, projectRoot)
        data.setCov4Reader(Cov4Reader(cov4))

        val result = runSuspend {
            CoverageMcpToolset().get_coverage_for_file(
                project = project.basePath,
                file_path = "Sample.php",
                limit = 10,
            )
        }

        assertEquals(2, result.totalLinesInFile)
        assertEquals(1, result.coveredLinesInFile)
        assertTrue(result.lines.first { it.lineNumber == 1 }.isCovered)
        assertFalse(result.lines.first { it.lineNumber == 2 }.isCovered)
    }

    // --- helpers ---------------------------------------------------------------

    private fun writeCov4(coverage: Map<String, Map<Int, List<String>>>): File {
        val file = Files.createTempFile("heavy-coverage", ".cov4").toFile()
        tempDirs += file
        Cov4TestWriter.write(coverage, file)
        return file
    }

    /** Creates a file on disk under the project root and returns its refreshed virtual file. */
    private fun createRealFile(name: String, text: String): VirtualFile {
        val root = File(project.basePath!!).apply { mkdirs() }
        val ioFile = File(root, name).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: error("cannot create $name under $root")
    }

    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals("git ${args.joinToString(" ")} failed:\n$output", 0, process.waitFor())
        return output
    }

    private fun waitUntil(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(message, condition())
    }

    /** Runs a suspend function that has no real suspension points from a plain JUnit test. */
    private fun <T> runSuspend(block: suspend () -> T): T {
        val future = CompletableFuture<T>()
        block.startCoroutine(
            Continuation(EmptyCoroutineContext) { result ->
                result.fold({ future.complete(it) }, { future.completeExceptionally(it) })
            },
        )
        return future.get(30, TimeUnit.SECONDS)
    }

    companion object {
        private const val FIXTURE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
