package com.github.yakov255.perlinecoverageinfo

/**
 * Looks up coverage for a file by trying the provided path candidates in order.
 * Each candidate is also tried with a leading-slash variant.
 *
 * Coverage data stores git-root-relative paths (e.g. `api/hotels/src/Foo.php`), so
 * callers should provide the git-relative path as the first (and typically only) candidate.
 *
 * Used by both [CoverageHighlighter] and [AffectedTestsService] so the lookup logic
 * stays consistent.
 */
object CoveragePathResolver {

    /**
     * Returns the first non-null coverage map for the given [candidates], or null if none matches.
     * Each candidate is tried directly and with a `/$candidate` prefix.
     */
    fun resolve(
        data: CoverageDataService,
        candidates: List<String>,
    ): Map<Int, List<String>>? {
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            data.getCoverage(candidate)?.let { return it }
            data.getCoverage("/$candidate")?.let { return it }
        }
        return null
    }
}
