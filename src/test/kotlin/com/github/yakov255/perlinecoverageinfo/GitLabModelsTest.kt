package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class GitLabModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testDeserializeGitLabProject() {
        val input = """
            {"id": 123, "name": "my-project", "path_with_namespace": "org/my-project", "web_url": "https://gitlab.com/org/my-project"}
        """.trimIndent()

        val project = json.decodeFromString<GitLabProject>(input)

        assertEquals(123L, project.id)
        assertEquals("my-project", project.name)
        assertEquals("org/my-project", project.pathWithNamespace)
        assertEquals("https://gitlab.com/org/my-project", project.webUrl)
    }

    @Test
    fun testDeserializeGitLabPipeline() {
        val input = """
            {"id": 456, "sha": "abc123", "ref": "main", "status": "success", "web_url": "https://gitlab.com/org/my-project/-/pipelines/456"}
        """.trimIndent()

        val pipeline = json.decodeFromString<GitLabPipeline>(input)

        assertEquals(456L, pipeline.id)
        assertEquals("abc123", pipeline.sha)
        assertEquals("main", pipeline.ref)
        assertEquals("success", pipeline.status)
        assertEquals("https://gitlab.com/org/my-project/-/pipelines/456", pipeline.webUrl)
    }

    @Test
    fun testDeserializeGitLabJob() {
        val input = """
            {"id": 789, "name": "test-job", "status": "success", "pipeline": {"id": 456}, "artifacts": [{"filename": "coverage.zip", "size": 1024}]}
        """.trimIndent()

        val job = json.decodeFromString<GitLabJob>(input)

        assertEquals(789L, job.id)
        assertEquals("test-job", job.name)
        assertEquals("success", job.status)
        assertEquals(456L, job.pipeline.id)
        assertEquals(1, job.artifacts.size)
        assertEquals("coverage.zip", job.artifacts[0].filename)
        assertEquals(1024L, job.artifacts[0].size)
    }

    @Test
    fun testDeserializeGitLabJobWithoutArtifacts() {
        val input = """
            {"id": 100, "name": "lint-job", "status": "failed", "pipeline": {"id": 200}}
        """.trimIndent()

        val job = json.decodeFromString<GitLabJob>(input)

        assertEquals(100L, job.id)
        assertEquals("lint-job", job.name)
        assertEquals("failed", job.status)
        assertEquals(200L, job.pipeline.id)
        assertTrue("Artifacts should default to empty list", job.artifacts.isEmpty())
    }
}
