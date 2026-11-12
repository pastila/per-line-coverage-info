package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Rewrites a Behat command line so the run writes a `.covt` into the local coverage directory.
 *
 * Pure logic — paths in the command line are interpreter-side (e.g. `/web/app/raketa/...` inside a
 * Docker container), the git root is host-side; the two are matched by the service directory
 * both have in common.
 */
object LocalCoverageCommand {

    const val REPORT_FILE_NAME = "local.covt"

    private const val EXTENSION_MARKER = "BehatBinaryCoverage"
    private const val PREFERRED_PROFILE = "coverage-clover"
    private const val REPORTER_PACKAGE = "raketa-libraries/behat-coverage-per-line-report"
    private const val LEGACY_ROOT_OPTION = "binary-coverage-root"
    private const val REPO_ROOT_OPTION = "binary-coverage-repo-root"
    private val BEHAT_SCRIPT_SUFFIXES = listOf("/vendor/behat/behat/bin/behat", "/vendor/bin/behat")

    /** PhpStorm's wrapper; the real Behat executable is passed in [BEHAT_DIR_ENV]. */
    private const val IDE_HELPER_SUFFIX = "/.phpstorm_helpers/behat.php"
    private const val BEHAT_DIR_ENV = "IDE_BEHAT_DIR"
    private val TOP_LEVEL_KEY = Regex("^([A-Za-z0-9_.-]+):")

    sealed interface Result {
        data class Patched(val parameters: List<String>, val serviceDir: String) : Result
        data class Skipped(val reason: String) : Result
    }

    /**
     * @param parameters the PHP command line parameters (interpreter options, Behat script, Behat options)
     * @param coverageDir git-root-relative directory to write the report to
     * @param isLocalDir whether a git-root-relative directory exists on the host
     * @param readLocalFile contents of a git-root-relative file on the host, or null if missing
     * @param environment the command line environment (PhpStorm's helper gets the Behat path from it)
     */
    fun patch(
        parameters: List<String>,
        coverageDir: String,
        isLocalDir: (String) -> Boolean,
        readLocalFile: (String) -> String?,
        environment: Map<String, String> = emptyMap(),
    ): Result {
        if (parameters.any { it.startsWith("--binary-coverage-target") }) {
            return Result.Skipped("binary coverage options are already set")
        }
        if (parameters.any { it == "--profile" || it == "-p" || it.startsWith("--profile=") }) {
            return Result.Skipped("--profile is already set")
        }

        val scriptIndex = parameters.indexOfFirst { p ->
            p.endsWith(IDE_HELPER_SUFFIX) || BEHAT_SCRIPT_SUFFIXES.any { p.endsWith(it) }
        }
        if (scriptIndex < 0) return Result.Skipped("Behat executable not found in the command line")
        val remoteServiceDir = if (parameters[scriptIndex].endsWith(IDE_HELPER_SUFFIX)) {
            environment[BEHAT_DIR_ENV]?.substringBefore("/vendor/")
                ?: configValue(parameters)?.substringBeforeLast('/')
                ?: return Result.Skipped("neither $BEHAT_DIR_ENV nor --config is set for the PhpStorm Behat helper")
        } else {
            parameters[scriptIndex].substringBefore("/vendor/")
        }

        val (remoteRoot, serviceDir) = matchServiceDir(remoteServiceDir, isLocalDir)
            ?: return Result.Skipped("$remoteServiceDir does not correspond to a directory in the git root")

        val configRelative = configPath(parameters, remoteRoot)
            ?: listOf(
                "$serviceDir/behat.yml",
                "$serviceDir/features/behat.yml",
                "$serviceDir/behat.php",
                "$serviceDir/features/behat.php",
            ).firstOrNull { readLocalFile(it) != null }
            ?: return Result.Skipped("Behat config for $serviceDir not found")
        val config = readLocalFile(configRelative)
            ?: return Result.Skipped("Behat config $configRelative not found")
        val profile = findCoverageProfile(config)
            ?: return Result.Skipped("$configRelative has no profile with the $EXTENSION_MARKER extension")

        val interpreterArgs = listOf("-d", "pcov.enabled=1", "-d", "pcov.directory=$remoteRoot")
        val behatArgs = listOfNotNull(
            profile.takeIf { it.isNotEmpty() }?.let { "--profile=$it" },
            "--binary-coverage-target=${remoteRoot.trimEnd('/')}/${coverageDir.trim('/')}/$REPORT_FILE_NAME",
            coverageRootArgument(serviceDir, remoteRoot, readLocalFile),
        )
        val patched = parameters.subList(0, scriptIndex) + interpreterArgs +
            parameters.subList(scriptIndex, parameters.size) + behatArgs
        return Result.Patched(patched, serviceDir)
    }

    /**
     * 1.x expects a relative service prefix; 2.x expects the absolute interpreter-side repository
     * root. Prefer the installed controller over Composer metadata (the vendor tree may be patched
     * or not yet match the lock). Missing metadata keeps the legacy command line working.
     */
    private fun coverageRootArgument(
        serviceDir: String,
        remoteRoot: String,
        readLocalFile: (String) -> String?,
    ): String {
        val controller = readLocalFile("$serviceDir/vendor/$REPORTER_PACKAGE/src/Cli/CoverageReporterController.php")
        val usesRepoRoot = when {
            controller != null && Regex("""['"]$REPO_ROOT_OPTION['"]""").containsMatchIn(controller) -> true
            controller != null && Regex("""['"]$LEGACY_ROOT_OPTION['"]""").containsMatchIn(controller) -> false
            else -> {
                val major = sequenceOf("$serviceDir/vendor/composer/installed.json", "$serviceDir/composer.lock")
                    .mapNotNull { readLocalFile(it)?.let(::reporterMajorVersion) }
                    .firstOrNull()
                major != null && major >= 2
            }
        }
        return if (usesRepoRoot) "--$REPO_ROOT_OPTION=$remoteRoot" else "--$LEGACY_ROOT_OPTION=$serviceDir"
    }

    private fun reporterMajorVersion(metadata: String): Int? {
        val json = runCatching { Json.parseToJsonElement(metadata) }.getOrNull() ?: return null
        val packages = when (json) {
            is JsonArray -> json.toList() // Composer 1 installed.json
            is JsonObject -> listOf("packages", "packages-dev").flatMap { key ->
                (json[key] as? JsonArray)?.toList().orEmpty()
            }
            else -> return null
        }
        val reporter = packages.filterIsInstance<JsonObject>().firstOrNull {
            (it["name"] as? JsonPrimitive)?.contentOrNull == REPORTER_PACKAGE
        } ?: return null
        val version = (reporter["version"] as? JsonPrimitive)?.contentOrNull ?: return null
        return Regex("""^v?(\d+)\.""").find(version)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Splits an interpreter-side service directory into (interpreter-side git root, git-root-relative
     * service directory) by finding the shortest trailing path that exists under the host git root:
     * `/web/app/raketa` → (`/web`, `app/raketa`), `/web/api/hotels` → (`/web`, `api/hotels`).
     */
    internal fun matchServiceDir(remoteServiceDir: String, isLocalDir: (String) -> Boolean): Pair<String, String>? {
        val segments = remoteServiceDir.trimEnd('/').split('/').filter { it.isNotEmpty() }
        for (count in 1..segments.size) {
            val relative = segments.takeLast(count).joinToString("/")
            if (isLocalDir(relative)) {
                val root = "/" + segments.dropLast(count).joinToString("/")
                return root.trimEnd('/').ifEmpty { "/" } to relative
            }
        }
        return null
    }

    private fun configValue(parameters: List<String>): String? {
        val index = parameters.indexOfFirst { it == "--config" || it == "-c" || it.startsWith("--config=") }
        if (index < 0) return null
        return if (parameters[index].startsWith("--config=")) {
            parameters[index].substringAfter("=")
        } else {
            parameters.getOrNull(index + 1)
        }
    }

    private fun configPath(parameters: List<String>, remoteRoot: String): String? {
        val value = configValue(parameters) ?: return null
        val prefix = remoteRoot.trimEnd('/') + "/"
        return if (value.startsWith(prefix)) value.removePrefix(prefix) else null
    }

    /**
     * Returns the Behat profile that enables the binary coverage extension: `coverage-clover` if it
     * does, otherwise the first such profile; an empty string when it is enabled in `default`
     * (no `--profile` needed); null when no profile enables it.
     */
    internal fun findCoverageProfile(config: String): String? {
        val profiles = LinkedHashSet<String>()
        var current: String? = null
        for (line in config.lineSequence()) {
            TOP_LEVEL_KEY.find(line)?.let { current = it.groupValues[1] }
            val profile = current ?: continue
            if (line.contains(EXTENSION_MARKER) && !line.trimStart().startsWith("#")) {
                profiles += profile
            }
        }
        return when {
            "default" in profiles -> ""
            PREFERRED_PROFILE in profiles -> PREFERRED_PROFILE
            else -> profiles.firstOrNull()
        }
    }
}
