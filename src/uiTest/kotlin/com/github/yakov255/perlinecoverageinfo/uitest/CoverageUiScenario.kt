package com.github.yakov255.perlinecoverageinfo.uitest

import java.io.File
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher

/**
 * End-to-end scenario against a real IDE window and a real GitLab server.
 *
 * Start the IDE first (`./gradlew runIdeForUiTests` or `scripts/ui-test.sh`), then run
 * `./gradlew uiTest`. The sandbox config is wiped by the `uiTestSandbox` task, so the plugin
 * starts with no settings and the test has to configure it through the UI.
 *
 * Environment: `GITLAB_URL` (default https://gitlab.raketa.in), `GITLAB_TOKEN_FILE` or `GITLAB_TOKEN`, `GITLAB_PROJECT`
 * (default raketa/raketa), `COVERAGE_BRANCH` (default master), `UI_TEST_PHP_FILE`
 * (git-root-relative path of the file whose gutter is checked).
 */
@ExtendWith(CoverageUiScenario.DiagnosticsExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoverageUiScenario : IdeTestCase() {

    private val settings = CoverageSettingsDialog(ide)
    private val toolWindow = CoverageToolWindow(ide)
    private val editor = EditorPane(ide)

    private val phpFile: String = System.getenv("UI_TEST_PHP_FILE")
        ?: error("UI_TEST_PHP_FILE must be set to a git-root-relative PHP path")

    @BeforeAll
    fun configureIdeThroughTheSettingsDialog() {
        // Step 1-2: the sandbox has no plugin settings; configure everything from the UI.
        try {
            settings.fillFirstRunPrompt(TestSecrets.token)
                .open()
                .enablePlugin()
                .setDomain(env("GITLAB_URL", "gitlab.raketa.in"))
                .setToken(TestSecrets.token)
                .setBranch(env("COVERAGE_BRANCH", "master"))
                .selectProject(env("GITLAB_PROJECT_NAME", "raketa"), env("GITLAB_PROJECT", "raketa/raketa"))
                .applyAndClose()
        } catch (t: Throwable) {
            // TestWatcher does not see @BeforeAll failures: capture the screen and masked
            // hierarchy here, otherwise a broken setup leaves no diagnostics at all.
            captureDiagnostics("setup") { "setup failed: ${t.message}" }
            archiveIdeLog("setup")
            throw t
        }
    }

    /**
     * The bridge reports line numbers of the coverage commit without line mapping, so they match
     * the editor only while the fixture is identical (working tree included) to that commit.
     */
    private fun assertFixtureUnchangedSince(commit: String) {
        val project = env("UI_TEST_PROJECT", "${System.getProperty("user.home")}/projects/raketa")
        val process = ProcessBuilder("git", "-C", project, "diff", "--quiet", commit, "--", phpFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals(
            0,
            exit,
            "fixture $phpFile differs from coverage commit ${commit.take(8)} (git exit $exit: $output); " +
                "the bridge's line numbers would not match the editor — pick an unchanged file",
        )
    }

    @Test
    fun coverageIsLoadedAndRenderedInTheRealIde() {
        // Step 3: the daemon resolves the pipeline and downloads artifacts; this takes tens of
        // seconds, so wait on the bridge instead of a fixed sleep.
        val snapshot: CoverageSnapshot = awaitCoverageLoaded(Duration.ofSeconds(180))

        assertTrue(snapshot.isLoaded, "coverage state is '${snapshot.state}', error=${snapshot.error}")
        assertNotNull(snapshot.commit, "loaded coverage has no commit")
        assertTrue(snapshot.files > 0, "loaded coverage reports no files")
        assertTrue(snapshot.artifacts > 0, "no cached artifact for the project")

        // Step 4: the tool window exists with all five tabs and a populated Artifacts table.
        toolWindow.open()
        // findAllText() does not promise visual order, so compare the set of tab titles.
        assertEquals(
            CoverageToolWindow.EXPECTED_TABS.toSet(),
            toolWindow.tabTitles().toSet(),
            "unexpected tool window tabs",
        )

        val rows = toolWindow.artifactRows()
        assertTrue(rows.isNotEmpty(), "Artifacts table is empty")
        // The table lists commit, pipeline, branch, component, size and counts, not file names:
        // the artifact the plugin loaded must be one of its rows (by short commit hash).
        val loadedShort = snapshot.commit!!.take(8)
        assertTrue(
            rows.any { row -> row.any { it == loadedShort } },
            "loaded commit $loadedShort is not in the Artifacts table: $rows",
        )

        // Step 5: the file is covered and the bridge reports per-line classification.
        assertFixtureUnchangedSince(snapshot.commit!!)
        val lineCoverage = snapshot.lines[phpFile]
            ?: throw AssertionError("no coverage lines for $phpFile; known=${snapshot.lines.keys.take(5)}")
        assertTrue(lineCoverage.isNotEmpty(), "coverage has no mapped lines for $phpFile")
        val covered = lineCoverage.filterValues { it != "UNCOVERED" }
        val uncovered = lineCoverage.filterValues { it == "UNCOVERED" }
        assertTrue(covered.isNotEmpty(), "no covered line in $phpFile")
        assertTrue(uncovered.isNotEmpty(), "no uncovered line in $phpFile — the fixture is not representative")

        // Step 6: clicking a gutter icon populates the "Covering Line" panel with real tests.
        editor.openFile(phpFile)
        editor.clickGutterAtLine(covered.keys.first())
        toolWindow.selectTab("Covering Line")
        ide.await("Covering Line tab was not populated by a gutter click", Duration.ofSeconds(30), Duration.ofSeconds(1)) {
            toolWindow.toolWindow().findAllText().any { it.text.contains("Line", ignoreCase = true) && it.text.contains(":") }
        }
    }

    private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    /** Saves a screenshot and the IDE log next to the test report when the scenario fails. */
    class DiagnosticsExtension : TestWatcher {
        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            val name = context.requiredTestMethod.name
            ACTIVE?.captureDiagnostics(name) { "test: $name\ncause: ${cause?.message}" }
            ACTIVE?.archiveIdeLog(name)
        }

        companion object {
            @Volatile
            var ACTIVE: IdeTestCase? = null
        }
    }

    init {
        DiagnosticsExtension.ACTIVE = this
    }

    @Suppress("unused")
    private fun artifactsDir(): File = artifactDir()
}
