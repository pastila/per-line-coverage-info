package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/**
 * One real, anonymous scan of the shared GitLab: the newest 30 successful pipelines for the branch
 * inventory plus the recent `master` pipelines for the baseline. Skipped (not failed) when the host
 * is unreachable or has no coverage in the window: the suite must stay green without network.
 */
class GitLabInventoryLiveTest {

    @Test
    fun `anonymous inventory of 30 pipelines and the master baseline`() {
        val inventory = GitLabInventory(
            client = GitLabClient(defaultGitLabUrl(), pauseMs = 300),
        )
        val snapshot = try {
            inventory.scan(limit = 30)
        } catch (e: IOException) {
            assumeNoException("gitlab.raketa.in is not reachable", e)
            return
        }

        assertEquals("335", snapshot.projectId)
        assertEquals(30, snapshot.pipelinesScanned)
        val withCoverage = snapshot.coverage.filter { it.components.isNotEmpty() }
        assumeTrue("no pipeline in the window published coverage", withCoverage.isNotEmpty())

        withCoverage.forEach { pipeline ->
            pipeline.components.forEach { (component, coverage) ->
                assertTrue("unexpected component token: $component", component.isNotBlank())
                assertTrue("shards must be positive for $component", coverage.shards > 0)
                assertTrue("bytes must not be negative for $component", coverage.bytes >= 0)
            }
            assertEquals("the API returns full commit ids", 40, pipeline.pipeline.sha.length)
        }

        // Scheduled master pipelines publish no coverage; at least one push pipeline must have it.
        snapshot.coverage.filter { it.pipeline.source == "schedule" }.forEach { scheduled ->
            assertTrue(
                "a scheduled pipeline must not publish coverage: ${scheduled.pipeline.id}",
                scheduled.components.isEmpty(),
            )
        }

        val raketa = withCoverage.filter { it.bytesOf(RAKETA_COMPONENT) > 0 }
        assumeTrue("no raketa coverage with live archives in the window", raketa.isNotEmpty())
        raketa.forEach { pipeline ->
            val bytes = pipeline.bytesOf(RAKETA_COMPONENT)
            assertTrue(
                "raketa coverage of ${pipeline.pipeline.ref} is $bytes bytes, outside the known spread",
                bytes in 1_000_000..80_000_000,
            )
        }

        // The baseline is looked up directly on its branch: it rarely appears in the newest 30.
        val masterCoverage = try {
            inventory.pipelines(status = "success", limit = 10, ref = COVERAGE_BRANCH)
                .map { CoverageSummarizer.summarize(it, inventory.jobs(it.id)) }
        } catch (e: IOException) {
            assumeNoException("gitlab.raketa.in is not reachable", e)
            return
        }
        val baseline = CoverageInventory.selectBaseline(masterCoverage)
        assumeTrue("no master push pipeline with live coverage", baseline != null)

        assertEquals(COVERAGE_BRANCH, baseline!!.pipeline.ref)
        assertEquals("push", baseline.pipeline.source)
        assertTrue(baseline.shardsOf(RAKETA_COMPONENT) > 0)
        assertTrue(baseline.bytesOf(RAKETA_COMPONENT) > 0)
    }
}
