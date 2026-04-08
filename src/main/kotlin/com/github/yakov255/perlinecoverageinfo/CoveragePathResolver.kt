package com.github.yakov255.perlinecoverageinfo

/**
 * Looks up coverage for a file by trying several path normalizations the way coverage
 * data may be stored (absolute, project-relative, leading-slash, suffix match).
 *
 * Used by both [CoverageHighlighter] and [AffectedTestsService] so the lookup logic
 * stays consistent.
 */
object CoveragePathResolver {

    /**
     * Tries [candidates] in order against [data] and falls back to a suffix match.
     * Returns the first non-null coverage map, or null if no candidate matches.
     *
     * Each candidate is also tried with a leading slash variant.
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
        // Suffix match across all known files (the same fallback CoverageHighlighter used).
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            val match = data.allFiles().firstNotNullOfOrNull { storedPath ->
                if (storedPath.endsWith(candidate) || candidate.endsWith(storedPath.trimStart('/'))) {
                    data.getCoverage(storedPath)
                } else null
            }
            if (match != null) return match
        }
        return null
    }
}
