package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.diagnostic.Logger

/**
 * Logger wrapper for plugin code that fans every log call out to both:
 *   1. the IntelliJ platform logger (so messages still land in `idea.log`), and
 *   2. [CoverageLogService] (so messages appear in the Log tab of the
 *      Coverage Tests tool window).
 *
 * Use [CoverageLog.get] in place of [Logger.getInstance] for any class in
 * this plugin. The surface mirrors [Logger]'s common methods so call sites
 * don't need to change beyond the declaration.
 */
class CoverageLog private constructor(
    private val delegate: Logger,
    private val name: String,
) {

    fun debug(message: String) {
        delegate.debug(message)
        publish(CoverageLogService.Level.DEBUG, message, null)
    }

    fun debug(message: String, throwable: Throwable?) {
        delegate.debug(message, throwable)
        publish(CoverageLogService.Level.DEBUG, message, throwable)
    }

    fun info(message: String) {
        delegate.info(message)
        publish(CoverageLogService.Level.INFO, message, null)
    }

    fun info(message: String, throwable: Throwable?) {
        delegate.info(message, throwable)
        publish(CoverageLogService.Level.INFO, message, throwable)
    }

    fun warn(message: String) {
        delegate.warn(message)
        publish(CoverageLogService.Level.WARN, message, null)
    }

    fun warn(message: String, throwable: Throwable?) {
        delegate.warn(message, throwable)
        publish(CoverageLogService.Level.WARN, message, throwable)
    }

    fun error(message: String) {
        delegate.error(message)
        publish(CoverageLogService.Level.ERROR, message, null)
    }

    fun error(message: String, throwable: Throwable?) {
        delegate.error(message, throwable)
        publish(CoverageLogService.Level.ERROR, message, throwable)
    }

    private fun publish(level: CoverageLogService.Level, message: String, throwable: Throwable?) {
        try {
            CoverageLogService.getInstance().append(
                CoverageLogService.LogEntry(
                    timestampMs = System.currentTimeMillis(),
                    level = level,
                    loggerName = name,
                    message = message,
                    throwable = throwable,
                )
            )
        } catch (_: Throwable) {
            // Never let in-IDE logging break real logging. Swallowed on purpose.
        }
    }

    companion object {
        fun get(cls: Class<*>): CoverageLog {
            return CoverageLog(
                delegate = Logger.getInstance(cls),
                name = cls.simpleName ?: cls.name,
            )
        }
    }
}
