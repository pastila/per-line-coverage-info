package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Creates Behat run configurations of the IDE's Behat plugin. One implementation per supported plugin, registered in the optional
 * descriptor of that plugin:
 * - [PhpStormBehatLauncher] — `com.jetbrains.php.behat` of PhpStorm / IntelliJ IDEA Ultimate (`behat-integration.xml`);
 * - [RaketaBehatLauncher] — `ru.raketa.php.behat` of the Raketa IDE (`raketa-integration.xml`).
 *
 * [BehatTestRunner] picks the first registered one and runs the configurations it creates.
 */
interface BehatLauncher {

    /** Runs the scenario that starts at [line] (1-based) of [featureFilePath]; [scenarioName] is its title. */
    fun createScenarioConfig(
        project: Project,
        featureFilePath: String,
        line: Int,
        scenarioName: String,
    ): RunnerAndConfigurationSettings?

    fun createFeatureFileConfig(project: Project, featureFilePath: String): RunnerAndConfigurationSettings?

    /**
     * Runs [pathsByFile] (feature file absolute path → 1-based scenario lines, empty for the whole file) of one Behat project in one
     * launch. [behatProject] is the Behat config that owns the files, or null when none was found.
     */
    fun createPathsConfig(
        project: Project,
        name: String,
        pathsByFile: Map<String, List<Int>>,
        behatProject: BehatConfigLocator.BehatProject?,
    ): RunnerAndConfigurationSettings?

    companion object {
        val EP_NAME: ExtensionPointName<BehatLauncher> =
            ExtensionPointName.create("com.github.yakov255.perlinecoverageinfo.behatLauncher")

        fun getInstance(): BehatLauncher? = EP_NAME.extensionList.firstOrNull()
    }
}
