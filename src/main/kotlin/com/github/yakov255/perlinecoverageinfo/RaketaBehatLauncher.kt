package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.project.Project
import org.jdom.Element

/** The type ID of Behat run configurations of the Raketa IDE (`ru.raketa.php.behat`). */
const val RAKETA_BEHAT_TYPE_ID = "RaketaBehat"

/**
 * [BehatLauncher] for the Behat plugin of the Raketa IDE (`ru.raketa.php.behat`), registered in `raketa-integration.xml`.
 *
 * Uses the platform API only: the run configuration is found by its type ID, and its options are set through
 * `readExternal` by the names the Raketa plugin keeps stable (`testPath`, `testLine`, `workingDirectory`, `configFile`, `arguments`).
 * With an empty working directory Raketa runs in the directory of the Behat config that owns `testPath`, with the PHP interpreter
 * of the project.
 */
class RaketaBehatLauncher : BehatLauncher {

    override fun createScenarioConfig(
        project: Project, featureFilePath: String, line: Int, scenarioName: String
    ): RunnerAndConfigurationSettings? =
        createConfig(project, "Behat: $scenarioName", mapOf("testPath" to featureFilePath, "testLine" to line.toString()))

    override fun createFeatureFileConfig(project: Project, featureFilePath: String): RunnerAndConfigurationSettings? =
        createConfig(project, "Behat: ${featureFilePath.substringAfterLast("/")}", mapOf("testPath" to featureFilePath))

    override fun createPathsConfig(
        project: Project,
        name: String,
        pathsByFile: Map<String, List<Int>>,
        behatProject: BehatConfigLocator.BehatProject?
    ): RunnerAndConfigurationSettings? {
        if (pathsByFile.isEmpty()) return null
        val workingDir = behatProject?.workingDir ?: project.basePath ?: return null
        val options = linkedMapOf(
            "workingDirectory" to workingDir,
            "arguments" to BehatTestRunner.pathArguments(pathsByFile, workingDir),
        )
        behatProject?.let { options["configFile"] = it.configFile }
        return createConfig(project, name, options)
    }

    private fun createConfig(project: Project, name: String, options: Map<String, String>): RunnerAndConfigurationSettings? {
        val type = ConfigurationTypeUtil.findConfigurationType(RAKETA_BEHAT_TYPE_ID) ?: return null
        val factory = type.configurationFactories.firstOrNull() ?: return null
        val settings = RunManager.getInstance(project).createConfiguration(name, factory)
        val configuration = settings.configuration
        // Start from the options the template has (environment, interpreter, ...) and replace only ours.
        val element = Element("configuration")
        configuration.writeExternal(element)
        for ((optionName, value) in options) {
            element.getChildren("option").filter { it.getAttributeValue("name") == optionName }.forEach { it.detach() }
            element.addContent(Element("option").setAttribute("name", optionName).setAttribute("value", value))
        }
        configuration.readExternal(element)
        return settings
    }
}
