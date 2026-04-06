package com.github.yakov255.perlinecoverageinfo

/**
 * Category of the coverage error, used to choose a dialog title.
 */
enum class CoverageErrorKind {
    NETWORK,
    GITLAB_API,
    PARSE,
    ARTIFACT_PARSE,
    GIT,
    NO_DATA,
    PROJECT_SETUP,
}

/**
 * Exception thrown when coverage API operations fail.
 * Contains detailed diagnostic information for error reporting.
 *
 * [userMessage] is a short, user-facing description shown in modal dialogs.
 * [kind] determines the dialog title.
 */
class CoverageApiException(
    message: String,
    cause: Throwable? = null,
    val details: Map<String, String?> = emptyMap(),
    val kind: CoverageErrorKind = CoverageErrorKind.NETWORK,
) : RuntimeException(buildDetailedMessage(message, details), cause) {

    /** Short headline suitable for a modal dialog body. */
    val userMessage: String = message

    companion object {
        private fun buildDetailedMessage(message: String, details: Map<String, String?>): String {
            if (details.isEmpty()) return message
            val detailLines = details.entries
                .filter { it.value != null }
                .joinToString("\n") { "  ${it.key}: ${it.value}" }
            return "$message\nDetails:\n$detailLines"
        }
    }
}
