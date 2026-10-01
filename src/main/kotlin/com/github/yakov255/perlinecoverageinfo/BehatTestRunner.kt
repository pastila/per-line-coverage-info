package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
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
import java.nio.file.Paths

object BehatTestRunner {

    private val log = CoverageLog.get(BehatTestRunner::class.java)

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
        pinBehatConfigFile(project, runnerSettings, featureFilePath)
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
        pinBehatConfigFile(project, runnerSettings, featureFilePath)
        return settings
    }

    /**
     * Bundles feature files / scenarios into Behat launches — one per Behat project (a
     * monorepo has several: `core/behat.yml`, `api/rail/behat.yml`, `api/bus/behat.php`, …).
     * [pathsByFile] maps a feature file absolute path to the list of 1-based scenario line
     * numbers to run; an empty list means "run the entire file".
     *
     * Each launch is pinned to the config file that owns its feature files, and the paths are
     * made relative to that config's directory — Behat resolves relative specification paths
     * against it, so a path relative to anything else fails with "No specifications found".
     *
     * Example produced CLI options (as a single string passed via test runner options):
     *   src/Features/a.feature:10 src/Features/a.feature:20 src/Features/b.feature
     */
    private fun createMultiPathsConfigs(
        project: Project,
        pathsByFile: Map<String, List<Int>>
    ): List<com.intellij.execution.RunnerAndConfigurationSettings> {
        if (pathsByFile.isEmpty()) return emptyList()
        val byBehatProject = pathsByFile.entries.groupBy {
            BehatConfigLocator.locate(it.key, project.basePath)
        }
        if (byBehatProject.size > 1) {
            log.info("BehatTestRunner: paths span ${byBehatProject.size} Behat projects — launching them one after another")
        }
        return byBehatProject.mapNotNull { (behatProject, entries) ->
            createMultiPathsConfig(project, entries.associate { it.key to it.value }, behatProject)
        }
    }

    private fun createMultiPathsConfig(
        project: Project,
        pathsByFile: Map<String, List<Int>>,
        behatProject: BehatConfigLocator.BehatProject?
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
            log.warn("BehatTestRunner: no behat config found for ${pathsByFile.keys.first()} — falling back to the run configuration template")
            resolveBehatWorkingDir(project, runnerSettings)
        }
        val relativePaths = relativizePaths(pathsByFile, workingDir)
        val pathsArgs = buildPositionalPathArgsInternal(relativePaths)
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
            log.warn("BehatTestRunner: no behat config found for $featureFilePath — falling back to the run configuration template")
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
        configs: List<com.intellij.execution.RunnerAndConfigurationSettings>,
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

    fun isAvailable(): Boolean {
        return try {
            Class.forName("com.jetbrains.php.behat.run.BehatRunConfigurationType")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
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
