package com.github.yakov255.perlinecoverageinfo

import java.io.File

/**
 * Finds the Behat project a feature file belongs to.
 *
 * A monorepo has several independent Behat setups (`core/behat.yml`, `api/rail/behat.yml`,
 * `api/bus/behat.php`, …). Behat resolves relative specification paths against the directory
 * of the config file it was started with, so a run must use the config that owns the feature
 * file — and the paths must be relative to that directory.
 *
 * The project is the nearest ancestor directory of the feature file that holds a Behat config,
 * searched upwards no further than [stopAt] (the project root).
 */
object BehatConfigLocator {

    private val CONFIG_NAMES = listOf(
        "behat.yml",
        "behat.yml.dist",
        "behat.php",
        "behat.php.dist",
        "behat.json",
        "behat.json.dist",
    )

    /** @param configFile absolute path of the Behat config, [workingDir] the directory Behat runs in */
    data class BehatProject(val configFile: String, val workingDir: String)

    fun locate(
        featureFilePath: String,
        stopAt: String?,
        isFile: (String) -> Boolean = { File(it).isFile },
    ): BehatProject? {
        val stop = stopAt?.takeIf { it.isNotBlank() }?.let { File(it).absolutePath.trimEnd(File.separatorChar) }
        var current: File? = File(featureFilePath).absoluteFile.parentFile
        while (current != null) {
            val dir: File = current
            for (name in CONFIG_NAMES) {
                val candidate = File(dir, name)
                if (isFile(candidate.path)) return BehatProject(candidate.path, dir.path)
                // Some projects keep the config in a `config/` subdirectory; Behat still runs
                // in the directory above it.
                val inConfigDir = File(File(dir, "config"), name)
                if (isFile(inConfigDir.path)) return BehatProject(inConfigDir.path, dir.path)
            }
            if (stop != null && dir.path.trimEnd(File.separatorChar) == stop) break
            current = dir.parentFile
        }
        return null
    }
}
