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

    /**
     * Runs a single scenario and invokes [onFinished] with the process exit code
     * once the run completes. Exit code 0 means success.
     * If the run cannot be started, [onFinished] is invoked with -1.
     */
    fun runScenarioWithCallback(
        project: Project,
        featureFilePath: String,
        scenarioName: String,
        onFinished: (Int) -> Unit
    ) {
        val settings = createScenarioConfig(project, featureFilePath, scenarioName)
        if (settings == null) {
            onFinished(-1)
            return
        }
        executeWithCallback(project, settings, onFinished)
    }

    /**
     * Runs an entire feature file and invokes [onFinished] with the process exit code
     * once the run completes.
     */
    fun runFeatureFileWithCallback(
        project: Project,
        featureFilePath: String,
        onFinished: (Int) -> Unit
    ) {
        val settings = createFeatureFileConfig(project, featureFilePath)
        if (settings == null) {
            onFinished(-1)
            return
        }
        executeWithCallback(project, settings, onFinished)
    }

    private fun executeWithCallback(
        project: Project,
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        onFinished: (Int) -> Unit
    ) {
        val runManager = RunManager.getInstance(project)
        settings.isTemporary = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor = DefaultRunExecutor.getRunExecutorInstance()
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
