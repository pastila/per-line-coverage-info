package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.Cov4Reader
import com.github.yakov255.perlinecoverageinfo.CovdClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Proves the harness itself: real covd + fake GitLab + git repo produce a readable COV4. */
class CovdHarnessSmokeTest : CovdTestBase() {

    @Test
    fun `ensure resolves the fake pipeline and the cache is readable`() {
        readyPipeline()

        val response = CovdClient.ensure(request())

        assertEquals(response.toString(), "ready", response.state)
        assertEquals(head, response.commit)
        val path = assertNotNull(response.cov4Path).let { File(response.cov4Path!!) }
        assertTrue("cov4 missing: $path", path.isFile)
        assertTrue(path.path.startsWith(cacheDir.path))
        assertEquals(1, gitlab.downloads)

        Cov4Reader(path).use { reader ->
            val lines = reader.getCoverage("core/web/src/Foo.php")
            assertNotNull("Foo.php must be in the cache", lines)
            assertTrue(lines!!.isNotEmpty())
        }
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
