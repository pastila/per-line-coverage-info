package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.jetbrains.php.behat.run.BehatRunConfiguration
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.run.PhpRunConfiguration
import com.jetbrains.php.run.PhpRunConfigurationExtension
import java.io.File

/**
 * When [CoverageApiSettings.collectLocalCoverage] is on, makes every Behat run started from the
 * IDE (gutter ▶, run configurations, "Covering Line" panel) write a `.covt` into the local coverage
 * directory, which [LocalCoverageService] then merges over CI coverage.
 *
 * Only services whose Behat config has a profile with the binary coverage extension are touched;
 * others run unchanged.
 */
class LocalCoverageRunExtension : PhpRunConfigurationExtension() {

    private val log = CoverageLog.get(LocalCoverageRunExtension::class.java)

    override fun isApplicableFor(configuration: PhpRunConfiguration<*>): Boolean = configuration is BehatRunConfiguration

    /** Local and remote (Docker, SSH) interpreters alike — paths are matched per command line. */
    override fun isApplicable(interpreter: PhpInterpreter?): Boolean = true

    override fun isEnabledFor(applicableConfiguration: PhpRunConfiguration<*>, runnerSettings: RunnerSettings?): Boolean =
        CoverageApiSettings.getInstance().collectLocalCoverage

    override fun patchCommandLine(
        configuration: PhpRunConfiguration<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String,
    ) {
        val gitRoot = LocalCoverageService.getInstance(configuration.project).gitRootOrNull() ?: run {
            log.info("LocalCoverage run: no git root, command line left as is")
            return
        }
        val result = LocalCoverageCommand.patch(
            parameters = cmdLine.parametersList.list,
            coverageDir = CoverageApiSettings.getInstance().localCoverageDir,
            isLocalDir = { File(gitRoot, it).isDirectory },
            readLocalFile = { File(gitRoot, it).takeIf(File::isFile)?.readText() },
            environment = cmdLine.environment,
        )
        when (result) {
            is LocalCoverageCommand.Result.Patched -> {
                cmdLine.parametersList.clearAll()
                cmdLine.parametersList.addAll(result.parameters)
                log.info("LocalCoverage run: collecting coverage for ${result.serviceDir} (${configuration.name})")
            }
            is LocalCoverageCommand.Result.Skipped ->
                log.info(
                    "LocalCoverage run: not collecting for ${configuration.name} — ${result.reason}; " +
                        "exe=${cmdLine.exePath}, parameters=${cmdLine.parametersList.list}",
                )
        }
    }
}
