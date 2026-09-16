package com.github.yakov255.perlinecoverageinfo

/**
 * Rewrites a Behat command line so the run writes a `.covt` into the local coverage directory.
 *
 * Pure logic — paths in the command line are interpreter-side (e.g. `/web/core/...` inside a
 * Docker container), the git root is host-side; the two are matched by the service directory
 * both have in common.
 */
object LocalCoverageCommand {

    const val REPORT_FILE_NAME = "local.covt"

    private const val EXTENSION_MARKER = "BehatBinaryCoverage"
    private const val PREFERRED_PROFILE = "coverage-clover"
    private val BEHAT_SCRIPT_SUFFIXES = listOf("/vendor/behat/behat/bin/behat", "/vendor/bin/behat")
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
     */
    fun patch(
        parameters: List<String>,
        coverageDir: String,
        isLocalDir: (String) -> Boolean,
        readLocalFile: (String) -> String?,
    ): Result {
        if (parameters.any { it.startsWith("--binary-coverage-target") }) {
            return Result.Skipped("binary coverage options are already set")
        }
        if (parameters.any { it == "--profile" || it == "-p" || it.startsWith("--profile=") }) {
            return Result.Skipped("--profile is already set")
        }

        val scriptIndex = parameters.indexOfFirst { p -> BEHAT_SCRIPT_SUFFIXES.any { p.endsWith(it) } }
        if (scriptIndex < 0) return Result.Skipped("Behat executable not found in the command line")
        val remoteServiceDir = parameters[scriptIndex].substringBefore("/vendor/")

        val (remoteRoot, serviceDir) = matchServiceDir(remoteServiceDir, isLocalDir)
            ?: return Result.Skipped("$remoteServiceDir does not correspond to a directory in the git root")

        val configRelative = configPath(parameters, remoteRoot) ?: "$serviceDir/behat.yml"
        val config = readLocalFile(configRelative)
            ?: return Result.Skipped("Behat config $configRelative not found")
        val profile = findCoverageProfile(config)
            ?: return Result.Skipped("$configRelative has no profile with the $EXTENSION_MARKER extension")

        val interpreterArgs = listOf("-d", "pcov.enabled=1", "-d", "pcov.directory=$remoteRoot")
        val behatArgs = listOfNotNull(
            profile.takeIf { it.isNotEmpty() }?.let { "--profile=$it" },
            "--binary-coverage-target=${remoteRoot.trimEnd('/')}/${coverageDir.trim('/')}/$REPORT_FILE_NAME",
            "--binary-coverage-root=$serviceDir",
        )
        val patched = parameters.subList(0, scriptIndex) + interpreterArgs +
            parameters.subList(scriptIndex, parameters.size) + behatArgs
        return Result.Patched(patched, serviceDir)
    }

    /**
     * Splits an interpreter-side service directory into (interpreter-side git root, git-root-relative
     * service directory) by finding the shortest trailing path that exists under the host git root:
     * `/web/core` → (`/web`, `core`), `/web/api/hotels` → (`/web`, `api/hotels`).
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

    private fun configPath(parameters: List<String>, remoteRoot: String): String? {
        val index = parameters.indexOfFirst { it == "--config" || it == "-c" || it.startsWith("--config=") }
        if (index < 0) return null
        val value = if (parameters[index].startsWith("--config=")) {
            parameters[index].substringAfter("=")
        } else {
            parameters.getOrNull(index + 1) ?: return null
        }
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
