package com.github.yakov255.perlinecoverageinfo

import java.io.File

/** How one HEAD observation relates to the revision seen before. */
internal enum class HeadChange {
    /** git could not read a revision: nothing to compare, the previous state is kept. */
    NO_HEAD,

    /** The first revision a tracker sees only seeds it; the startup activity loads it. */
    FIRST,

    /** Same commit as before. */
    UNCHANGED,

    /** Another commit than before. */
    CHANGED,
}

/**
 * Remembers the last HEAD revision of one repository and classifies the next observation.
 *
 * Both the Git4Idea listener and the pipeline poller report into one tracker, so a HEAD reported
 * by one entry point is not reported again by the other. Thread-safe: the two entry points run on
 * different threads.
 */
internal class HeadRevisionTracker {

    private val lock = Any()
    private var last: String? = null

    /** The last revision this tracker accepted, or null before the first one. */
    val lastKnown: String?
        get() = synchronized(lock) { last }

    /** Records [revision] and tells how it relates to the previous one. */
    fun observe(revision: String?): HeadChange = synchronized(lock) {
        if (revision.isNullOrBlank()) return HeadChange.NO_HEAD
        val previous = last
        last = revision
        return when (previous) {
            null -> HeadChange.FIRST
            revision -> HeadChange.UNCHANGED
            else -> HeadChange.CHANGED
        }
    }
}

/**
 * Decides whether a repository change belongs to the coverage of the project.
 *
 * A project can contain submodules or several repositories; their HEAD moves have nothing to do
 * with the commit the coverage describes, and treating them as coverage changes causes false
 * reloads and drops the failure block of the real commit.
 */
internal object CoverageRepositoryFilter {

    /**
     * @param repositoryRoot root of the repository that changed.
     * @param coverageRoot git root of the loaded coverage, or null when it is not known yet; an
     *   unknown root accepts everything, which keeps the single-repository behaviour.
     */
    fun accepts(repositoryRoot: File?, coverageRoot: File?): Boolean {
        if (coverageRoot == null) return true
        if (repositoryRoot == null) return false
        return canonical(repositoryRoot) == canonical(coverageRoot)
    }

    private fun canonical(file: File): String = try {
        file.canonicalPath
    } catch (_: Exception) {
        file.absolutePath
    }
}
