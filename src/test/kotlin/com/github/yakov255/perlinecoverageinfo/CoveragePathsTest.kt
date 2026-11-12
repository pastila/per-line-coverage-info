package com.github.yakov255.perlinecoverageinfo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * `-Dcoverage.cacheRoot`, `-Dcoverage.perf.dir` and `-Dcoverage.covdResponses`: pure resolution
 * plus the property read, with every property restored afterwards.
 */
class CoveragePathsTest {

    private val saved = mutableMapOf<String, String?>()

    @After
    fun tearDown() {
        saved.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    private fun setProperty(key: String, value: String?) {
        if (!saved.containsKey(key)) saved[key] = System.getProperty(key)
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    @Test
    fun `cache root comes from the property and falls back to the home directory`() {
        assertEquals(File("/tmp/rp-cache"), CoveragePaths.resolveCacheRoot("/tmp/rp-cache", "/home/user"))
        assertEquals(File("/home/user", ".cache/coverage-plugin"), CoveragePaths.resolveCacheRoot(null, "/home/user"))
        assertEquals(File("/home/user", ".cache/coverage-plugin"), CoveragePaths.resolveCacheRoot("   ", "/home/user"))
    }

    @Test
    fun `the test seam still wins over the cache root property`() {
        setProperty(CoveragePaths.CACHE_ROOT_PROPERTY, "/tmp/from-property")

        assertEquals(File("/tmp/from-property"), CoveragePaths.cacheRoot(null))
        assertEquals(File("/tmp/from-seam"), CoveragePaths.cacheRoot(File("/tmp/from-seam")))
    }

    @Test
    fun `perf dir defaults to the cache root perf directory`() {
        setProperty(CoveragePaths.CACHE_ROOT_PROPERTY, "/tmp/rp-cache")
        setProperty(CoveragePaths.PERF_DIR_PROPERTY, null)

        assertEquals(File("/tmp/rp-cache/perf"), CoveragePaths.perfDir())
    }

    @Test
    fun `perf dir property wins over the cache root`() {
        setProperty(CoveragePaths.CACHE_ROOT_PROPERTY, "/tmp/rp-cache")
        setProperty(CoveragePaths.PERF_DIR_PROPERTY, "/tmp/rp-perf")

        assertEquals(File("/tmp/rp-perf"), CoveragePaths.perfDir())
    }

    @Test
    fun `saved covd answers are off unless a directory is given`() {
        setProperty(CoveragePaths.COVD_RESPONSES_PROPERTY, null)
        assertNull(CoveragePaths.covdResponsesDir())

        setProperty(CoveragePaths.COVD_RESPONSES_PROPERTY, "  ")
        assertNull(CoveragePaths.covdResponsesDir())

        setProperty(CoveragePaths.COVD_RESPONSES_PROPERTY, "/tmp/rp-responses")
        assertEquals(File("/tmp/rp-responses"), CoveragePaths.covdResponsesDir())
    }
}
