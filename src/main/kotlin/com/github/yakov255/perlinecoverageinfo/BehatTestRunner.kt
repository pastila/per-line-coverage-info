package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import com.jetbrains.php.behat.run.BehatRunConfiguration
import com.jetbrains.php.behat.run.BehatRunConfigurationType
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

object BehatTestRunner {

    fun runScenario(project: Project, featureFilePath: String, scenarioName: String, debug: Boolean = false) {
        val settings = createScenarioConfig(project, featureFilePath, scenarioName) ?: return
        execute(project, settings, debug)
    }

    fun runFeatureFile(project: Project, featureFilePath: String, debug: Boolean = false) {
        val settings = createFeatureFileConfig(project, featureFilePath) ?: return
        execute(project, settings, debug)
    }

    private fun createScenarioConfig(
        project: Project, featureFilePath: String, scenarioName: String
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        val configType = BehatRunConfigurationType.getInstance()
        val factory = configType.configurationFactories.firstOrNull() ?: return null
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("Behat: $scenarioName", factory)
        val config = settings.configuration as? BehatRunConfiguration ?: return null

        val runnerSettings = config.settings.runnerSettings
        runnerSettings.scope = PhpTestRunnerSettings.Scope.Method
        runnerSettings.filePath = featureFilePath
        runnerSettings.methodName = scenarioName
        return settings
    }

    private fun createFeatureFileConfig(
        project: Project, featureFilePath: String
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        val configType = BehatRunConfigurationType.getInstance()
        val factory = configType.configurationFactories.firstOrNull() ?: return null
        val runManager = RunManager.getInstance(project)
        val fileName = featureFilePath.substringAfterLast("/")
        val settings = runManager.createConfiguration("Behat: $fileName", factory)
        val config = settings.configuration as? BehatRunConfiguration ?: return null

        val runnerSettings = config.settings.runnerSettings
        runnerSettings.scope = PhpTestRunnerSettings.Scope.File
        runnerSettings.filePath = featureFilePath
        return settings
    }

    /**
     * Bundles multiple feature files / scenarios into a single Behat launch using the
     * custom `--paths` option. [pathsByFile] maps a feature file absolute path to the
     * list of 1-based scenario line numbers to run; an empty list means "run the
     * entire file".
     *
     * Example produced CLI options (as a single string passed via test runner options):
     *   --paths features/a.feature:10,20 --paths features/b.feature
     */
    private fun createMultiPathsConfig(
        project: Project,
        pathsByFile: Map<String, List<Int>>
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        if (pathsByFile.isEmpty()) return null
        val configType = BehatRunConfigurationType.getInstance()
        val factory = configType.configurationFactories.firstOrNull() ?: return null
        val runManager = RunManager.getInstance(project)
        val name = if (pathsByFile.size == 1) {
            "Behat: ${pathsByFile.keys.first().substringAfterLast("/")}"
        } else {
            "Behat: ${pathsByFile.size} paths"
        }
        val settings = runManager.createConfiguration(name, factory)
        val config = settings.configuration as? BehatRunConfiguration ?: return null

        val runnerSettings = config.settings.runnerSettings
        // Force ConfigurationFile scope so the handler does not append any positional
        // path argument — paths are driven exclusively through `--paths`. We
        // intentionally do NOT touch `isUseAlternativeConfigurationFile` or
        // `configurationFilePath`: those are inherited from the run configuration
        // template the user edits via "Edit Configuration Templates", so both the
        // default `behat.yml` and a custom config file location are honored.
        runnerSettings.scope = PhpTestRunnerSettings.Scope.ConfigurationFile

        val pathsArgs = pathsByFile.entries.joinToString(" ") { (file, lines) ->
            buildPathsArg(file, lines)
        }
        val existing = runnerSettings.testRunnerOptions.orEmpty()
        runnerSettings.testRunnerOptions = if (existing.isBlank()) pathsArgs else "$existing $pathsArgs"
        return settings
    }

    private fun buildPathsArg(file: String, lines: List<Int>): String {
        val suffix = if (lines.isEmpty()) "" else ":${lines.joinToString(",")}"
        // Quote the whole value when the path contains whitespace so the
        // ParametersList tokenizer keeps it as a single argument.
        return if (file.any { it.isWhitespace() }) "--paths \"$file$suffix\"" else "--paths $file$suffix"
    }

    /**
     * Runs multiple feature files / scenarios in a single Behat launch.
     * See [createMultiPathsConfig] for the [pathsByFile] format.
     */
    fun runMultiplePaths(
        project: Project,
        pathsByFile: Map<String, List<Int>>,
        debug: Boolean = false
    ) {
        val settings = createMultiPathsConfig(project, pathsByFile) ?: return
        execute(project, settings, debug)
    }

    /**
     * Same as [runMultiplePaths] but invokes [onFinished] with the process exit code
     * once the run completes (or with -1 if the run could not be started).
     */
    fun runMultiplePathsWithCallback(
        project: Project,
        pathsByFile: Map<String, List<Int>>,
        onFinished: (Int) -> Unit,
        debug: Boolean = false
    ) {
        val settings = createMultiPathsConfig(project, pathsByFile)
        if (settings == null) {
            onFinished(-1)
            return
        }
        executeWithCallback(project, settings, onFinished, debug)
    }

    private fun execute(
        project: Project,
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        debug: Boolean
    ) {
        val runManager = RunManager.getInstance(project)
        settings.isTemporary = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor: Executor = if (debug) {
            DefaultDebugExecutor.getDebugExecutorInstance()
        } else {
            DefaultRunExecutor.getRunExecutorInstance()
        }
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    private fun executeWithCallback(
        project: Project,
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        onFinished: (Int) -> Unit,
        debug: Boolean = false
    ) {
        val runManager = RunManager.getInstance(project)
        settings.isTemporary = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor: Executor = if (debug) {
            DefaultDebugExecutor.getDebugExecutorInstance()
        } else {
            DefaultRunExecutor.getRunExecutorInstance()
        }
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
                handler.addProcessListener(object : ProcessAdapter() {
                    override fun processTerminated(event: ProcessEvent) {
                        onFinished(event.exitCode)
                    }
                })
            }
            val env = ExecutionEnvironmentBuilder.create(executor, settings).build(callback)
            runner.execute(env)
        } catch (_: Throwable) {
            onFinished(-1)
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Class.forName("com.jetbrains.php.behat.run.BehatRunConfigurationType")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
