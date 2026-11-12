package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.Cov4Reader
import com.github.yakov255.perlinecoverageinfo.CoveragePathNormalizer
import com.github.yakov255.perlinecoverageinfo.CovdClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * `POST /v1/import` and the artifact catalogue around it: a local COVT file lands in the
 * cache under the SHA-256 of its bytes, the same bytes reuse one entry, broken input is an
 * error (not a daemon crash) and `GET/DELETE /v1/artifacts` report and remove what the
 * import wrote. The last test pins `pathMode=raketa` to the plugin's own
 * [CoveragePathNormalizer], so the daemon and the reader cannot drift apart silently.
 */
class CovdImportArtifactsTest : CovdTestBase() {

    private lateinit var workDir: File

    @Before
    fun setUpWorkDir() {
        workDir = Files.createTempDirectory("covd-import-").toFile()
    }

    @After
    fun tearDownWorkDir() {
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    @Test
    fun `import of a plain covt writes a readable cov4 into the cache`() {
        val file = covt("shard1.covt")

        val response = import(file)

        assertEquals(response.toString(), "ready", response.state)
        assertEquals(sha256(file.readBytes()), response.commit)
        val cov4 = File(response.cov4Path!!)
        assertTrue("cov4 outside the cache dir: $cov4", cov4.path.startsWith(cacheDir.path))
        assertTrue("cov4 missing: $cov4", cov4.isFile)
        assertEquals(3, response.stats?.files)
        assertEquals(7, response.stats?.lines)
        assertEquals(3, response.stats?.covered)

        Cov4Reader(cov4).use { reader ->
            assertTrue(reader.hasData())
            assertEquals(
                setOf("core/web/src/Foo.php", "core/web/src/Sub/Baz.php", "core/web/src/Empty.php"),
                reader.allFilePaths,
            )
            assertEquals(
                mapOf(
                    10 to listOf("core/web/app/raketa/E2E/A.feature:1", "core/web/src/Foo.php:12"),
                    11 to listOf("core/web/app/raketa/E2E/B.feature:2"),
                    12 to emptyList<String>(),
                ),
                reader.getCoverage("core/web/src/Foo.php"),
            )
        }
    }

    @Test
    fun `import of a gzipped covt uses the file bytes as the commit key`() {
        val plain = covt("shard1.covt")
        val gz = File(workDir, "shard1.covt.gz").apply { writeBytes(gzip(plain.readBytes())) }

        val response = import(gz)

        assertEquals("ready", response.state)
        assertEquals(sha256(gz.readBytes()), response.commit)
        assertNotEquals("the commit must hash the file covd read, not its payload", sha256(plain.readBytes()), response.commit)
        val cov4 = File(response.cov4Path!!)
        assertTrue(cov4.isFile)
        Cov4Reader(cov4).use { reader ->
            assertEquals(3, reader.allFilePaths.size)
            assertNotNull(reader.getCoverage("core/web/src/Foo.php"))
        }
    }

    @Test
    fun `importing the same bytes twice reuses one cache entry`() {
        val file = covt("shard1.covt")

        val first = import(file)
        val second = import(file)

        assertEquals("ready", first.state)
        assertEquals("ready", second.state)
        assertEquals(sha256(file.readBytes()), first.commit)
        assertEquals(first.commit, second.commit)
        assertEquals(first.cov4Path, second.cov4Path)
        val artifacts = assertNotNull(CovdClient.artifacts(PROJECT_ID))
        assertEquals(1, artifacts.size)
        assertEquals(first.commit, artifacts.single().commit)

        val cov4Names = File(cacheDir, PROJECT_ID).listFiles { f -> f.name.endsWith(".cov4") }!!.map { it.name }
        assertEquals(listOf("${first.commit}-$COMPONENT.cov4"), cov4Names)
    }

    @Test
    fun `different bytes produce a different commit and a second artifact`() {
        val first = import(covt("shard1.covt"))
        val second = import(covt("shard2.covt"))

        assertEquals("ready", first.state)
        assertEquals("ready", second.state)
        assertNotEquals(first.commit, second.commit)
        assertNotEquals(first.cov4Path, second.cov4Path)
        assertEquals(3, first.stats?.files)
        assertEquals(7, first.stats?.lines)
        assertEquals(2, second.stats?.files)
        assertEquals(4, second.stats?.lines)
        assertEquals(4, second.stats?.covered)

        val artifacts = assertNotNull(CovdClient.artifacts(PROJECT_ID))
        assertEquals(setOf(first.commit, second.commit), artifacts.map { it.commit }.toSet())
        assertEquals(2, artifacts.size)
    }

    @Test
    fun `unreadable imports report an error and the daemon keeps serving`() {
        val missing = File(workDir, "missing.covt")
        val missingResponse = import(missing)
        assertEquals("error", missingResponse.state)
        assertEquals("PROJECT_SETUP", missingResponse.code)
        assertTrue(
            "message should name the file: ${missingResponse.message}",
            missingResponse.message.orEmpty().contains("cannot read ${missing.absolutePath}"),
        )

        val emptyResponse = import(covt("empty.covt", ByteArray(0)))
        assertEquals("error", emptyResponse.state)
        assertEquals("NO_DATA", emptyResponse.code)
        assertTrue("message: ${emptyResponse.message}", emptyResponse.message.orEmpty().contains("empty input"))

        val garbageResponse = import(covt("garbage.covt", "definitely not a covt".toByteArray()))
        assertEquals("error", garbageResponse.state)
        assertEquals("NO_DATA", garbageResponse.code)
        assertTrue("message: ${garbageResponse.message}", garbageResponse.message.orEmpty().isNotBlank())

        // The failures above must not have taken the daemon down: the same process answers.
        val good = import(covt("shard1.covt"))
        assertEquals("ready", good.state)
        assertEquals(1, CovdClient.startCount.get())
        assertTrue(CovdClient.currentProcessForTest()!!.isAlive)
    }

    @Test
    fun `artifacts lists the imported entry and hides other projects`() {
        val response = import(covt("shard1.covt"))

        val entry = assertNotNull(CovdClient.artifacts(PROJECT_ID)).single()
        val stats = response.stats!!
        assertEquals(response.commit, entry.commit)
        assertEquals(COMPONENT, entry.component)
        assertEquals(response.cov4Path, entry.path)
        assertTrue("path must be inside the cache: ${entry.path}", entry.path!!.startsWith(cacheDir.path))
        assertEquals(0L, entry.pipelineId)
        assertEquals(stats.files, entry.files)
        assertEquals(stats.lines, entry.lines)
        assertEquals(stats.covered, entry.covered)
        assertTrue("timestamp must be set: ${entry.timestampMs}", entry.timestampMs > 0)

        val foreign = assertNotNull(CovdClient.artifacts("2"))
        assertEquals(emptyList<CovdClient.Artifact>(), foreign)
    }

    @Test
    fun `delete removes the cov4 and its entry and is idempotent`() {
        val response = import(covt("shard1.covt"))
        val commit = response.commit!!
        val cov4 = File(response.cov4Path!!)
        assertTrue(cov4.isFile)

        assertTrue(CovdClient.deleteArtifact(PROJECT_ID, commit, COMPONENT))
        assertFalse("cov4 still on disk: $cov4", cov4.exists())
        assertEquals(emptyList<CovdClient.Artifact>(), assertNotNull(CovdClient.artifacts(PROJECT_ID)))

        // covd's Delete ignores a missing .cov4 (fs.ErrNotExist is not an error), so a
        // second delete is also answered with 200/{ok:true} rather than 404.
        assertTrue(CovdClient.deleteArtifact(PROJECT_ID, commit, COMPONENT))
        assertEquals(emptyList<CovdClient.Artifact>(), assertNotNull(CovdClient.artifacts(PROJECT_ID)))
    }

    @Test
    fun `pathMode raketa matches the plugin path normalizer`() {
        val repoCopy = copyRepo()
        assertEquals(
            "the fixture must expose the E2E and FeatureTest suites",
            setOf("E2E", "FeatureTest"),
            CoveragePathNormalizer.raketaFeatureSuiteEntries(repoCopy),
        )
        val file = covt("shard1.covt")

        // The cache key is the file hash, so the same bytes under one project would overwrite
        // the raw artifact with the normalized one.
        val raw = import(file, pathMode = "raw", projectId = "raw-project")
        val raketa = import(file, pathMode = "raketa", projectId = "raketa-project", gitRoot = repoCopy)
        assertEquals("ready", raw.state)
        assertEquals("ready", raketa.state)
        assertEquals("same bytes must keep the same content key", raw.commit, raketa.commit)

        val rawCoverage = Cov4Reader(File(raw.cov4Path!!)).use { reader ->
            assertEquals(
                setOf("core/web/src/Foo.php", "core/web/src/Sub/Baz.php", "core/web/src/Empty.php"),
                reader.allFilePaths,
            )
            reader.allFilePaths.associateWith { reader.getCoverage(it)!! }
        }
        val expected = CoveragePathNormalizer.normalizeRaketaCoverage(rawCoverage, repoCopy)

        // Spot-check the transformation the daemon is expected to reproduce.
        assertEquals(setOf("src/Foo.php", "src/Sub/Baz.php", "src/Empty.php"), expected.keys)
        assertEquals(
            listOf("app/raketa/features/E2E/A.feature:1", "core/web/src/Foo.php:12"),
            expected.getValue("src/Foo.php")[10],
        )
        assertEquals(
            listOf("app/raketa/features/E2E/B.feature:2"),
            expected.getValue("src/Sub/Baz.php")[5],
        )

        Cov4Reader(File(raketa.cov4Path!!)).use { reader ->
            assertEquals("covd and CoveragePathNormalizer disagree on the file set", expected.keys, reader.allFilePaths)
            for ((path, lines) in expected) {
                assertEquals("covd and CoveragePathNormalizer disagree on '$path'", lines, reader.getCoverage(path))
            }
            // The raw paths must be gone from the normalized artifact.
            assertNull(reader.getCoverage("core/web/src/Foo.php"))
        }
    }

    @Test
    fun `an open reader keeps serving data after the same artifact is re-imported`() {
        val file = covt("shard1.covt")
        val first = import(file)
        val cov4 = File(first.cov4Path!!)

        Cov4Reader(cov4).use { reader ->
            assertTrue(reader.hasData())
            val before = reader.getCoverage("core/web/src/Foo.php")

            // covd rewrites the .cov4 (temp file + rename), so the reader sees a new file
            // identity and reloads its index instead of serving stale offsets.
            val again = import(file)
            assertEquals(first.commit, again.commit)
            assertEquals(first.cov4Path, again.cov4Path)

            assertTrue("reader lost its index after the cov4 was rewritten", reader.hasData())
            assertEquals(before, reader.getCoverage("core/web/src/Foo.php"))
        }
    }

    // --- helpers -------------------------------------------------------------------

    private fun import(
        file: File,
        pathMode: String = "raw",
        projectId: String = PROJECT_ID,
        gitRoot: File = repoDir,
    ): CovdClient.ImportResponse = CovdClient.import(
        CovdClient.ImportRequest(
            covtPath = file.absolutePath,
            projectId = projectId,
            component = COMPONENT,
            pathMode = pathMode,
            gitRoot = gitRoot.absolutePath,
        )
    )

    private fun covt(name: String, bytes: ByteArray = resource(name)): File =
        File(workDir, name).apply { writeBytes(bytes) }

    private fun copyRepo(): File {
        val source = File(CovdImportArtifactsTest::class.java.getResource("/covd/repo")!!.toURI())
        val target = File(workDir, "repo")
        source.copyRecursively(target)
        return target
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
