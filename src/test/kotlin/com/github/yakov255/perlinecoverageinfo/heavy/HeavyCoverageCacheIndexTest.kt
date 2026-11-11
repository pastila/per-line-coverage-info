package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.Cov4TestWriter
import com.github.yakov255.perlinecoverageinfo.CoverageApiSettings
import com.github.yakov255.perlinecoverageinfo.CoverageCacheService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/**
 * Cache-index contract inside a real IDE (review items M6/L6).
 *
 * The daemon owns `cache-index.json` and may add fields to it at any time. A new field must not
 * make the whole cache invisible to offline-first loading and the Artifacts tab, and a commit
 * cached for another component must not be picked for this one.
 */
class HeavyCoverageCacheIndexTest : BasePlatformTestCase() {

    private lateinit var cacheDir: File
    private var savedSettings: CoverageApiSettings.State? = null

    override fun setUp() {
        super.setUp()
        cacheDir = Files.createTempDirectory("heavy-cache-index-").toFile()
        CoverageCacheService.cacheRootOverride = cacheDir
        val settings = CoverageApiSettings.getInstance()
        savedSettings = settings.state.copy()
        settings.gitlabProjectId = PROJECT_ID
        settings.enabled = true
    }

    override fun tearDown() {
        try {
            savedSettings?.let { CoverageApiSettings.getInstance().loadState(it) }
            CoverageCacheService.cacheRootOverride = null
            cacheDir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testAnUnknownIndexFieldDoesNotHideTheCache() {
        writeArtifact("abc123", "api")
        writeIndex(
            """
            {"schemaVersion":3,"entries":[
              {"commitHash":"abc123","pipelineId":7,"timestampMs":1000,"totalFiles":1,
               "totalLines":1,"coveredLines":1,"component":"api","branch":"main",
               "futureField":{"nested":true},"anotherOne":[1,2,3]}
            ]}
            """.trimIndent(),
        )

        val cache = service()

        assertEquals("the Artifacts tab must still see the entry", 1, cache.listArtifacts().size)
        assertEquals(setOf("abc123"), cache.cachedCommitHashes())
        assertEquals("abc123", cache.findCachedCommit(listOf("abc123"), "api"))
    }

    fun testACachedCommitOfAnotherComponentIsNotPicked() {
        writeArtifact("aaa111", "api")
        writeArtifact("bbb222", "hotels")
        writeIndex(
            """
            {"entries":[
              {"commitHash":"aaa111","pipelineId":1,"timestampMs":1000,"component":"api"},
              {"commitHash":"bbb222","pipelineId":2,"timestampMs":2000,"component":"hotels"}
            ]}
            """.trimIndent(),
        )

        val cache = service()

        assertEquals("bbb222", cache.findCachedCommit(listOf("aaa111", "bbb222"), "hotels"))
        assertEquals("aaa111", cache.findCachedCommit(listOf("aaa111", "bbb222"), "api"))
        assertNull(
            "a commit cached for another component has no coverage for this one",
            cache.findCachedCommit(listOf("bbb222"), "api"),
        )
    }

    // --- helpers ---------------------------------------------------------------------

    private fun service(): CoverageCacheService = CoverageCacheService.getInstance(project)

    private fun projectDir(): File = File(cacheDir, PROJECT_ID.toString()).apply { mkdirs() }

    private fun writeArtifact(commit: String, component: String) {
        Cov4TestWriter.write(
            mapOf("src/Foo.php" to mapOf(1 to listOf("Test::a"))),
            File(projectDir(), "$commit-$component.cov4"),
        )
    }

    private fun writeIndex(json: String) {
        File(projectDir(), "cache-index.json").writeText(json)
    }

    private companion object {
        const val PROJECT_ID = 4242L
    }
}
