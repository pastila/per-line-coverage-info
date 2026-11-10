package com.github.yakov255.perlinecoverageinfo

import java.io.File

/**
 * Directories the plugin works with, resolved once per call from system properties.
 *
 * The properties exist so a real-project test can point a sandbox IDE at its own directory
 * tree (see `docs/real-project-testing.md`); a normal run never sets them.
 */
object CoveragePaths {

    const val CACHE_ROOT_PROPERTY = "coverage.cacheRoot"
    const val PERF_DIR_PROPERTY = "coverage.perf.dir"
    const val COVD_RESPONSES_PROPERTY = "coverage.covdResponses"

    /**
     * The covd cache root (`--cache-dir`), which also names the daemon shared by every IDE
     * instance. [override] is the test seam; it wins over the system property.
     */
    fun cacheRoot(override: File? = null): File {
        if (override != null) return override
        return resolveCacheRoot(System.getProperty(CACHE_ROOT_PROPERTY), System.getProperty("user.home"))
    }

    /** Pure resolution of the cache root: the property when set, `~/.cache/coverage-plugin` otherwise. */
    fun resolveCacheRoot(property: String?, home: String?): File {
        val explicit = property?.trim()
        if (!explicit.isNullOrEmpty()) return File(explicit)
        return File(home ?: ".", ".cache/coverage-plugin")
    }

    /** Perf-log directory: the property when set, `<cacheRoot>/perf` otherwise. */
    fun perfDir(): File {
        val explicit = System.getProperty(PERF_DIR_PROPERTY)?.trim()
        if (!explicit.isNullOrEmpty()) return File(explicit)
        return File(cacheRoot(), "perf")
    }

    /** Directory for saved covd answers, or null when `coverage.covdResponses` is not set. */
    fun covdResponsesDir(): File? {
        val explicit = System.getProperty(COVD_RESPONSES_PROPERTY)?.trim()
        return if (explicit.isNullOrEmpty()) null else File(explicit)
    }
}
