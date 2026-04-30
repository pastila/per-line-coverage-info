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
     * Paths are converted to be relative to the Behat working directory (derived from
     * the run configuration's config file location) so that Behat receives paths like
     * `src/Features/foo.feature:10,20` rather than absolute filesystem paths.
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

        val workingDir = resolveBehatWorkingDir(project, runnerSettings)
        val relativePaths = relativizePaths(pathsByFile, workingDir)
        val pathsArgs = relativePaths.entries.joinToString(" ") { (file, lines) ->
            buildPathsArg(file, lines)
        }
        val existing = runnerSettings.testRunnerOptions.orEmpty()
        runnerSettings.testRunnerOptions = if (existing.isBlank()) pathsArgs else "$existing $pathsArgs"
        return settings
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
