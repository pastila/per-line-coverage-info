package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The plugin is compiled with Kotlin 2.3, whose coroutine state machines carry debug metadata
 * version 2, while the platform's stdlib (2025.2) checks for version 1. The IDE installs coroutine
 * debug probes, so the first time a plugin coroutine is *resumed* the probes throw
 * `IllegalStateException: Debug metadata version mismatch` and the work silently never happens
 * (found by the real-project run: the startup load did not start at all).
 *
 * A `suspend fun` without a real suspension point compiles to a plain method and is harmless
 * (the MCP tools and `ProjectActivity.execute` are like that). A real suspension point makes the
 * compiler generate a state-machine class extending `ContinuationImpl` or `SuspendLambda`. This
 * test fails when the plugin's bytecode contains one: use callbacks, `invokeLater` and pooled
 * threads instead of `withContext`, `launch`, `async`, `delay` or `Dispatchers`.
 */
class NoCoroutineSuspensionTest {

    private val markers = listOf(
        "kotlin/coroutines/jvm/internal/ContinuationImpl",
        "kotlin/coroutines/jvm/internal/SuspendLambda",
        "kotlin/coroutines/jvm/internal/RestrictedContinuationImpl",
    ).map { it.toByteArray(Charsets.UTF_8) }

    /** Gradle's output for the plugin (`build/classes/kotlin/main`); the test class loader hides the code source. */
    private fun pluginClassesDir(): File? {
        val fromProject = File(System.getProperty("user.dir"), "build/classes/kotlin/main")
        if (fromProject.isDirectory) return fromProject
        val location = CoverageLog::class.java.protectionDomain?.codeSource?.location ?: return null
        return runCatching { File(location.toURI()) }.getOrNull()?.takeIf { it.isDirectory }
    }

    @Test
    fun `the plugin has no coroutine state machines`() {
        val dir = pluginClassesDir()
        assumeTrue("build/classes/kotlin/main is not available here", dir != null)
        assertTrue("the directory has no classes: $dir", dir!!.walkTopDown().any { it.extension == "class" })

        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .filter { file -> val bytes = file.readBytes(); markers.any { bytes.indexOf(it) >= 0 } }
            .map { it.relativeTo(dir).path }
            .sorted()
            .toList()

        assertTrue(
            "These classes contain a coroutine state machine (a real suspension point), which breaks " +
                "under the IDE's coroutine debug probes with Kotlin 2.3: use invokeLater / pooled " +
                "threads instead of withContext, launch, async or delay:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty(),
        )
    }

    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        outer@ for (i in 0..size - pattern.size) {
            for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
