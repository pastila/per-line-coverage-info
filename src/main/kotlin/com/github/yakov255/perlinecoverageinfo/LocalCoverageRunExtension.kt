package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.jetbrains.php.behat.run.BehatRunConfiguration
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.run.PhpRunConfiguration
import com.jetbrains.php.run.PhpRunConfigurationExtension

/**
 * When [CoverageApiSettings.collectLocalCoverage] is on, makes every Behat run started from the
 * IDE (gutter ▶, run configurations, "Covering Line" panel) write a `.covt` into the local coverage
 * directory, which [LocalCoverageService] then merges over CI coverage.
 *
 * Only services whose Behat config has a profile with the binary coverage extension are touched;
 * others run unchanged.
 */
class LocalCoverageRunExtension : PhpRunConfigurationExtension() {

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
        LocalCoverageCommandLine.patch(configuration.project, configuration.name, cmdLine)
    }
}
