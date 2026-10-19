package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.configuration.RunConfigurationExtensionBase
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings

/**
 * [LocalCoverageRunExtension] for the Raketa IDE: registered as `ru.raketa.php.behat.runConfigurationExtension`, which takes the
 * platform class [RunConfigurationExtensionBase], so no Raketa classes are needed to compile it.
 *
 * In a container the command line is `docker compose ... exec ... <service> php ... <behat> ...`; [LocalCoverageCommand] finds the
 * Behat script in it the same way.
 */
class RaketaLocalCoverageRunExtension : RunConfigurationExtensionBase<RunConfigurationBase<*>>() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = configuration.type.id == RAKETA_BEHAT_TYPE_ID

    override fun isEnabledFor(applicableConfiguration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?): Boolean =
        CoverageApiSettings.getInstance().collectLocalCoverage

    override fun patchCommandLine(
        configuration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String,
    ) {
        LocalCoverageCommandLine.patch(configuration.project, configuration.name, cmdLine)
    }
}
