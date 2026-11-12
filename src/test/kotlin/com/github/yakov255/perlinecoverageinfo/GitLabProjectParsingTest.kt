package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GitLabProjectParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `maps the snake case fields GitLab returns`() {
        val body = """
            [{"id":335,"name":"Raketa","path_with_namespace":"raketa/raketa",
              "web_url":"https://gitlab.raketa.in/raketa/raketa","description":"ignored"}]
        """.trimIndent()

        val projects = json.decodeFromString(ListSerializer(GitLabProject.serializer()), body)

        assertEquals(
            listOf(GitLabProject(335, "Raketa", "raketa/raketa", "https://gitlab.raketa.in/raketa/raketa")),
            projects,
        )
    }
}
