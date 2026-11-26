package com.github.yakov255.perlinecoverageinfo

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Finds the Behat project a feature file belongs to.
 *
 * A monorepo has several independent Behat setups (`app/raketa/features/behat.yml`,
 * `api/rail/behat.yml`, `api/bus/behat.php`, …). Behat resolves relative specification paths against the directory
 * of the config file it was started with, so a run must use the config that owns the feature
 * file — and the paths must be relative to that directory.
 *
 * Resolution order:
 * 1. Nearest ancestor directory of the feature file up to [stopAt] (project root) that holds a Behat config
 *    directly, in `config/` (working dir stays one level above), or in `features/` (working dir is `features/`).
 * 2. Fallback for cross-directory suites (e.g. `app/light/raketa/features` declared via `directory: ../../light/raketa/features`
 *    in `app/raketa/features/behat.yml`): scans Behat configs under [stopAt] and matches their declared suite paths.
 */
object BehatConfigLocator {

    private val CONFIG_NAMES = listOf(
        "behat.yml",
        "behat.yml.dist",
        "behat.dist.yml",
        "behat.yaml",
        "behat.yaml.dist",
        "behat.dist.yaml",
        "behat.php",
        "behat.php.dist",
        "behat.dist.php",
        "behat.json",
        "behat.json.dist",
    )

    private val CONFIG_NAME_SET = CONFIG_NAMES.toSet()

    private const val MAX_SCAN_DEPTH = 3

    private val SKIP_SCAN_DIRS = setOf(
        "vendor",
        "node_modules",
        "storage",
        "var",
        "build",
        "dist",
        "cache",
        "tasks",
        "public",
        "frontend",
        "autotest",
        "database",
        "documentation",
        "target",
        "out",
    )

    private val YAML_PATHS_KEY_REGEX = Regex("""^(\s*)paths\s*:\s*(.*)$""")
    private val YAML_DIRECTORY_KEY_REGEX = Regex("""^\s*directory\s*:\s*(.+)$""")
    private val PHP_WITH_PATHS_REGEX = Regex("""->withPaths\s*\(([^)]*)\)""")
    private val QUOTED_STRING_REGEX = Regex("""(['"])(.*?)\1""")

    /** @param configFile absolute path of the Behat config, [workingDir] the directory Behat runs in */
    data class BehatProject(val configFile: String, val workingDir: String)

    private data class SuiteMapping(val suiteDir: Path, val project: BehatProject)

    fun locate(featureFilePath: String, stopAt: String?): BehatProject? =
        locate(featureFilePath, stopAt, { File(it).isFile }, ::discoverConfigs, ::readConfigText)

    fun locate(
        featureFilePath: String,
        stopAt: String?,
        isFile: (String) -> Boolean,
    ): BehatProject? = locateUpwards(featureFilePath, stopAt, isFile)

    internal fun locate(
        featureFilePath: String,
        stopAt: String?,
        isFile: (String) -> Boolean,
        discoverConfigs: (String) -> List<String>,
        readConfig: (String) -> String?,
    ): BehatProject? {
        locateUpwards(featureFilePath, stopAt, isFile)?.let { return it }
        val root = normalizeStop(stopAt) ?: return null
        val mappings = buildSuiteMappings(root, discoverConfigs, readConfig)
        return matchSuiteMappings(featureFilePath, mappings)
    }

    /**
     * Resolves the owning [BehatProject] for multiple feature files, scanning configs under [stopAt]
     * at most once if any file falls outside its config's ancestor directory tree.
     */
    fun locateAll(
        featureFilePaths: Collection<String>,
        stopAt: String?,
    ): Map<String, BehatProject?> =
        locateAll(featureFilePaths, stopAt, { File(it).isFile }, ::discoverConfigs, ::readConfigText)

    internal fun locateAll(
        featureFilePaths: Collection<String>,
        stopAt: String?,
        isFile: (String) -> Boolean,
        discoverConfigs: (String) -> List<String>,
        readConfig: (String) -> String?,
    ): Map<String, BehatProject?> {
        val result = LinkedHashMap<String, BehatProject?>(featureFilePaths.size)
        val unresolved = mutableListOf<String>()
        for (path in featureFilePaths) {
            val found = locateUpwards(path, stopAt, isFile)
            result[path] = found
            if (found == null) unresolved += path
        }
        if (unresolved.isNotEmpty()) {
            val root = normalizeStop(stopAt)
            if (root != null) {
                val mappings = buildSuiteMappings(root, discoverConfigs, readConfig)
                if (mappings.isNotEmpty()) {
                    for (path in unresolved) {
                        result[path] = matchSuiteMappings(path, mappings)
                    }
                }
            }
        }
        return result
    }

    private fun locateUpwards(
        featureFilePath: String,
        stopAt: String?,
        isFile: (String) -> Boolean,
    ): BehatProject? {
        val stop = normalizeStop(stopAt)
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
                // A config inside `features/` also owns sibling directories (e.g. bounded contexts)
                // and keeps `features/` as its working directory.
                val featuresDir = File(dir, "features")
                val inFeaturesDir = File(featuresDir, name)
                if (isFile(inFeaturesDir.path)) return BehatProject(inFeaturesDir.path, featuresDir.path)
            }
            if (stop != null && dir.path.trimEnd(File.separatorChar) == stop) break
            current = dir.parentFile
        }
        return null
    }

    private fun normalizeStop(stopAt: String?): String? =
        stopAt?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.normalize().path.trimEnd(File.separatorChar) }

    private fun projectForConfig(configFile: String): BehatProject? {
        val file = File(configFile).absoluteFile.normalize()
        val configDir = file.parentFile ?: return null
        val workingDir = if (configDir.name == "config") {
            configDir.parentFile?.path ?: configDir.path
        } else {
            configDir.path
        }
        return BehatProject(file.path, workingDir)
    }

    private fun buildSuiteMappings(
        rootDir: String,
        discoverConfigs: (String) -> List<String>,
        readConfig: (String) -> String?,
    ): List<SuiteMapping> {
        val mappings = mutableListOf<SuiteMapping>()
        for (configFile in discoverConfigs(rootDir)) {
            val project = projectForConfig(configFile) ?: continue
            val content = readConfig(configFile) ?: continue
            val configDir = File(project.configFile).parent ?: continue
            for (suiteDir in extractSuiteDirectories(project.configFile, content, configDir, project.workingDir)) {
                mappings += SuiteMapping(suiteDir, project)
            }
        }
        return mappings
    }

    private fun matchSuiteMappings(featureFilePath: String, mappings: List<SuiteMapping>): BehatProject? {
        if (mappings.isEmpty()) return null
        val featurePath = try {
            Paths.get(featureFilePath).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        return mappings
            .filter { featurePath.startsWith(it.suiteDir) }
            .maxWithOrNull(compareBy<SuiteMapping> { it.suiteDir.nameCount }.thenBy { it.suiteDir.toString().length })
            ?.project
    }

    internal fun extractSuiteDirectories(
        configFilePath: String,
        content: String,
        configDir: String,
        workingDir: String,
    ): List<Path> {
        val lower = configFilePath.lowercase()
        val rawPaths = when {
            lower.endsWith(".php") || lower.endsWith(".php.dist") -> extractPhpSuitePaths(content)
            lower.endsWith(".yml") || lower.endsWith(".yml.dist") ||
                lower.endsWith(".yaml") || lower.endsWith(".yaml.dist") -> extractYamlSuitePaths(content)
            else -> emptyList()
        }
        if (rawPaths.isEmpty()) return emptyList()
        val configDirPath = Paths.get(configDir).toAbsolutePath().normalize()
        val workingDirPath = Paths.get(workingDir).toAbsolutePath().normalize()
        val resolved = LinkedHashSet<Path>()
        for (raw in rawPaths) {
            val cleaned = unquote(raw)
            if (cleaned.isEmpty() || '*' in cleaned || "://" in cleaned) continue
            try {
                if ("%paths.base%" in cleaned) {
                    val expanded = cleaned.replace("%paths.base%", configDirPath.toString())
                    resolved.add(Paths.get(expanded).toAbsolutePath().normalize())
                } else {
                    val candidate = Paths.get(cleaned)
                    if (candidate.isAbsolute) {
                        resolved.add(candidate.normalize())
                    } else {
                        resolved.add(configDirPath.resolve(candidate).normalize())
                        if (workingDirPath != configDirPath) {
                            resolved.add(workingDirPath.resolve(candidate).normalize())
                        }
                    }
                }
            } catch (_: InvalidPathException) {
                // Ignore non-path strings in config
            }
        }
        return resolved.toList()
    }

    private fun extractYamlSuitePaths(content: String): List<String> {
        val results = mutableListOf<String>()
        var inPathsList = false
        var pathsIndent = 0
        for (rawLine in content.lineSequence()) {
            val line = stripYamlComment(rawLine)
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

            if (inPathsList) {
                if (indent > pathsIndent && trimmed.startsWith("-")) {
                    val item = trimmed.removePrefix("-").trim()
                    if (item.isNotEmpty()) results += item
                    continue
                }
                if (indent == pathsIndent && trimmed.startsWith("-")) {
                    val item = trimmed.removePrefix("-").trim()
                    if (item.isNotEmpty()) results += item
                    continue
                }
                inPathsList = false
            }

            val pathsMatch = YAML_PATHS_KEY_REGEX.matchEntire(line)
            if (pathsMatch != null) {
                val rest = pathsMatch.groupValues[2].trim()
                when {
                    rest.isEmpty() -> {
                        inPathsList = true
                        pathsIndent = pathsMatch.groupValues[1].length
                    }
                    rest.startsWith("[") && rest.endsWith("]") -> {
                        rest.substring(1, rest.length - 1)
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { results += it }
                    }
                    else -> results += rest
                }
                continue
            }

            val dirMatch = YAML_DIRECTORY_KEY_REGEX.matchEntire(line)
            if (dirMatch != null) {
                val value = dirMatch.groupValues[1].trim()
                if (value.isNotEmpty()) results += value
            }
        }
        return results
    }

    private fun extractPhpSuitePaths(content: String): List<String> {
        val results = mutableListOf<String>()
        for (match in PHP_WITH_PATHS_REGEX.findAll(content)) {
            val args = match.groupValues[1]
            for (str in QUOTED_STRING_REGEX.findAll(args)) {
                results += str.groupValues[2]
            }
        }
        return results
    }

    private fun stripYamlComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle && (i == 0 || line[i - 1] != '\\') -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace()) -> return line.substring(0, i)
            }
        }
        return line
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length >= 2 &&
            ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith('\'') && trimmed.endsWith('\'')))
        ) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }

    private fun discoverConfigs(rootDir: String): List<String> {
        val root = File(rootDir)
        if (!root.isDirectory) return emptyList()
        val found = mutableListOf<String>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isFile && child.name in CONFIG_NAME_SET) {
                    found += child.path
                }
            }
            if (depth >= MAX_SCAN_DEPTH) return
            for (child in children) {
                if (!child.isDirectory) continue
                val name = child.name
                if (name.startsWith(".") || name in SKIP_SCAN_DIRS) continue
                if (Files.isSymbolicLink(child.toPath())) continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return found
    }

    private fun readConfigText(path: String): String? = try {
        File(path).readText()
    } catch (_: Exception) {
        null
    }
}
