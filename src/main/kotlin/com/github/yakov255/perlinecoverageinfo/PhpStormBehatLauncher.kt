package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.jetbrains.php.behat.BehatFrameworkType
import com.jetbrains.php.behat.run.BehatRunConfiguration
import com.jetbrains.php.behat.run.BehatRunConfigurationType
import com.jetbrains.php.config.interpreters.PhpInterpretersManagerImpl
import com.jetbrains.php.config.interpreters.PhpSdkDependentConfiguration
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager
import com.jetbrains.php.testFramework.PhpTestFrameworkConfiguration
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings
import java.io.File

/**
 * [BehatLauncher] for the Behat plugin of PhpStorm (`com.jetbrains.php.behat`), registered in `behat-integration.xml`.
 */
class PhpStormBehatLauncher : BehatLauncher {

    private val log = CoverageLog.get(PhpStormBehatLauncher::class.java)

    override fun createScenarioConfig(
        project: Project, featureFilePath: String, line: Int, scenarioName: String
    ): RunnerAndConfigurationSettings? {
        val configType = BehatRunConfigurationType.getInstance()
        val factory = configType.configurationFactories.firstOrNull() ?: return null
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("Behat: $scenarioName", factory)
        val config = settings.configuration as? BehatRunConfiguration ?: return null

        val runnerSettings = config.settings.runnerSettings
        runnerSettings.scope = PhpTestRunnerSettings.Scope.Method
        runnerSettings.filePath = featureFilePath
        runnerSettings.methodName = scenarioName
        pinBehatConfigFile(project, runnerSettings, featureFilePath)
        return settings
    }

    override fun createFeatureFileConfig(
        project: Project, featureFilePath: String
    ): RunnerAndConfigurationSettings? {
        val configType = BehatRunConfigurationType.getInstance()
        val factory = configType.configurationFactories.firstOrNull() ?: return null
        val runManager = RunManager.getInstance(project)
        val fileName = featureFilePath.substringAfterLast("/")
        val settings = runManager.createConfiguration("Behat: $fileName", factory)
        val config = settings.configuration as? BehatRunConfiguration ?: return null

        val runnerSettings = config.settings.runnerSettings
        runnerSettings.scope = PhpTestRunnerSettings.Scope.File
        runnerSettings.filePath = featureFilePath
        pinBehatConfigFile(project, runnerSettings, featureFilePath)
        return settings
    }

    override fun createPathsConfig(
        project: Project,
        name: String,
        pathsByFile: Map<String, List<Int>>,
        behatProject: BehatConfigLocator.BehatProject?
    ): RunnerAndConfigurationSettings? {
        if (pathsByFile.isEmpty()) return null
        val configType = BehatRunConfigurationType.getInstance()
        val factory = configType.configurationFactories.firstOrNull() ?: return null
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration(name, factory)
        val config = settings.configuration as? BehatRunConfiguration ?: return null

        val runnerSettings = config.settings.runnerSettings
        // Force ConfigurationFile scope so the handler does not append any positional
        // path argument — paths are passed as separate positional arguments to behat.
        runnerSettings.scope = PhpTestRunnerSettings.Scope.ConfigurationFile

        val workingDir = if (behatProject != null) {
            // Pin the config that owns these feature files: it decides both the suites Behat
            // knows about and the directory relative paths are resolved against. Without this
            // the run inherits whichever framework configuration PhpStorm happens to pick.
            runnerSettings.isUseAlternativeConfigurationFile = true
            runnerSettings.configurationFilePath = behatProject.configFile
            behatProject.workingDir
        } else {
            resolveBehatWorkingDir(project, runnerSettings)
        }
        val pathsArgs = BehatTestRunner.pathArguments(pathsByFile, workingDir)
        val existing = runnerSettings.testRunnerOptions.orEmpty()
        runnerSettings.testRunnerOptions = if (existing.isBlank()) pathsArgs else "$existing $pathsArgs"
        return settings
    }

    /**
     * Points a single-file / single-scenario run at the Behat config that owns [featureFilePath],
     * instead of whichever framework configuration PhpStorm would pick by default.
     */
    private fun pinBehatConfigFile(
        project: Project,
        runnerSettings: PhpTestRunnerSettings,
        featureFilePath: String
    ) {
        val behatProject = BehatConfigLocator.locate(featureFilePath, project.basePath)
        if (behatProject == null) {
            log.warn("PhpStormBehatLauncher: no behat config found for $featureFilePath — falling back to the run configuration template")
            return
        }
        runnerSettings.isUseAlternativeConfigurationFile = true
        runnerSettings.configurationFilePath = behatProject.configFile
    }

    /**
     * Determines the Behat working directory, replicating the logic from
     * [BehatRunConfiguration.getWorkingDirectory] for [PhpTestRunnerSettings.Scope.ConfigurationFile].
     *
     * Tries multiple strategies to find the Behat configuration file path,
     * handles remote interpreter path mappings (Docker/SSH), and derives
     * the working dir from the **local** config file path.
     *
     * If the config file is inside a `config/` subdirectory, the working directory is
     * one level above `config/`; otherwise it is the directory containing the config
     * file. Falls back to [Project.getBasePath] when no config file is set.
     */
    private fun resolveBehatWorkingDir(project: Project, runnerSettings: PhpTestRunnerSettings): String {
        val configFilePath = resolveBehatLocalConfigFilePath(project, runnerSettings)
        if (configFilePath.isNotBlank()) {
            return deriveWorkingDirFromConfig(configFilePath, project)
        }
        log.warn("resolveBehatWorkingDir: no configFilePath found, fallback to basePath")
        return project.basePath ?: ""
    }

    /**
     * Resolves the Behat config file path as a **local** filesystem path.
     *
     * For remote interpreters (Docker, SSH) the framework config stores container-side
     * paths (e.g. `/web/core/behat.yml`). We convert them to local paths using the
     * interpreter's path mappings.
     */
    private fun resolveBehatLocalConfigFilePath(project: Project, runnerSettings: PhpTestRunnerSettings): String {
        // Step 1: if "Use alternative configuration file" is checked, use that (it's always local)
        if (runnerSettings.isUseAlternativeConfigurationFile) {
            val altPath = runnerSettings.configurationFilePath.orEmpty()
            if (altPath.isNotBlank()) return altPath
        }

        // Step 2: find the Behat framework config from Test Frameworks settings
        val frameworkConfig = findBehatFrameworkConfig(project) ?: return ""

        val rawPath = frameworkConfig.configurationFilePath.orEmpty()
        if (rawPath.isBlank()) return ""

        // Step 3: if the config is local, use as-is
        if (frameworkConfig.isLocal) return rawPath

        // Step 4: remote config — convert path using interpreter's path mappings
        val localPath = convertRemoteToLocal(project, frameworkConfig, rawPath)
        if (localPath.isNotBlank()) return localPath

        // Step 5: heuristic fallback — match remote path suffix under project root
        val basePath = project.basePath
        if (basePath != null) {
            val heuristic = matchRemotePathToLocal(rawPath, basePath)
            if (heuristic.isNotBlank()) return heuristic
        }

        log.warn("resolveBehatLocalConfigFilePath: could not resolve remote path '$rawPath' to local")
        return ""
    }

    /**
     * Finds the first Behat framework config that has a configuration file path set.
     */
    private fun findBehatFrameworkConfig(project: Project): PhpTestFrameworkConfiguration? {
        try {
            val behatType = BehatFrameworkType.getInstance()
            val configs = PhpTestFrameworkSettingsManager.getInstance(project).getConfigurations(behatType)
            configs?.forEach { cfg ->
                if (cfg.isUseConfigurationFile && !cfg.configurationFilePath.isNullOrBlank()) {
                    return cfg
                }
            }
        } catch (e: Exception) {
            log.warn("findBehatFrameworkConfig: failed: ${e.message}")
        }
        return null
    }

    /**
     * Converts a remote (Docker/SSH) path to a local path using the interpreter's
     * path mappings configured in PhpStorm.
     */
    private fun convertRemoteToLocal(
        project: Project,
        frameworkConfig: PhpTestFrameworkConfiguration,
        remotePath: String
    ): String {
        try {
            val interpreterId = (frameworkConfig as? PhpSdkDependentConfiguration)?.interpreterId
            if (interpreterId.isNullOrBlank()) return ""

            val sdkData = PhpInterpretersManagerImpl.getInstance(project)
                .findInterpreterDataById(interpreterId) ?: return ""

            val remoteManager = PhpRemoteInterpreterManager.getInstance() ?: return ""
            val pathMappings = remoteManager.createPathMappings(project, sdkData)
            val localPath = pathMappings.convertToLocal(remotePath)
            return if (localPath != remotePath) localPath else ""
        } catch (e: Exception) {
            log.warn("convertRemoteToLocal: failed: ${e.message}")
            return ""
        }
    }

    /**
     * Heuristic fallback: tries to match a remote path to a local file by
     * progressively shorter suffixes. For example, `/web/core/behat.yml` is
     * tried as `web/core/behat.yml`, then `core/behat.yml` under [basePath].
     */
    private fun matchRemotePathToLocal(remotePath: String, basePath: String): String {
        val parts = remotePath.split("/").filter { it.isNotEmpty() }
        for (i in 1 until parts.size) {
            val suffix = parts.subList(i, parts.size).joinToString("/")
            val candidate = File(basePath, suffix)
            if (candidate.isFile) return candidate.absolutePath
        }
        return ""
    }

    private fun deriveWorkingDirFromConfig(configFilePath: String, project: Project): String {
        val configDir = File(configFilePath).parent ?: return project.basePath ?: ""
        if (File(configDir).name == "config") {
            val parentDir = File(configDir).parent
            if (!parentDir.isNullOrBlank()) return parentDir
        }
        return configDir
    }
}
