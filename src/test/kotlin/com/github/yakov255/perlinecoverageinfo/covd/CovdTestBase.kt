package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.CovdClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Base class for tests that run the **real** `covd` binary against a [FakeGitLab].
 *
 * Every test gets its own cache directory (so its own daemon: covd is a singleton per
 * `--cache-dir`), its own git repository and its own fake GitLab. The binary is looked up in
 * `-Dcovd.dist`/`COVD_DIST`, then in the sibling `coverage-assistant` checkout; tests are
 * skipped (not failed) when none is found.
 */
abstract class CovdTestBase {

    protected lateinit var cacheDir: File
    protected lateinit var repoDir: File
    protected lateinit var gitlab: FakeGitLab

    /** HEAD of [repoDir]. */
    protected lateinit var head: String

    @Before
    fun setUpHarness() {
        val binary = findBinary()
        assumeTrue("no covd binary: set COVD_DIST or -Dcovd.dist to coverage-assistant/dist/covd", binary != null)

        CovdClient.resetForTest()
        cacheDir = Files.createTempDirectory("covd-cache-").toFile()
        repoDir = Files.createTempDirectory("covd-repo-").toFile()
        head = initRepo(repoDir)

        gitlab = FakeGitLab()
        gitlab.defaultShard = resource("shard1.covt")

        CovdClient.binaryOverride = binary
        CovdClient.cacheRootOverride = cacheDir
    }

    @After
    fun tearDownHarness() {
        CovdClient.resetForTest()
        if (::gitlab.isInitialized) gitlab.close()
        if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
        if (::repoDir.isInitialized) repoDir.deleteRecursively()
    }

    // --- request builders ----------------------------------------------------------

    protected fun request(
        component: String = COMPONENT,
        jobPrefixes: List<String> = listOf(JOB_PREFIX),
        coverageBranch: String = "",
        token: String = "test-token",
        projectId: String = PROJECT_ID,
        pathMode: String = "raw",
        force: Boolean = false,
        baseUrl: String = gitlab.baseUrl,
        gitRoot: File = repoDir,
    ) = CovdClient.EnsureRequest(
        gitRoot = gitRoot.absolutePath,
        component = component,
        pathMode = pathMode,
        jobPrefixes = jobPrefixes,
        settings = CovdClient.Settings(
            baseUrl = baseUrl,
            projectId = projectId,
            coverageBranch = coverageBranch,
            token = token,
        ),
        force = force,
    )

    /** Registers a successful pipeline for [sha] on [ref] whose coverage job matches [JOB_PREFIX]. */
    protected fun readyPipeline(
        id: Int = 100,
        sha: String = head,
        ref: String = BRANCH,
        shard: ByteArray? = null,
        jobName: String = JOB_PREFIX,
    ) = gitlab.addReadyPipeline(id, sha, ref, jobName, shard = shard)

    // --- git ----------------------------------------------------------------------

    /** Makes a new commit in [repoDir] and returns it (HEAD moves). */
    protected fun commit(message: String): String {
        git(repoDir, "commit", "--allow-empty", "-q", "-m", message)
        head = git(repoDir, "rev-parse", "HEAD")
        return head
    }

    protected fun resource(name: String): ByteArray =
        CovdTestBase::class.java.getResourceAsStream("/covd/$name")?.use { it.readBytes() }
            ?: error("missing test resource /covd/$name")

    companion object {
        const val COMPONENT = "api"
        const val JOB_PREFIX = "test:behat:coverage:api"
        const val PROJECT_ID = "1"
        const val BRANCH = "feature"

        private val platformFile = when {
            System.getProperty("os.name").lowercase().contains("win") -> "covd-windows-amd64.exe"
            System.getProperty("os.name").lowercase().contains("mac") ->
                if (System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch").contains("arm64")) {
                    "covd-darwin-arm64"
                } else {
                    "covd-darwin-amd64"
                }
            System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch").contains("arm64") -> "covd-linux-arm64"
            else -> "covd-linux-amd64"
        }

        /** The covd binary for this platform, or null. */
        fun findBinary(): File? {
            val roots = listOfNotNull(
                System.getProperty("covd.dist"),
                System.getenv("COVD_DIST"),
                File(System.getProperty("user.dir"), "../raketa/yakov_scripts/coverage/coverage-assistant/dist/covd").path,
                File(System.getProperty("user.home"), "projects/raketa/yakov_scripts/coverage/coverage-assistant/dist/covd").path,
            )
            return roots.map { File(it, platformFile) }.firstOrNull { it.isFile && it.canExecute() }
        }

        /** Initialises a repository on branch [BRANCH] with one commit and returns its SHA. */
        fun initRepo(dir: File): String {
            git(dir, "init", "-q", "-b", BRANCH)
            git(dir, "config", "user.email", "test@example.com")
            git(dir, "config", "user.name", "Test")
            git(dir, "config", "commit.gpgsign", "false")
            git(dir, "commit", "--allow-empty", "-q", "-m", "initial")
            return git(dir, "rev-parse", "HEAD")
        }

        fun git(dir: File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "git ${args.joinToString(" ")} failed: $output"
            }
            return output
        }
    }
}
