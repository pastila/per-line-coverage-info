package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BehatConfigLocatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val monorepo = setOf(
        "/repo/core/behat.yml",
        "/repo/api/rail/behat.yml",
        "/repo/api/bus/behat.php",
    )

    private fun locate(featurePath: String, files: Set<String> = monorepo) =
        BehatConfigLocator.locate(featurePath, "/repo") { it in files }

    @Test
    fun `finds the config of the service the feature belongs to`() {
        val project = locate("/repo/core/src/Features/FeatureTest/MobileAPI/Service/AviaLegacy.feature")
        assertEquals("/repo/core/behat.yml", project?.configFile)
        assertEquals("/repo/core", project?.workingDir)
    }

    @Test
    fun `picks the nearest config, not the first one in the repo`() {
        val project = locate("/repo/api/rail/tests/Features/search.feature")
        assertEquals("/repo/api/rail/behat.yml", project?.configFile)
    }

    @Test
    fun `supports behat php configs`() {
        val project = locate("/repo/api/bus/tests/Features/book.feature")
        assertEquals("/repo/api/bus/behat.php", project?.configFile)
        assertEquals("/repo/api/bus", project?.workingDir)
    }

    @Test
    fun `stops at the project root`() {
        assertNull(locate("/repo/app/raketa/Features/a.feature"))
    }

    @Test
    fun `a config in a config subdirectory belongs to the directory above`() {
        val project = locate(
            "/repo/svc/tests/a.feature",
            setOf("/repo/svc/config/behat.yml"),
        )
        assertEquals("/repo/svc/config/behat.yml", project?.configFile)
        assertEquals("/repo/svc", project?.workingDir)
    }

    @Test
    fun `a config in a features subdirectory owns sibling directories and keeps features as working dir`() {
        val project = locate(
            "/repo/app/raketa/raketa/BoundedContext/Client/SignIn/Features/Web/EnterPage/ru_GET/feature.feature",
            setOf("/repo/app/raketa/features/behat.yml"),
        )
        assertEquals("/repo/app/raketa/features/behat.yml", project?.configFile)
        assertEquals("/repo/app/raketa/features", project?.workingDir)
    }

    @Test
    fun `resolves cross-directory suite declared via directory in yaml`() {
        val configPath = "/repo/app/raketa/features/behat.yml"
        val yaml = """
            default:
              extensions:
                Features\Support\SuiteAutoloader\SuiteAutoloaderExtension:
                  suites:
                    core:
                      directory: .
                    ddd:
                      directory: ../raketa/BoundedContext
                    light:
                      directory: ../../light/raketa/features # comment
        """.trimIndent()

        val project = BehatConfigLocator.locate(
            featureFilePath = "/repo/app/light/raketa/features/FeatureTest/Web/Theme/theme.feature",
            stopAt = "/repo",
            isFile = { it == configPath },
            discoverConfigs = { listOf(configPath) },
            readConfig = { if (it == configPath) yaml else null },
        )

        assertEquals(configPath, project?.configFile)
        assertEquals("/repo/app/raketa/features", project?.workingDir)
    }

    @Test
    fun `resolves cross-directory suite declared via paths list and php withPaths`() {
        val yamlConfig = "/repo/svc/behat.yml"
        val yaml = """
            default:
              suites:
                shared:
                  paths:
                    - "%paths.base%/../shared-features"
        """.trimIndent()
        val phpConfig = "/repo/api/static/behat.php"
        val php = """
            <?php
            return (new Config())->withProfile((new Profile('default'))
                ->withSuite((new Suite('extra'))->withPaths('%paths.base%/../../extra-features')));
        """.trimIndent()
        val configs = mapOf(yamlConfig to yaml, phpConfig to php)

        val fromYaml = BehatConfigLocator.locate(
            featureFilePath = "/repo/shared-features/a/test.feature",
            stopAt = "/repo",
            isFile = { it in configs },
            discoverConfigs = { configs.keys.toList() },
            readConfig = { configs[it] },
        )
        assertEquals(yamlConfig, fromYaml?.configFile)
        assertEquals("/repo/svc", fromYaml?.workingDir)

        val fromPhp = BehatConfigLocator.locate(
            featureFilePath = "/repo/extra-features/b/test.feature",
            stopAt = "/repo",
            isFile = { it in configs },
            discoverConfigs = { configs.keys.toList() },
            readConfig = { configs[it] },
        )
        assertEquals(phpConfig, fromPhp?.configFile)
        assertEquals("/repo/api/static", fromPhp?.workingDir)
    }

    @Test
    fun `does not match prefix-similar sibling directory names`() {
        val configPath = "/repo/app/raketa/features/behat.yml"
        val yaml = """
            suites:
              light:
                directory: ../../light/raketa/features
        """.trimIndent()

        val project = BehatConfigLocator.locate(
            featureFilePath = "/repo/app/light/raketa/features-extra/a.feature",
            stopAt = "/repo",
            isFile = { it == configPath },
            discoverConfigs = { listOf(configPath) },
            readConfig = { yaml },
        )

        assertNull(project)
    }

    @Test
    fun `locateAll scans configs at most once for multiple cross-directory files`() {
        val configPath = "/repo/app/raketa/features/behat.yml"
        val yaml = """
            suites:
              light:
                directory: ../../light/raketa/features
        """.trimIndent()
        var scanCount = 0

        val files = listOf(
            "/repo/app/raketa/features/FeatureTest/a.feature",
            "/repo/app/raketa/raketa/BoundedContext/b.feature",
            "/repo/app/light/raketa/features/FeatureTest/c.feature",
            "/repo/app/light/raketa/features/BoundedContext/d.feature",
        )
        val located = BehatConfigLocator.locateAll(
            featureFilePaths = files,
            stopAt = "/repo",
            isFile = { it == configPath },
            discoverConfigs = {
                scanCount++
                listOf(configPath)
            },
            readConfig = { yaml },
        )

        assertEquals(1, scanCount)
        val expected = BehatConfigLocator.BehatProject(configPath, "/repo/app/raketa/features")
        for (file in files) {
            assertEquals(expected, located[file])
        }
    }

    @Test
    fun `discovers cross-directory config on real filesystem`() {
        val root = tempFolder.root
        val raketaFeatures = File(root, "app/raketa/features").apply { mkdirs() }
        val configFile = File(raketaFeatures, "behat.yml").apply {
            writeText(
                """
                default:
                  extensions:
                    SuiteAutoloader:
                      suites:
                        light:
                          directory: ../../light/raketa/features
                """.trimIndent()
            )
        }
        val lightFeature = File(root, "app/light/raketa/features/FeatureTest/Web/Theme/theme.feature").apply {
            parentFile.mkdirs()
            writeText("Feature: Theme\n")
        }

        val located = BehatConfigLocator.locate(lightFeature.absolutePath, root.absolutePath)

        assertEquals(configFile.absolutePath, located?.configFile)
        assertEquals(raketaFeatures.absolutePath, located?.workingDir)
    }
}
