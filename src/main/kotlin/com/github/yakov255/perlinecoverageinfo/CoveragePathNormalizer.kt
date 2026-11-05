package com.github.yakov255.perlinecoverageinfo

import java.io.File

/**
 * Normalizes paths written by the PHP coverage extension into git-root-relative paths.
 *
 * The extension prefixes every entry with `--binary-coverage-root` (the CI `SERVICE_DIR`)
 * and keeps the container path minus its leading `/`. For the raketa component that
 * produced `core/web/...` before the move and `app/raketa/web/...` afterwards, while the
 * actual repository paths are `...` and `app/raketa/...` respectively.
 *
 * Behat feature files inside `app/raketa/features` additionally lose the `features/`
 * segment, because it is the behat base directory. Tests outside that base keep the
 * container prefix and only need the [RAKETA_CONTAINER_PREFIX] strip.
 */
object CoveragePathNormalizer {

    private const val RAKETA_CONTAINER_PREFIX = "app/raketa/web/"
    private const val LEGACY_CONTAINER_PREFIX = "core/web/"

    private const val RAKETA_ROOT = "app/raketa/"
    private const val RAKETA_FEATURES = "app/raketa/features/"

    /** Fallback when the repository is not available (e.g. reading an old cache offline). */
    private val DEFAULT_RAKETA_FEATURE_SUITES = setOf("E2E", "FeatureTest", "Stubs", "Support")

    /**
     * Top-level entries of `app/raketa/features` that are not top-level entries of
     * `app/raketa` — the behat suites whose `features/` segment is stripped by the
     * coverage extension (E2E, FeatureTest, Stubs, Support, ...).
     */
    fun raketaFeatureSuiteEntries(gitRoot: File?): Set<String> {
        if (gitRoot == null) return DEFAULT_RAKETA_FEATURE_SUITES
        val appDir = File(gitRoot, "app/raketa")
        val featuresDir = File(appDir, "features")
        if (!featuresDir.isDirectory) return DEFAULT_RAKETA_FEATURE_SUITES
        val topLevel = appDir.list()?.toSet().orEmpty()
        val features = featuresDir.list()?.toSet().orEmpty()
        return (features - topLevel).ifEmpty { DEFAULT_RAKETA_FEATURE_SUITES }
    }

    /** Maps a coverage file key to a git-root-relative path. */
    fun normalizeRaketaFilePath(path: String): String = when {
        path.startsWith(RAKETA_CONTAINER_PREFIX) -> path.removePrefix(RAKETA_CONTAINER_PREFIX)
        path.startsWith(LEGACY_CONTAINER_PREFIX) -> path.removePrefix(LEGACY_CONTAINER_PREFIX)
        else -> path
    }

    /**
     * Maps a behat test identifier (`<repo path>.feature:<line>`) to a git-root-relative
     * path. [featureSuiteEntries] are the top-level entries of `app/raketa/features` that
     * are not top-level entries of `app/raketa` (E2E, FeatureTest, Stubs, Support, ...).
     *
     * Non-behat test names and names without a line suffix are returned unchanged.
     * Idempotent: already-normalized paths are not prefixed twice.
     */
    fun normalizeRaketaBehatName(name: String, featureSuiteEntries: Set<String>): String {
        val separator = name.lastIndexOf(':')
        if (separator < 0) return name
        val path = name.substring(0, separator)
        if (!path.endsWith(".feature")) return name
        val suffix = name.substring(separator)

        val normalized = normalizeRaketaFilePath(path)
        val withFeatures = if (
            normalized.startsWith(RAKETA_ROOT) &&
            !normalized.startsWith(RAKETA_FEATURES)
        ) {
            val rest = normalized.removePrefix(RAKETA_ROOT)
            val firstSegment = rest.substringBefore('/')
            if (firstSegment in featureSuiteEntries) RAKETA_FEATURES + rest else normalized
        } else {
            normalized
        }

        return withFeatures + suffix
    }

    /** Normalizes a whole coverage map (file paths and test names) for the raketa component. */
    fun normalizeRaketaCoverage(
        coverage: Map<String, Map<Int, List<String>>>,
        gitRoot: File?,
    ): Map<String, Map<Int, List<String>>> {
        val suites = raketaFeatureSuiteEntries(gitRoot)
        return coverage.entries.associate { (path, lineMap) ->
            normalizeRaketaFilePath(path) to lineMap.mapValues { (_, tests) ->
                tests.map { normalizeRaketaBehatName(it, suites) }
            }
        }
    }

    /** Includes header-only tests so a local re-run also supersedes CI files it no longer reaches. */
    fun normalizeRaketaRun(data: BinaryCoverageParser.CovtData, gitRoot: File?): BinaryCoverageParser.CovtData {
        val suites = raketaFeatureSuiteEntries(gitRoot)
        return BinaryCoverageParser.CovtData(
            data.tests.map { normalizeRaketaBehatName(it, suites) },
            normalizeRaketaCoverage(data.coverage, gitRoot),
        )
    }
}
