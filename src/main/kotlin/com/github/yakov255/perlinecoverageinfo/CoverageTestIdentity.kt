package com.github.yakov255.perlinecoverageinfo

/** Numeric coverage session IDs are not scenario paths or PHPUnit test names. */
internal object CoverageTestIdentity {
    const val UNNAMED_MESSAGE = "Coverage report has no test names; navigation is unavailable"

    fun isAnonymous(name: String): Boolean {
        if (':' in name) return false
        val id = name.substringAfterLast('/').substringAfterLast('\\')
        return id.isNotEmpty() && id.all { it in '0'..'9' }
    }
}
