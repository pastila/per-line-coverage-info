package com.github.yakov255.perlinecoverageinfo

import com.github.yakov255.perlinecoverageinfo.LocalCoverageCommand.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCoverageCommandTest {

    private val coreConfig = """
        default:
          extensions:
            Raketa\Library\BehatContext\Extension\CustomFormatter\CustomFormatterExtension:
        coverage-cobertura: # CI
          extensions:
            Raketa\Library\BehatBinaryCoverage\Extension:
        coverage-clover:
          extensions:
            Raketa\Library\BehatBinaryCoverage\Extension:
    """.trimIndent()

    private val hotelsConfig = """
        default:
          extensions: ~
        coverage-html:
          extensions:
            DVDoug\Behat\CodeCoverage\Extension: ~
    """.trimIndent()

    private val localDirs = setOf("core", "api", "api/hotels", "storage", "storage/coverage")
    private val localFiles = mapOf("core/behat.yml" to coreConfig, "api/hotels/behat.yml" to hotelsConfig)

    private fun patch(vararg parameters: String) = patchWithEnv(emptyMap(), *parameters)

    private fun patchWithEnv(environment: Map<String, String>, vararg parameters: String) = LocalCoverageCommand.patch(
        parameters.toList(),
        coverageDir = "storage/coverage",
        isLocalDir = { it in localDirs },
        readLocalFile = { localFiles[it] },
        environment = environment,
    )

    @Test
    fun `PhpStorm behat helper takes the service dir from the config`() {
        val result = patch(
            "/opt/.phpstorm_helpers/behat.php",
            "--format", "PhpStormBehatFormatter",
            "--no-interaction",
            "src/Features/a.feature:106",
            "--config", "/web/core/behat.yml",
        )

        assertEquals(
            Result.Patched(
                listOf(
                    "-d", "pcov.enabled=1", "-d", "pcov.directory=/web",
                    "/opt/.phpstorm_helpers/behat.php",
                    "--format", "PhpStormBehatFormatter",
                    "--no-interaction",
                    "src/Features/a.feature:106",
                    "--config", "/web/core/behat.yml",
                    "--profile=coverage-clover",
                    "--binary-coverage-target=/web/storage/coverage/local.covt",
                    "--binary-coverage-root=core",
                ),
                serviceDir = "core",
            ),
            result,
        )
    }

    @Test
    fun `PhpStorm behat helper prefers IDE_BEHAT_DIR`() {
        val result = patchWithEnv(
            mapOf("IDE_BEHAT_DIR" to "/web/core/vendor/behat/behat/bin/behat"),
            "/opt/.phpstorm_helpers/behat.php",
            "src/Features/a.feature",
        )

        assertEquals("core", (result as Result.Patched).serviceDir)
    }

    @Test
    fun `PhpStorm behat helper without behat dir and config is left alone`() {
        assertTrue(patch("/opt/.phpstorm_helpers/behat.php", "src/Features/a.feature") is Result.Skipped)
    }

    @Test
    fun `docker command line gets pcov and binary coverage options`() {
        val result = patch(
            "-dxdebug.mode=debug",
            "/web/core/vendor/behat/behat/bin/behat",
            "--config", "/web/core/behat.yml",
            "--format", "teamcity",
            "/web/core/src/Features/a.feature:28",
        )

        assertEquals(
            Result.Patched(
                listOf(
                    "-dxdebug.mode=debug",
                    "-d", "pcov.enabled=1", "-d", "pcov.directory=/web",
                    "/web/core/vendor/behat/behat/bin/behat",
                    "--config", "/web/core/behat.yml",
                    "--format", "teamcity",
                    "/web/core/src/Features/a.feature:28",
                    "--profile=coverage-clover",
                    "--binary-coverage-target=/web/storage/coverage/local.covt",
                    "--binary-coverage-root=core",
                ),
                serviceDir = "core",
            ),
            result,
        )
    }

    @Test
    fun `local interpreter uses host paths`() {
        val result = patch("/home/me/raketa/core/vendor/bin/behat", "--config=/home/me/raketa/core/behat.yml")

        result as Result.Patched
        assertTrue("pcov.directory=/home/me/raketa" in result.parameters)
        assertTrue("--binary-coverage-target=/home/me/raketa/storage/coverage/local.covt" in result.parameters)
        assertTrue("--binary-coverage-root=core" in result.parameters)
    }

    @Test
    fun `config is taken from the service dir when not passed`() {
        val result = patch("/web/core/vendor/behat/behat/bin/behat", "/web/core/src/Features/a.feature")

        assertTrue(result is Result.Patched)
    }

    @Test
    fun `service without the binary coverage extension is left alone`() {
        val result = patch("/web/api/hotels/vendor/behat/behat/bin/behat", "--config", "/web/api/hotels/behat.yml")

        assertEquals(
            Result.Skipped("api/hotels/behat.yml has no profile with the BehatBinaryCoverage extension"),
            result,
        )
    }

    @Test
    fun `explicit profile is not overridden`() {
        assertTrue(patch("/web/core/vendor/behat/behat/bin/behat", "--profile=ci") is Result.Skipped)
        assertTrue(patch("/web/core/vendor/behat/behat/bin/behat", "-p", "ci") is Result.Skipped)
    }

    @Test
    fun `already configured command line is left alone`() {
        val result = patch("/web/core/vendor/behat/behat/bin/behat", "--binary-coverage-target=/tmp/x.covt")

        assertEquals(Result.Skipped("binary coverage options are already set"), result)
    }

    @Test
    fun `non behat command is left alone`() {
        assertTrue(patch("/web/core/vendor/bin/phpunit") is Result.Skipped)
    }

    @Test
    fun `unknown service dir is left alone`() {
        assertTrue(patch("/opt/other/vendor/bin/behat") is Result.Skipped)
    }

    // --- helpers ---

    @Test
    fun `matchServiceDir picks the shortest existing trailing path`() {
        assertEquals("/web" to "core", LocalCoverageCommand.matchServiceDir("/web/core") { it in localDirs })
        assertEquals("/web" to "api/hotels", LocalCoverageCommand.matchServiceDir("/web/api/hotels") { it in localDirs })
        assertNull(LocalCoverageCommand.matchServiceDir("/web/nothing") { it in localDirs })
    }

    @Test
    fun `findCoverageProfile prefers coverage-clover`() {
        assertEquals("coverage-clover", LocalCoverageCommand.findCoverageProfile(coreConfig))
    }

    @Test
    fun `findCoverageProfile falls back to the first profile with the extension`() {
        val config = """
            default: ~
            ci:
              extensions:
                Raketa\Library\BehatBinaryCoverage\Extension:
        """.trimIndent()
        assertEquals("ci", LocalCoverageCommand.findCoverageProfile(config))
    }

    @Test
    fun `findCoverageProfile needs no profile when default has the extension`() {
        val config = """
            default:
              extensions:
                Raketa\Library\BehatBinaryCoverage\Extension:
        """.trimIndent()
        assertEquals("", LocalCoverageCommand.findCoverageProfile(config))
    }

    @Test
    fun `findCoverageProfile ignores commented out extension`() {
        val config = """
            default:
              extensions:
                # Raketa\Library\BehatBinaryCoverage\Extension:
        """.trimIndent()
        assertNull(LocalCoverageCommand.findCoverageProfile(config))
    }
}
