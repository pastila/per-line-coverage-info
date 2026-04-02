package com.github.yakov255.perlinecoverageinfo

/**
 * Exception thrown when coverage API operations fail.
 * Contains detailed diagnostic information for error reporting.
 */
class CoverageApiException(
    message: String,
    cause: Throwable? = null,
    val details: Map<String, String?> = emptyMap()
) : RuntimeException(buildDetailedMessage(message, details), cause) {

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
