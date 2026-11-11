package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import com.github.yakov255.perlinecoverageinfo.realproject.inventory.PlanEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * R4 — `api/hotels` opened as its own IDE project (docs/real-project-testing.md).
 *
 * The hotels component publishes a single ≈0.7 MB coverage job
 * (`test:behat:coverage:hotels-8.5`), its coverage keys are already git-root-relative
 * (`api/hotels/src/...`) and the plugin sends `pathMode` empty for it. So the scenario checks the
 * second half of the component story: the same worktree opened at `api/hotels` must ask covd for
 * `component=hotels`, resolve that small artifact, classify the requested files and attach the
 * master baseline of the hotels component. Then the worktree root is opened on the **same commit**
 * (`component=raketa`) and the two cache keys are compared.
 *
 * Nothing here touches the user's checkout: branches move only inside the shared worktree with
 * `git checkout --detach` (through [ScenarioBase.switchTo]).
 */
class HotelsProjectScenarios : ScenarioBase() {

    private val tourCount: Int =
        (System.getProperty("realProject.tour")
            ?: System.getenv("REAL_PROJECT_TOUR")
            ?: "8").toIntOrNull() ?: 8

    /** Files the bridge classifies: enough to hit more than one directory. */
    private val uiFileCount = 10

    @Test
    fun r4HotelsOpenedAsItsOwnProject() {
        scenario("R4", "r4-hotels")
        val baseline = plan.baseline
        assumeTrue("plan.json has no baseline", baseline != null)
        baseline!!

        val candidates = plan.hotelsBranches.filter { entry ->
            entry.hasHotels && entry.localSha && entry.hotelsBytes > 0 && entry.shards > 0
        }
        assumeTrue("plan.json has no hotels pipeline with a local commit", candidates.isNotEmpty())

        val branch = pickLiveHotelsBranch(candidates)
        event(
            "branch",
            "${branch.ref} ${branch.sha.take(8)} hotels=${branch.hotelsBytes}B",
            eventData(
                "pipelineId" to branch.pipelineId,
                "shards" to branch.shards,
                "hotelsBytes" to branch.hotelsBytes,
                "sizeClass" to branch.sizeClass,
                "tour" to tourCount,
            ),
        )

        switchTo(branch.sha)
        val hotelsDir = File(worktree, "api/hotels")
        assertTrue("api/hotels is missing from the worktree at ${branch.sha.take(8)}", hotelsDir.isDirectory)

        val uiFiles = hotelsUiTestFiles(hotelsDir)
        assertTrue("no PHP file found under ${hotelsDir.absolutePath}/src", uiFiles.isNotEmpty())
        event("uiTestFiles", "${uiFiles.size} git-root-relative paths", eventData("files" to uiFiles.joinToString(",")))

        // ---- hotels as its own project ---------------------------------------------------
        val hotelsStart = System.currentTimeMillis()
        val hotelsIde = startIde("hotels", projectDir = hotelsDir, uiTestFiles = uiFiles)
        val hotels = awaitLoaded(hotelsIde, branch.sha, timeoutMs = 900_000)
        val hotelsLoadedAt = System.currentTimeMillis()
        event(
            "hotels.loaded",
            "state=${hotels.state} commit=${hotels.commit?.take(8)}",
            eventData(
                "wallMs" to (hotelsLoadedAt - hotelsStart),
                "files" to hotels.files,
                "artifacts" to hotels.artifacts,
                "coverageGeneration" to hotels.coverageGeneration,
            ),
        )

        assertEquals("hotels IDE state", "LOADED", hotels.state)
        assertEquals("hotels IDE loaded commit", branch.sha, hotels.commit)
        assertNotEquals("hotels IDE shows a stale artifact, not a download", true, hotels.stale)
        assertNull("hotels IDE reported lastError=${hotels.lastError}", hotels.lastError)

        val answers = S2Evidence.answers(cacheRoot)
        // The plugin sends the ComponentConfig serviceDir, so accept `hotels` and `api/hotels`
        // here and pin the real value below.
        val hotelsAnswers = answers.filter { it.component?.substringAfterLast('/') == "hotels" }
        assertTrue(
            "no covd answer for the hotels component; answers=${answers.map { "${it.component}/${it.state}" }}",
            hotelsAnswers.isNotEmpty(),
        )
        val readyHotel = hotelsAnswers.filter { it.state == "ready" }.maxByOrNull { it.atMs }
        assertNotNull("covd never answered ready for hotels: ${hotelsAnswers.map { "${it.state}/${it.code}" }}", readyHotel)
        readyHotel!!
        // FINDING (recorded, not hidden): docs/real-project-testing.md says `component=hotels`,
        // but the plugin sends `ComponentConfig`'s serviceDir, i.e. `api/hotels`; covd then names
        // the cache file `<sha>-api_hotels.cov4`. The functional contract still holds because the
        // job prefixes carry the `hotels` token, and the assertion below pins the real value.
        if (readyHotel.component != "hotels") {
            finding(
                "covd request.component is '${readyHotel.component}', the documented component is 'hotels'",
                eventData(
                    "actual" to readyHotel.component,
                    "documented" to "hotels",
                    "jobPrefixes" to readyHotel.jobPrefixes.joinToString(","),
                    "componentConfigServiceDir" to "api/hotels",
                ),
            )
        }
        assertEquals(
            "covd request.component (plugin sends the serviceDir; docs name the token 'hotels')",
            "api/hotels",
            readyHotel.component,
        )
        assertEquals("covd trace request.component", readyHotel.component, readyHotel.traceComponent ?: readyHotel.component)
        assertEquals("covd answered for the branch commit", branch.sha, readyHotel.commit)
        assertTrue(
            "covd did not get the dedicated coverage job prefix: ${readyHotel.jobPrefixes}",
            readyHotel.jobPrefixes.any { it.contains("test:behat:coverage:hotels") },
        )
        assertTrue(
            "hotels coverage keys are already git-root-relative, pathMode must stay empty, got '${readyHotel.pathMode}'",
            readyHotel.pathMode.isNullOrEmpty(),
        )
        event(
            "covd.answer",
            "hotels state=${readyHotel.state}",
            eventData(
                "commit" to readyHotel.commit,
                "cached" to readyHotel.cached,
                "joinedFlight" to readyHotel.joinedFlight,
                "pipelineId" to readyHotel.pipelineId,
                "cov4Path" to readyHotel.cov4Path,
                "files" to readyHotel.files,
                "lines" to readyHotel.lines,
                "covered" to readyHotel.covered,
                "baselineCommit" to readyHotel.baselineCommit,
                "baselineState" to readyHotel.baselineState,
            ),
        )

        // ---- proxy traffic: one small hotels artifact, not a raketa-sized download -------
        val hotelRows = S2Evidence.journal(ctx.dir).filter { it.t in hotelsStart..hotelsLoadedAt }
        val hotelArtifacts = hotelRows.filter { it.artifactJobId != null }
        val artifactBytes = hotelArtifacts.sumOf { it.bytesDown }
        event(
            "proxy.hotels",
            "artifacts=${hotelArtifacts.size} bytes=$artifactBytes",
            eventData(
                "requests" to hotelRows.size,
                "bytesDown" to hotelRows.sumOf { it.bytesDown },
                "statuses" to hotelRows.groupingBy { it.status }.eachCount(),
                "jobIds" to hotelArtifacts.mapNotNull { it.artifactJobId }.distinct().joinToString(","),
            ),
        )
        assertTrue("the hotels load must download its artifact through the proxy", hotelArtifacts.isNotEmpty())
        assertTrue(
            "hotels artifacts must stay small (<5 MB), got $artifactBytes bytes in " +
                "${hotelArtifacts.map { it.path }}",
            artifactBytes < 5L * 1024 * 1024,
        )
        assertTrue(
            "hotels artifacts must be complete 2xx answers: ${hotelArtifacts.map { "${it.status}/${it.fault}" }}",
            hotelArtifacts.all { it.status in 200..299 && it.fault == null },
        )
        assertTrue(
            "unexpected hotels error answers: ${hotelRows.filter { it.status >= 400 }.map { "${it.status} ${it.path}" }}",
            hotelRows.none { it.status >= 400 },
        )

        // ---- gutter classification comes from real hotel keys ---------------------------
        val classifiedFiles = hotels.lines.filterValues { it.isNotEmpty() }
        val categories = hotels.lines.values.flatMap { it.values }.groupingBy { it }.eachCount()
        event(
            "bridge.lines",
            "${classifiedFiles.size}/${uiFiles.size} files",
            eventData(
                "categories" to categories,
                "classified" to classifiedFiles.keys.joinToString(","),
                "empty" to hotels.lines.filterValues { it.isEmpty() }.keys.joinToString(","),
            ),
        )
        if (classifiedFiles.isEmpty()) {
            fail(
                "bridge.lines has no classification for any requested file. requested=$uiFiles " +
                    "artifacts=${hotels.artifacts} cov4=${readyHotel.cov4Path} " +
                    "cov4Keys=${cov4KeySample(readyHotel.cov4Path).joinToString(" | ")}",
            )
        }
        val coveredLines = categories["COVERED"] ?: 0
        if (coveredLines == 0) {
            fail(
                "bridge.lines has no COVERED line, categories=$categories. " +
                    "featureOnly=${categories["FEATURE_ONLY"] ?: 0} uncovered=${categories["UNCOVERED"] ?: 0} " +
                    "cov4=${readyHotel.cov4Path} cov4Keys=${cov4KeySample(readyHotel.cov4Path).joinToString(" | ")}",
            )
        }

        // ---- dual coverage: the hotels artifact of the coverage branch -------------------
        val baselineCommit = readyHotel.baselineCommit
        assertNotNull(
            "hotels load must run in dual mode with the coverage branch as baseline; " +
                "covd said baselineState=${readyHotel.baselineState}",
            baselineCommit,
        )
        assertEquals("bridge baselineCommit must match covd", baselineCommit, hotels.baselineCommit)
        val baselinePath = readyHotel.baselineCov4Path
        assertNotNull(
            "covd returned baselineCommit=$baselineCommit without a baseline file " +
                "(baselineState=${readyHotel.baselineState})",
            baselinePath,
        )
        val baselineFile = File(baselinePath!!)
        assertTrue("baseline cov4 does not exist: $baselinePath", baselineFile.isFile)
        val componentSuffix = File(readyHotel.cov4Path!!).name.substringAfter('-')
        assertTrue(
            "baseline artifact must carry the same component suffix '$componentSuffix': $baselinePath",
            baselinePath.endsWith("-$componentSuffix"),
        )
        event(
            "baseline",
            "commit=${baselineCommit?.take(8)} state=${readyHotel.baselineState}",
            eventData(
                "path" to baselinePath,
                "bytes" to baselineFile.length(),
                "isCoverageBranchHead" to (baselineCommit == baseline.sha),
            ),
        )

        // ---- cache keys: hotels now, raketa must not exist yet --------------------------
        val projectCache = File(cacheRoot, S2Evidence.PROJECT_ID)
        val hotelsComponent = readyHotel.component!!
        val hotelsCov4 = File(projectCache, "${branch.sha}-${hotelsComponent.replace('/', '_')}.cov4")
        assertEquals(
            "the cache file must be the one covd returned",
            File(readyHotel.cov4Path!!).name,
            hotelsCov4.name,
        )
        assertTrue("hotels cache file missing: ${hotelsCov4.absolutePath}", hotelsCov4.isFile)
        val afterHotels = S2Evidence.cacheIndex(cacheRoot)
        assertTrue(
            "cache-index has no (${branch.sha.take(8)}, $hotelsComponent) entry: ${afterHotels.map { it.key }}",
            afterHotels.any { it.commit == branch.sha && it.component == hotelsComponent },
        )
        assertFalse(
            "the hotels load must not create a raketa entry: ${afterHotels.map { it.key }}",
            afterHotels.any { it.commit == branch.sha && it.component == "raketa" },
        )

        // The hotels IDE is not needed any more and two IDEs on a 4 GB heap each do not fit
        // comfortably next to the other agents of the suite: the evidence is already collected.
        stopIde(hotelsIde)

        // ---- the same commit opened at the worktree root: component raketa --------------
        event("root.start", "same commit ${branch.sha.take(8)}, component must be raketa")
        val rootStart = System.currentTimeMillis()
        val rootIde = startIde("root")
        val root = awaitLoaded(rootIde, branch.sha, timeoutMs = 900_000)
        val rootLoadedAt = System.currentTimeMillis()
        assertNull("root IDE reported lastError=${root.lastError}", root.lastError)

        val rootAnswer = S2Evidence.answers(cacheRoot)
            .filter { it.component == "raketa" && it.state == "ready" && it.commit == branch.sha }
            .maxByOrNull { it.atMs }
        assertNotNull(
            "the root IDE did not ask covd for the raketa component at ${branch.sha.take(8)}; " +
                "answers=${S2Evidence.answers(cacheRoot).map { "${it.component}/${it.state}/${it.shortCommit}" }}",
            rootAnswer,
        )
        val rootRows = S2Evidence.journal(ctx.dir).filter { it.t in rootStart..rootLoadedAt }
        event(
            "root.loaded",
            "state=${root.state}",
            eventData(
                "wallMs" to (rootLoadedAt - rootStart),
                "component" to rootAnswer!!.component,
                "artifactBytes" to rootRows.filter { it.artifactJobId != null }.sumOf { it.bytesDown },
                "artifacts" to rootRows.count { it.artifactJobId != null },
                "baselineCommit" to root.baselineCommit,
            ),
        )

        val afterRoot = S2Evidence.cacheIndex(cacheRoot)
        val hotelsEntry = afterRoot.firstOrNull { it.commit == branch.sha && it.component == hotelsComponent }
        val raketaEntry = afterRoot.firstOrNull { it.commit == branch.sha && it.component == "raketa" }
        assertNotNull("cache-index lost the hotels entry after the raketa load", hotelsEntry)
        assertNotNull("cache-index has no raketa entry for the same commit", raketaEntry)
        assertNotEquals("the two components must not share one cache entry", hotelsEntry, raketaEntry)
        assertEquals(
            "cache-index keys must be unique: ${afterRoot.map { it.key }}",
            afterRoot.size,
            afterRoot.distinctBy { it.commit to it.component }.size,
        )
        val raketaCov4 = File(projectCache, "${branch.sha}-raketa.cov4")
        assertTrue("raketa cache file missing: ${raketaCov4.absolutePath}", raketaCov4.isFile)
        assertTrue("hotels cache file was replaced by the raketa load", hotelsCov4.isFile)
        assertNotEquals("the cache file names must differ by component", hotelsCov4.name, raketaCov4.name)
        assertTrue(
            "a corrupt cache index was left behind: ${S2Evidence.corruptIndexFiles(cacheRoot)}",
            S2Evidence.corruptIndexFiles(cacheRoot).isEmpty(),
        )
        event(
            "cache.keys",
            "same commit, two components",
            eventData(
                "hotels" to hotelsCov4.name,
                "raketa" to raketaCov4.name,
                "indexKeys" to afterRoot.joinToString(",") { it.key },
                "hotelsBytes" to hotelsCov4.length(),
                "raketaBytes" to raketaCov4.length(),
            ),
        )

        val pluginErrors = (
            S2Evidence.pluginErrorLines(hotelsIde.ideaLog) + S2Evidence.pluginErrorLines(rootIde.ideaLog)
            ).distinct()
        event("log", "plugin SEVERE/ERROR lines=${pluginErrors.size}", eventData("lines" to pluginErrors.joinToString(" ¶ ").take(3_000)))
        assertTrue("plugin logged SEVERE/ERROR lines: $pluginErrors", pluginErrors.isEmpty())

        event(
            "metrics",
            "R4",
            eventData(
                "hotelsToLoadedMs" to (hotelsLoadedAt - hotelsStart),
                "rootToLoadedMs" to (rootLoadedAt - rootStart),
                "hotelsArtifacts" to hotelArtifacts.size,
                "hotelsArtifactBytes" to artifactBytes,
                "rootArtifactBytes" to rootRows.filter { it.artifactJobId != null }.sumOf { it.bytesDown },
                "hotelsAnswers" to hotelsAnswers.size,
                "ensureAnswers" to S2Evidence.answers(cacheRoot).size,
                "peakCovdRssMb" to S2Evidence.format(S2Evidence.peakRss(ctx.samplesFile, "covd", hotelsStart, rootLoadedAt)),
                "peakHotelsIdeRssMb" to S2Evidence.format(S2Evidence.peakRss(ctx.samplesFile, "hotels", hotelsStart, hotelsLoadedAt)),
                "peakRootIdeRssMb" to S2Evidence.format(S2Evidence.peakRss(ctx.samplesFile, "root", rootStart, rootLoadedAt)),
                "categories" to categories,
            ),
        )

        val report = finish()
        assertTrue("no report written", report.isFile)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * First hotels branch whose pipeline still publishes **both** artifacts: hotels (the
     * scenario's subject) and raketa (needed for the same-commit root IDE). A pipeline can carry
     * only hotels jobs — a hotels-only branch has no raketa coverage job, and covd then answers
     * `NO_COMPONENT_JOBS`, so such a branch cannot be used for the cache-key comparison.
     */
    private fun pickLiveHotelsBranch(candidates: List<PlanEntry>): PlanEntry {
        var probed = 0
        for (entry in candidates) {
            val live = try {
                coverageJobs(entry.pipelineId)
            } catch (error: Throwable) {
                // An unreachable anonymous API must not fail the scenario on its own: the load
                // itself shows whether the artifacts are there.
                event("probe", "pipeline ${entry.pipelineId} probe failed: ${error.message}", eventData("ref" to entry.ref))
                return entry
            }
            probed++
            event(
                "probe",
                "pipeline ${entry.pipelineId} hotels=${live.hotels} raketa=${live.raketa}",
                eventData(
                    "ref" to entry.ref,
                    "sha" to entry.sha,
                    "hotelsBytes" to entry.hotelsBytes,
                    "hotelsJobs" to live.hotelsJobs.joinToString(","),
                    "raketaJobs" to live.raketaJobs.joinToString(","),
                ),
            )
            if (live.hotels && live.raketa) return entry
        }
        assumeTrue(
            "none of the ${candidates.size} hotels branches publishes both hotels and raketa artifacts (probed $probed)",
            false,
        )
        error("unreachable")
    }

    /** Anonymous job listing: which coverage jobs of a pipeline still have an artifact. */
    private data class CoverageJobs(
        val hotels: Boolean,
        val raketa: Boolean,
        val hotelsJobs: List<String>,
        val raketaJobs: List<String>,
    )

    private fun coverageJobs(pipelineId: Long): CoverageJobs {
        val url = URL("https://gitlab.raketa.in/api/v4/projects/${S2Evidence.PROJECT_ID}/pipelines/$pipelineId/jobs?per_page=100")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val jobs = S2Evidence.json.parseToJsonElement(body).jsonArray
            val withArtifact = jobs.mapNotNull { element ->
                val job = element.jsonObject
                val name = job["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val status = job["status"]?.jsonPrimitive?.contentOrNull ?: ""
                val size = (job["artifacts_file"] as? JsonObject)?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
                if (status == "success" && size > 0L) name else null
            }
            val hotelsJobs = withArtifact.filter { it.contains("coverage:hotels") }
            val raketaJobs = withArtifact.filter { it.contains("coverage:raketa") }
            return CoverageJobs(
                hotels = hotelsJobs.isNotEmpty(),
                raketa = raketaJobs.isNotEmpty(),
                hotelsJobs = hotelsJobs,
                raketaJobs = raketaJobs,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The files the bridge must classify: git-root-relative, the form `CoverageUiTestBridge` looks
     * up in the coverage map. `ApiContainerProxy.php` is first because the spike recorded covered
     * lines 21..30 there; the rest is every n-th PHP file of `api/hotels/src`, so the sample covers
     * several directories.
     */
    private fun hotelsUiTestFiles(hotelsDir: File): List<String> {
        val src = File(hotelsDir, "src")
        val all = src.walkTopDown()
            .filter { it.isFile && it.extension == "php" }
            .map { it.relativeTo(worktree).path.replace(File.separatorChar, '/') }
            .sorted()
            .toList()
        val spike = "api/hotels/src/ApiContainerProxy.php"
        val preferred = listOf(spike).filter { it in all }
        val rest = all.filterNot { it in preferred }
        val slots = (uiFileCount - preferred.size).coerceAtLeast(1)
        val step = if (rest.size <= slots) 1 else rest.size / slots
        val spread = rest.filterIndexed { index, _ -> index % step == 0 }
        return (preferred + spread).distinct().take(uiFileCount)
    }

    /** A recorded divergence from the documented behaviour: never silently accepted. */
    private fun finding(message: String, data: kotlinx.serialization.json.JsonObject) {
        event("finding", message, data)
    }

    /** First keys of the `.cov4` index for a failure message: what the plugin actually sees. */
    private fun cov4KeySample(path: String?): List<String> {
        if (path == null) return listOf("<no cov4 path>")
        val file = File(path)
        if (!file.isFile) return listOf("<missing $path>")
        val paths = runCatching { S2Evidence.cov4Paths(file) }.getOrElse { error ->
            return listOf("<cannot read ${file.name}: ${error.message}>")
        }
        val hotels = paths.count { it.startsWith("api/hotels/") }
        return listOf("paths=${paths.size}", "hotels=$hotels") + paths.take(12)
    }
}
