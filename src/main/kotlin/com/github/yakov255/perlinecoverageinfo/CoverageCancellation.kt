package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator

/** Cancellation context for coverage CPU/disk loops; ordinary callers have no context. */
internal object CoverageCancellation {
    private val current = ThreadLocal<ProgressIndicator>()
    fun check() { current.get()?.checkCanceled() }
    fun currentIndicator(): ProgressIndicator? = current.get()
    fun indicator(): ProgressIndicator = current.get() ?: DumbProgressIndicator.INSTANCE
    fun <T> withIndicator(indicator: ProgressIndicator, action: () -> T): T {
        val previous = current.get()
        current.set(indicator)
        try { return action() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
