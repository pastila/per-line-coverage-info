package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.jetbrains.php.behat.run.BehatRunConfiguration
import com.jetbrains.php.behat.run.BehatRunConfigurationType
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

object BehatTestRunner {

    /**
     * Creates and executes a Behat run configuration for a specific scenario.
     *
     * @param project the current project
     * @param featureFilePath absolute path to the .feature file
     * @param scenarioName the scenario name (used as "method" in Behat run config)
     */
    fun runScenario(project: Project, featureFilePath: String, scenarioName: String) {
        val configType = BehatRunConfigurationType.getInstance()
        val factory = configType.configurationFactories.firstOrNull() ?: return
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("Behat: $scenarioName", factory)
        val config = settings.configuration as? BehatRunConfiguration ?: return

        val runnerSettings = config.settings.runnerSettings
        runnerSettings.scope = PhpTestRunnerSettings.Scope.Method
        runnerSettings.filePath = featureFilePath
        runnerSettings.methodName = scenarioName

        settings.isTemporary = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    /**
     * Creates and executes a Behat run configuration for an entire feature file.
     *
     * @param project the current project
     * @param featureFilePath absolute path to the .feature file
     */
    fun runFeatureFile(project: Project, featureFilePath: String) {
        val configType = BehatRunConfigurationType.getInstance()
        val factory = configType.configurationFactories.firstOrNull() ?: return
        val runManager = RunManager.getInstance(project)
        val fileName = featureFilePath.substringAfterLast("/")
        val settings = runManager.createConfiguration("Behat: $fileName", factory)
        val config = settings.configuration as? BehatRunConfiguration ?: return

        val runnerSettings = config.settings.runnerSettings
        runnerSettings.scope = PhpTestRunnerSettings.Scope.File
        runnerSettings.filePath = featureFilePath

        settings.isTemporary = true
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    /**
     * Checks if the Behat plugin is available at runtime.
     */
    fun isAvailable(): Boolean {
        return try {
            Class.forName("com.jetbrains.php.behat.run.BehatRunConfigurationType")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
