package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.nio.file.Paths

/**
 * Runs Behat tests through the Behat plugin of the IDE ([BehatLauncher]): PhpStorm's or Raketa's.
 * Everything here uses the platform API only.
 */
object BehatTestRunner {

    private val log = CoverageLog.get(BehatTestRunner::class.java)

    /** True when the IDE has a supported Behat plugin. */
    fun isAvailable(): Boolean = BehatLauncher.getInstance() != null

    /** Runs the scenario that starts at [line] (1-based) of [featureFilePath]. */
    fun runScenario(project: Project, featureFilePath: String, line: Int, scenarioName: String, debug: Boolean = false) {
        val settings = BehatLauncher.getInstance()?.createScenarioConfig(project, featureFilePath, line, scenarioName) ?: return
        execute(settings, debug)
    }

    fun runFeatureFile(project: Project, featureFilePath: String, debug: Boolean = false) {
        val settings = BehatLauncher.getInstance()?.createFeatureFileConfig(project, featureFilePath) ?: return
        execute(settings, debug)
    }

    /**
     * Bundles feature files / scenarios into Behat launches — one per Behat project (a
     * monorepo has several: `core/behat.yml`, `api/rail/behat.yml`, `api/bus/behat.php`, …).
     * [pathsByFile] maps a feature file absolute path to the list of 1-based scenario line
     * numbers to run; an empty list means "run the entire file".
     *
     * Each launch is pinned to the config file that owns its feature files, and the paths are
     * made relative to that config's directory (see [pathArguments]).
     */
    private fun createMultiPathsConfigs(
        project: Project,
        pathsByFile: Map<String, List<Int>>
    ): List<RunnerAndConfigurationSettings> {
        if (pathsByFile.isEmpty()) return emptyList()
        val launcher = BehatLauncher.getInstance() ?: return emptyList()
        val byBehatProject = pathsByFile.entries.groupBy {
            BehatConfigLocator.locate(it.key, project.basePath)
        }
        if (byBehatProject.size > 1) {
            log.info("BehatTestRunner: paths span ${byBehatProject.size} Behat projects — launching them one after another")
        }
        return byBehatProject.mapNotNull { (behatProject, entries) ->
            val paths = entries.associate { it.key to it.value }
            val name = if (paths.size == 1) {
                "Behat: ${paths.keys.first().substringAfterLast("/")}"
            } else {
                "Behat: ${paths.size} paths"
            }
            if (behatProject == null) {
                log.warn("BehatTestRunner: no behat config found for ${paths.keys.first()} — falling back to the run configuration template")
            }
            launcher.createPathsConfig(project, name, paths, behatProject)
        }
    }

    /**
     * Positional Behat arguments for [pathsByFile], relative to [workingDir]: Behat resolves relative
     * specification paths against the directory it runs in, so a path relative to anything else fails
     * with "No specifications found".
     *
     * Example: `src/Features/a.feature:10 src/Features/a.feature:20 src/Features/b.feature`
     */
    fun pathArguments(pathsByFile: Map<String, List<Int>>, workingDir: String): String =
        buildPositionalPathArgsInternal(relativizePaths(pathsByFile, workingDir))

    /**
     * Converts absolute paths in [pathsByFile] to paths relative to [workingDir].
     * If a path cannot be relativized (e.g. on a different root), it is kept as-is.
     */
    private fun relativizePaths(
        pathsByFile: Map<String, List<Int>>,
        workingDir: String,
    ): Map<String, List<Int>> {
        val base = Paths.get(workingDir)
        return pathsByFile.mapKeys { (absPath, _) ->
            try {
                base.relativize(Paths.get(absPath)).toString()
            } catch (_: IllegalArgumentException) {
                absPath
            }
        }
    }

    /**
     * Runs multiple feature files / scenarios in a single Behat launch — or, when they belong
     * to different Behat projects, in one launch per project, started one after another.
     * See [createMultiPathsConfigs] for the [pathsByFile] format.
     */
    fun runMultiplePaths(
        project: Project,
        pathsByFile: Map<String, List<Int>>,
        debug: Boolean = false
    ) {
        val configs = createMultiPathsConfigs(project, pathsByFile)
        if (configs.isEmpty()) return
        executeSequentially(project, configs, debug) {}
    }

    /**
     * Same as [runMultiplePaths] but invokes [onFinished] once every launch has completed,
     * with the first non-zero exit code (or -1 if nothing could be started).
     */
    fun runMultiplePathsWithCallback(
        project: Project,
        pathsByFile: Map<String, List<Int>>,
        onFinished: (Int) -> Unit,
        debug: Boolean = false
    ) {
        val configs = createMultiPathsConfigs(project, pathsByFile)
        if (configs.isEmpty()) {
            onFinished(-1)
            return
        }
        executeSequentially(project, configs, debug, onFinished)
    }

    /**
     * Starts the launches one at a time — Behat suites of a monorepo share a single test
     * database, so two processes must never run in parallel.
     */
    private fun executeSequentially(
        project: Project,
        configs: List<RunnerAndConfigurationSettings>,
        debug: Boolean,
        onFinished: (Int) -> Unit
    ) {
        fun step(index: Int, worstExitCode: Int) {
            if (index >= configs.size) {
                onFinished(worstExitCode)
                return
            }
            executeWithCallback(project, configs[index], { exitCode ->
                val worst = if (worstExitCode != 0) worstExitCode else exitCode
                ApplicationManager.getApplication().invokeLater { step(index + 1, worst) }
            }, debug)
        }
        step(0, 0)
    }

    private fun execute(settings: RunnerAndConfigurationSettings, debug: Boolean) {
        val project = settings.configuration.project
        val runManager = RunManager.getInstance(project)
        settings.isTemporary = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, executor(debug))
    }

    private fun executeWithCallback(
        project: Project,
        settings: RunnerAndConfigurationSettings,
        onFinished: (Int) -> Unit,
        debug: Boolean = false
    ) {
        val runManager = RunManager.getInstance(project)
        settings.isTemporary = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor = executor(debug)
        val runner = ProgramRunner.getRunner(executor.id, settings.configuration)
        if (runner == null) {
            onFinished(-1)
            return
        }
        try {
            val callback = ProgramRunner.Callback { descriptor ->
                val handler = descriptor.processHandler
                if (handler == null) {
                    onFinished(-1)
                    return@Callback
                }
                handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        onFinished(event.exitCode)
                    }

                    override fun startNotified(event: ProcessEvent) = Unit
                    override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) = Unit
                    override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) = Unit
                })
            }
            val env = ExecutionEnvironmentBuilder.create(executor, settings).build(callback)
            runner.execute(env)
        } catch (e: Throwable) {
            log.warn("BehatTestRunner: executeWithCallback failed: ${e.message}")
            onFinished(-1)
        }
    }

    private fun executor(debug: Boolean): Executor =
        if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
}

internal fun buildPositionalPathArgsForTest(relativePaths: Map<String, List<Int>>): String {
    return buildPositionalPathArgsInternal(relativePaths)
}

private fun buildPositionalPathArgsInternal(relativePaths: Map<String, List<Int>>): String {
    val args = mutableListOf<String>()
    relativePaths.forEach { (file, lines) ->
        if (lines.isEmpty()) {
            // If no specific lines, just add the file
            args += quoteIfNeeded(file)
        } else {
            // For each line, create a separate argument
            lines.forEach { line ->
                args += quoteIfNeeded("$file:$line")
            }
        }
    }
    return args.joinToString(" ")
}

private fun quoteIfNeeded(path: String): String {
    return if (path.any { it.isWhitespace() }) "\"$path\"" else path
}
