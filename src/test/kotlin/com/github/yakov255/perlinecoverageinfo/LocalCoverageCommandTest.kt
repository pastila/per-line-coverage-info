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
    fun `Raketa docker compose command line gets pcov and binary coverage options`() {
        val prefix = listOf(
            "compose", "-f", "/home/u/raketa/docker-compose.yml", "exec", "-T",
            "-e", "BEHAT_PARAMS={\"extensions\":{\"/web/.idea/raketa-behat/raketa-behat-teamcity.php\":{}}}",
            "-w", "/web/core", "php", "php",
        )
        val behat = listOf(
            "/web/core/vendor/bin/behat", "--format=raketa_teamcity", "--no-interaction",
            "--config=/web/core/behat.yml", "/web/core/src/Features/a.feature:6",
        )
        val result = patch(*(prefix + behat).toTypedArray())

        assertEquals(
            Result.Patched(
                prefix + listOf("-d", "pcov.enabled=1", "-d", "pcov.directory=/web") + behat + listOf(
                    "--profile=coverage-clover",
                    "--binary-coverage-target=/web/storage/coverage/local.covt",
                    "--binary-coverage-root=core",
                ),
                "core",
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
    fun `raketa config falls back to features directory after core move`() {
        val result = LocalCoverageCommand.patch(
            parameters = listOf(
                "/web/app/raketa/vendor/bin/behat",
                "/web/app/raketa/features/FeatureTest/a.feature",
            ),
            coverageDir = "storage/coverage",
            isLocalDir = { it in setOf("app/raketa", "storage", "storage/coverage") },
            readLocalFile = {
                if (it == "app/raketa/features/behat.yml") coreConfig else null
            },
        )

        val patched = result as Result.Patched
        assertEquals("app/raketa", patched.serviceDir)
        assertTrue(patched.parameters.contains("--binary-coverage-root=app/raketa"))
    }

    private val controllerPath =
        "app/raketa/vendor/raketa-libraries/behat-coverage-per-line-report/src/Cli/CoverageReporterController.php"
    private val installedPath = "app/raketa/vendor/composer/installed.json"
    private val lockPath = "app/raketa/composer.lock"

    private fun reporterMetadata(version: String, section: String = "packages") = """
        {"$section":[
          {"name":"unrelated/package","version":"99.0.0"},
          {"name":"raketa-libraries/behat-coverage-per-line-report","version":"$version"}
        ]}
    """.trimIndent()

    private fun patchWithReporter(
        files: Map<String, String>,
        executable: String = "/web/app/raketa/vendor/bin/behat",
        environment: Map<String, String> = emptyMap(),
    ): Result.Patched = LocalCoverageCommand.patch(
        parameters = listOf(executable, "features/a.feature:53"),
        coverageDir = "storage/coverage",
        isLocalDir = { it == "app/raketa" },
        readLocalFile = { if (it == "app/raketa/features/behat.yml") coreConfig else files[it] },
        environment = environment,
    ) as Result.Patched

    private fun assertRootArgument(expected: String, result: Result.Patched) {
        assertEquals(listOf(expected), result.parameters.filter {
            it.startsWith("--binary-coverage-root=") || it.startsWith("--binary-coverage-repo-root=")
        })
    }

    @Test
    fun `reporter 2 controller uses the container repository root instead of the service prefix`() {
        val result = patchWithReporter(mapOf(controllerPath to "\$command->addOption('binary-coverage-repo-root');"))

        assertRootArgument("--binary-coverage-repo-root=/web", result)
        assertTrue("--binary-coverage-target=/web/storage/coverage/local.covt" in result.parameters)
        assertTrue("pcov.directory=/web" in result.parameters)
    }

    @Test
    fun `PhpStorm helper supports reporter 2 and a local interpreter`() {
        val result = patchWithReporter(
            files = mapOf(controllerPath to "\$command->addOption(\"binary-coverage-repo-root\");"),
            executable = "/opt/.phpstorm_helpers/behat.php",
            environment = mapOf("IDE_BEHAT_DIR" to "/home/me/project/app/raketa/vendor/behat/behat/bin/behat"),
        )

        assertRootArgument("--binary-coverage-repo-root=/home/me/project", result)
        assertTrue("--binary-coverage-target=/home/me/project/storage/coverage/local.covt" in result.parameters)
    }

    @Test
    fun `installed controller takes precedence over a stale lock in both directions`() {
        assertRootArgument("--binary-coverage-repo-root=/web", patchWithReporter(mapOf(
            controllerPath to "'binary-coverage-repo-root'",
            lockPath to reporterMetadata("1.0.2"),
        )))
        assertRootArgument("--binary-coverage-root=app/raketa", patchWithReporter(mapOf(
            controllerPath to "'binary-coverage-root'",
            installedPath to reporterMetadata("2.0.0"),
            lockPath to reporterMetadata("2.0.0"),
        )))
    }

    @Test
    fun `Composer 2 installed version takes precedence over lock when controller is unavailable`() {
        assertRootArgument("--binary-coverage-repo-root=/web", patchWithReporter(mapOf(
            installedPath to reporterMetadata("2.0.0"), lockPath to reporterMetadata("1.0.2"),
        )))
        assertRootArgument("--binary-coverage-root=app/raketa", patchWithReporter(mapOf(
            installedPath to reporterMetadata("1.0.2"), lockPath to reporterMetadata("2.0.0"),
        )))
    }

    @Test
    fun `Composer 1 installed version is recognized`() {
        assertRootArgument("--binary-coverage-repo-root=/web", patchWithReporter(mapOf(
            installedPath to """[{"name":"raketa-libraries/behat-coverage-per-line-report","version":"v2.0.0"}]""",
        )))
    }

    @Test
    fun `lock fallback finds reporter in normal and dev dependencies`() {
        for (section in listOf("packages", "packages-dev")) {
            assertRootArgument("--binary-coverage-repo-root=/web", patchWithReporter(mapOf(
                lockPath to reporterMetadata("2.0.0", section),
            )))
            assertRootArgument("--binary-coverage-root=app/raketa", patchWithReporter(mapOf(
                lockPath to reporterMetadata("1.0.2", section),
            )))
        }
    }

    @Test
    fun `malformed or missing metadata keeps legacy arguments and can fall back to lock`() {
        for (metadata in listOf("not JSON", "null", "{}", """{"packages":{}}""", reporterMetadata("dev-main"))) {
            assertRootArgument("--binary-coverage-root=app/raketa", patchWithReporter(mapOf(installedPath to metadata)))
            assertRootArgument("--binary-coverage-repo-root=/web", patchWithReporter(mapOf(
                installedPath to metadata, lockPath to reporterMetadata("2.0.0"),
            )))
        }
        assertRootArgument("--binary-coverage-root=app/raketa", patchWithReporter(emptyMap()))
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
