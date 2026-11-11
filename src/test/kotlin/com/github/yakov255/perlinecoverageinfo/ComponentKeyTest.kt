package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The component key sent to covd is `serviceDir`, and covd maps `/` to `_` in the cache file name:
 * two components must never end up in the same file, and the job tokens must stay distinct too.
 */
class ComponentKeyTest {

    private val entries = ComponentConfig.allEntries()

    @Test
    fun `service dirs stay unique after slashes become underscores`() {
        val fileKeys = entries.map { it.serviceDir.replace('/', '_') }
        assertEquals("two components would share a cache file: $fileKeys", fileKeys.size, fileKeys.toSet().size)
    }

    @Test
    fun `job names are unique`() {
        val names = entries.map { it.jobName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `a service dir is a valid covd component name`() {
        // Mirrors linecov/cache/validate.go: non-empty, relative, no backslash, no dot segments.
        for (entry in entries) {
            val key = entry.serviceDir
            assertTrue(key.isNotEmpty() && key.length <= 255)
            assertFalse("absolute: $key", key.startsWith("/"))
            assertFalse("backslash: $key", key.contains('\\'))
            assertFalse("dot segment: $key", key.split('/').any { it == "." || it == ".." || it.isEmpty() })
        }
    }

    @Test
    fun `only raketa has a service dir equal to its job name`() {
        val same = entries.filter { it.serviceDir == it.jobName }.map { it.serviceDir }
        assertEquals(
            "the raketa-only checks (path mode, path normalizer) rely on this; revisit them if it changes",
            listOf("raketa"),
            same,
        )
    }

    @Test
    fun `job prefixes use the job name and the cache key uses the service dir`() {
        val hotels = entries.single { it.serviceDir == "api/hotels" }
        assertEquals("test:behat:coverage:hotels", hotels.coverageJobPrefix)
        assertEquals("test:behat:hotels", hotels.jobPrefix)
        assertEquals("api_hotels", hotels.serviceDir.replace('/', '_'))
    }
}
