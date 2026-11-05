package com.github.yakov255.perlinecoverageinfo

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
}
