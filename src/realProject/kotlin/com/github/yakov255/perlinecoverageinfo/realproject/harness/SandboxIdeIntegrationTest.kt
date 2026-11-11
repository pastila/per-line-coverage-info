package com.github.yakov255.perlinecoverageinfo.realproject.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The real IDE smoke test: start one sandbox IDE on a throwaway project, wait for the plugin and
 * the bridge snapshot, then verify that [SandboxIde.stop] ends exactly this process.
 *
 * Skipped unless explicitly asked for: a full platform start takes a minute and needs a display,
 * a license and a prepared sandbox (`./gradlew prepareSandbox`). Run it manually with
 *
 * ```
 * REAL_PROJECT_IDE=true ./gradlew realProjectTest --tests '*SandboxIdeIntegrationTest*'
 * ```
 *
 * (`-DrealProject.ide=true` works too: the Gradle `Test` task does not forward arbitrary `-D`
 * flags, so the environment variable or `JAVA_TOOL_OPTIONS=-DrealProject.ide=true` is the
 * practical way to set it.)
 *
 * The project is a temporary directory with one PHP file: this test must never open the real
 * raketa checkout or `/tmp/raketa-load`.
 */
class SandboxIdeIntegrationTest {

    private val workDirs = mutableListOf<java.io.File>()

    /** Each test creates a sandbox under /tmp (RAM-backed): never leave it behind. */
    @After
    fun deleteWorkDirs() {
        workDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `starts a sandbox ide writes a bridge snapshot and stops only its own process`() {
        assumeTrue(
            "real IDE run not requested (set REAL_PROJECT_IDE=true or -DrealProject.ide=true)",
            System.getProperty("realProject.ide") == "true" || System.getenv("REAL_PROJECT_IDE") == "true",
        )
        val projectRoot = SandboxIde.projectRoot()
        val ideaHome = SandboxIde.findIdeaHome(projectRoot)
        assumeTrue("no prepared IntelliJ platform in the Gradle cache", ideaHome != null)
        assumeTrue(
            "no prepared sandbox; run ./gradlew prepareSandbox first",
            SandboxIde.findSandboxPluginsDir(projectRoot) != null,
        )

        val work = Files.createTempDirectory("rp-ide-smoke").toFile().also { workDirs += it }
        val project = File(work, "project").apply { mkdirs() }
        File(project, "index.php").writeText("<?php\n\necho 'ok';\n")
        val cacheRoot = File(work, "shared-cache").apply { mkdirs() }

        val context = RunContext.create("ide-smoke")
        context.writeMachineInfo(mapOf("ideaHome" to ideaHome!!.absolutePath))
        context.event("smoke", "start", "starting sandbox IDE", jsonObjectOf("project" to project.absolutePath))

        // An unrelated process that must survive the stop: proves stop() is per-PID, not a sweep.
        val bystander = ProcessBuilder("sleep", "120").start()
        var ide: SandboxIde? = null
        val sampler = ProcSampler(context.samplesFile, cacheRoot)
        try {
            val started = SandboxIde.start(
                name = "smoke-${System.nanoTime() % 100_000}",
                projectDir = project,
                proxyBaseUrl = "http://127.0.0.1:1",
                options = SandboxIde.Options(
                    ideaHome = ideaHome,
                    cacheRoot = cacheRoot,
                    uiTestFiles = listOf("index.php"),
                ),
            )
            ide = started
            context.event("smoke", "started", "IDE process", jsonObjectOf("pid" to started.pid.toString()))
            sampler.add("ide-1", started.pid)
            sampler.start()
            val polling = context.startSnapshotPolling(started::readBridge)

            val snapshot = try {
                started.awaitReady(timeoutMs = 300_000)
            } finally {
                polling.close()
            }
            assertNotNull("bridge snapshot must exist after awaitReady", snapshot)
            assertTrue("plugin must be loaded", ide.pluginLoaded())
            assertTrue("IDE process must still be alive", started.isAlive())
            context.event(
                "smoke",
                "ready",
                "plugin loaded and bridge answered",
                jsonObjectOf("state" to snapshot.state.orEmpty()),
            )

            // Samples exist for the report: at least one row for the IDE.
            sampler.sampleOnce()
            assertTrue(context.samplesFile.readLines().size >= 2)
            context.copyPerfLogs(started.prepared.perfDir)
            context.copyCovdResponses(started.prepared.covdResponsesDir)
        } finally {
            sampler.stop()
            ide?.stop()
            ide?.rootDir?.let { workDirs += it }
            context.event("smoke", "stopped", "IDE stopped", jsonObjectOf("pid" to (ide?.pid ?: -1).toString()))
            assertFalse("stop() must end the IDE process", ide?.isAlive() == true)
            assertTrue("an unrelated process must survive stop()", bystander.isAlive)
            bystander.destroyForcibly()
            bystander.waitFor(5, TimeUnit.SECONDS)
            context.close()
        }
    }

    /**
     * Two IDE instances must not see each other's state, but must share the prepared plugin
     * directory and the coverage cache: that is the setup scenario R7 builds on. Stopping one
     * instance leaves the other running.
     */
    @Test
    fun `two instances keep separate state and one stop does not touch the other`() {
        assumeTrue(
            "real IDE run not requested (set REAL_PROJECT_IDE=true or -DrealProject.ide=true)",
            System.getProperty("realProject.ide") == "true" || System.getenv("REAL_PROJECT_IDE") == "true",
        )
        val projectRoot = SandboxIde.projectRoot()
        val ideaHome = SandboxIde.findIdeaHome(projectRoot)
        assumeTrue("no prepared IntelliJ platform in the Gradle cache", ideaHome != null)
        assumeTrue(
            "no prepared sandbox; run ./gradlew prepareSandbox first",
            SandboxIde.findSandboxPluginsDir(projectRoot) != null,
        )

        val work = Files.createTempDirectory("rp-ide-pair").toFile().also { workDirs += it }
        val projects = (1..2).map { index ->
            File(work, "project-$index").apply { mkdirs() }
                .also { File(it, "index.php").writeText("<?php\n\necho $index;\n") }
        }
        val cacheRoot = File(work, "shared-cache").apply { mkdirs() }
        val suffix = System.nanoTime() % 100_000
        val first = SandboxIde.start(
            "pair-a-$suffix", projects[0], "http://127.0.0.1:1",
            SandboxIde.Options(ideaHome = ideaHome, cacheRoot = cacheRoot),
        )
        var second: SandboxIde? = null
        try {
            second = SandboxIde.start(
                "pair-b-$suffix", projects[1], "http://127.0.0.1:1",
                SandboxIde.Options(ideaHome = ideaHome, cacheRoot = cacheRoot),
            )
            assertTrue(first.pid != second.pid)
            assertTrue(first.stateFile != second.stateFile)
            assertEquals(cacheRoot, first.prepared.cacheRoot)
            assertEquals(cacheRoot, second.prepared.cacheRoot)
            assertEquals(first.prepared.pluginsDir, second.prepared.pluginsDir)

            val firstSnapshot = first.awaitReady(timeoutMs = 300_000)
            val secondSnapshot = second.awaitReady(timeoutMs = 300_000)
            assertNotNull(firstSnapshot)
            assertNotNull(secondSnapshot)

            first.stop()
            assertFalse("stopped instance must be gone", first.isAlive())
            assertTrue("the other instance must keep running", second.isAlive())
            assertNotNull("the other bridge must keep answering", second.readBridge())
        } finally {
            first.stop()
            second?.stop()
            workDirs += first.rootDir
            second?.rootDir?.let { workDirs += it }
            assertFalse(second?.isAlive() == true)
        }
    }
}
