package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.CovdClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lifecycle of the real `covd` daemon as [CovdClient] drives it: the handshake, the ping that
 * proves the published instance is still the one answering, reuse of a running process, the
 * singleton lock shared with a second launch, restart after a crash or an idle exit, and the
 * failure paths that must report `Unavailable` instead of looping.
 */
class CovdLifecycleTest : CovdTestBase() {

    @Test(timeout = 60_000)
    fun `handshake points at a live daemon that answers ping with the same instance id`() {
        readyPipeline()

        val response = CovdClient.ensure(request())

        assertEquals("ready", response.state)
        val handshake = requireNotNull(CovdClient.currentHandshakeForTest()) { "no handshake recorded" }
        val process = requireNotNull(CovdClient.currentProcessForTest()) { "no process recorded" }
        assertTrue("daemon process is not alive", process.isAlive)
        assertEquals(1, CovdClient.startCount.get())

        val ping = ping(handshake)
        assertEquals(1, jsonInt(ping, "protocol"))
        assertEquals(handshake.instanceId, jsonString(ping, "instanceId"))
        assertFalse("ping must publish the build id", jsonString(ping, "build").isNullOrEmpty())
    }

    @Test(timeout = 60_000)
    fun `repeated ensures reuse the running daemon`() {
        readyPipeline()
        val first = CovdClient.ensure(request())
        val process = requireNotNull(CovdClient.currentProcessForTest()) { "no process recorded" }
        val instanceId = requireNotNull(CovdClient.currentHandshakeForTest()).instanceId

        val second = CovdClient.ensure(request())

        assertEquals("ready", first.state)
        assertEquals("ready", second.state)
        assertEquals("the client started a second process", 1, CovdClient.startCount.get())
        assertSame("the client replaced its process handle", process, CovdClient.currentProcessForTest())
        assertEquals(instanceId, CovdClient.currentHandshakeForTest()?.instanceId)
        assertTrue("daemon process died", process.isAlive)
    }

    /**
     * A second launch on the same cache directory loses the singleton lock, prints the handshake
     * of the live daemon and exits `0`. Its token and port must belong to the running daemon, and
     * the second client must be able to `ensure` against it.
     */
    @Test(timeout = 60_000)
    fun `a second launch on the same cache dir reports the live daemon and exits`() {
        readyPipeline()
        CovdClient.ensure(request())
        val live = requireNotNull(CovdClient.currentHandshakeForTest()) { "no handshake recorded" }

        val (second, line) = spawnDaemon(cacheDir)

        assertTrue("second launch did not exit", second.waitFor(20, TimeUnit.SECONDS))
        assertEquals("second launch must exit successfully", 0, second.exitValue())
        val reported = requireNotNull(CovdClient.parseHandshakeForTest(requireNotNull(line) { "second launch printed no handshake" }))
        assertEquals(live.port, reported.port)
        assertEquals(live.token, reported.token)
        assertEquals(live.instanceId, reported.instanceId)

        // The raw launch is not a client start: the daemon stays the only one.
        assertEquals(1, CovdClient.startCount.get())
        val response = CovdClient.parseEnsureForTest(
            postEnsure(reported, CovdClient.encodeRequestForTest(request())).second,
        )
        assertEquals("ready", response.state)
        assertEquals(live.instanceId, jsonString(ping(live), "instanceId"))
    }

    @Test(timeout = 60_000)
    fun `the next ensure restarts a daemon killed between calls`() {
        readyPipeline()
        CovdClient.ensure(request())
        val killed = requireNotNull(CovdClient.currentProcessForTest()) { "no process recorded" }
        val killedInstance = requireNotNull(CovdClient.currentHandshakeForTest()).instanceId

        killed.destroyForcibly()
        assertTrue("daemon did not die", killed.waitFor(10, TimeUnit.SECONDS))

        val response = CovdClient.ensure(request())

        assertEquals("ready", response.state)
        assertEquals("the client must restart exactly once", 2, CovdClient.startCount.get())
        val restarted = requireNotNull(CovdClient.currentProcessForTest())
        assertNotSame("the client reused the killed process handle", killed, restarted)
        assertTrue("the restarted daemon is not alive", restarted.isAlive)
        val restartedHandshake = requireNotNull(CovdClient.currentHandshakeForTest())
        assertNotEquals("the restarted daemon must have a new instance id", killedInstance, restartedHandshake.instanceId)
        assertEquals(restartedHandshake.instanceId, jsonString(ping(restartedHandshake), "instanceId"))
    }

    /**
     * `--idle-timeout` takes a Go duration. Once the daemon has exited, the cached handshake no
     * longer answers, so the next `ensure` starts one replacement and succeeds.
     */
    @Test(timeout = 60_000)
    fun `an idle timeout exit costs exactly one restart`() {
        CovdClient.extraDaemonArgs = listOf("--idle-timeout=1s")
        readyPipeline()
        assertEquals("ready", CovdClient.ensure(request()).state)
        val idle = requireNotNull(CovdClient.currentProcessForTest()) { "no process recorded" }

        assertTrue("daemon ignored --idle-timeout=1s", idle.waitFor(20, TimeUnit.SECONDS))
        assertEquals("idle exit must be clean", 0, idle.exitValue())

        val response = CovdClient.ensure(request())

        assertEquals("ready", response.state)
        assertEquals("the client must restart exactly once", 2, CovdClient.startCount.get())
        assertEquals(response.commit, head)
    }

    /**
     * When the replacement cannot start either, `ensure` must surface [CovdClient.Unavailable]
     * after one attempt instead of retrying forever.
     */
    @Test(timeout = 60_000)
    fun `a daemon that cannot be restarted fails fast without looping`() {
        readyPipeline()
        CovdClient.ensure(request())
        val killed = requireNotNull(CovdClient.currentProcessForTest()) { "no process recorded" }
        killed.destroyForcibly()
        assertTrue("daemon did not die", killed.waitFor(10, TimeUnit.SECONDS))

        // covd rejects the unknown flag and exits before printing a handshake line.
        CovdClient.extraDaemonArgs = listOf("--no-such-flag")

        try {
            CovdClient.ensure(request())
            fail("ensure must report the unusable daemon")
        } catch (expected: CovdClient.Unavailable) {
            assertTrue(
                "unexpected message: ${expected.message}",
                expected.message.orEmpty().contains("handshake"),
            )
        }
        assertEquals("ensure must try the daemon exactly twice, not loop", 2, CovdClient.startCount.get())
    }

    @Test(timeout = 60_000)
    fun `a handshake timeout is reported as Unavailable`() {
        CovdClient.handshakeTimeoutMs = 1

        try {
            CovdClient.ensure(request())
            fail("ensure must report the missing handshake")
        } catch (expected: CovdClient.Unavailable) {
            assertTrue(
                "unexpected message: ${expected.message}",
                expected.message.orEmpty().contains("handshake"),
            )
        }
        assertEquals("ensure must not retry the start", 1, CovdClient.startCount.get())
    }

    @Test(timeout = 60_000)
    fun `a missing binary is reported as Unavailable and isAvailable is false`() {
        assertTrue("the harness must point the client at the real binary", CovdClient.isAvailable())

        CovdClient.binaryOverride = File(cacheDir, "no-such-covd")

        assertFalse(CovdClient.isAvailable())
        try {
            CovdClient.ensure(request())
            fail("ensure must report the missing binary")
        } catch (expected: CovdClient.Unavailable) {
            assertTrue(
                "unexpected message: ${expected.message}",
                expected.message.orEmpty().contains("no covd binary"),
            )
        }
        assertEquals("no process can start without a binary", 0, CovdClient.startCount.get())
    }

    /**
     * A cold client hit by several threads at once must end up on exactly one daemon process:
     * starts are serialized and the threads that waited reuse the connection of the first one.
     */
    @Test(timeout = 120_000)
    fun `concurrent cold ensures all succeed on one daemon`() {
        readyPipeline()
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit<CovdClient.EnsureResponse> {
                    barrier.await(20, TimeUnit.SECONDS)
                    CovdClient.ensure(request())
                }
            }
            val responses = futures.map { it.get(60, TimeUnit.SECONDS) }

            responses.forEachIndexed { index, response ->
                assertEquals("thread $index: ${response.code} ${response.message}", "ready", response.state)
            }
            assertEquals("each cold caller spawned its own daemon", 1, CovdClient.startCount.get())

            waitFor("one live daemon", 20_000) { liveDaemons().size == 1 }
            val handshake = requireNotNull(CovdClient.currentHandshakeForTest()) { "no handshake recorded" }
            assertEquals(handshake.instanceId, jsonString(ping(handshake), "instanceId"))
        } finally {
            pool.shutdownNow()
        }
    }

    /** Callers that all see a dead daemon must share a single restart, not one process each. */
    @Test(timeout = 120_000)
    fun `concurrent ensures after a kill share one restart`() {
        readyPipeline()
        CovdClient.ensure(request())
        val killed = requireNotNull(CovdClient.currentProcessForTest()) { "no process recorded" }
        killed.destroyForcibly()
        assertTrue("daemon did not die", killed.waitFor(10, TimeUnit.SECONDS))

        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit<CovdClient.EnsureResponse> {
                    barrier.await(20, TimeUnit.SECONDS)
                    CovdClient.ensure(request())
                }
            }
            futures.map { it.get(60, TimeUnit.SECONDS) }.forEachIndexed { index, response ->
                assertEquals("thread $index: ${response.code} ${response.message}", "ready", response.state)
            }
            assertEquals("one initial start plus one shared restart", 2, CovdClient.startCount.get())
            waitFor("one live daemon", 20_000) { liveDaemons().size == 1 }
        } finally {
            pool.shutdownNow()
        }
    }

    // --- helpers ------------------------------------------------------------------

    private fun spawnDaemon(dir: File): Pair<Process, String?> {
        val binary = requireNotNull(findBinary()) { "no covd binary" }
        val process = ProcessBuilder(binary.absolutePath, "--cache-dir", dir.absolutePath)
            .redirectErrorStream(false)
            .start()
        val line = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
            .get(20, TimeUnit.SECONDS)
        return process to line
    }

    private fun ping(handshake: CovdClient.Handshake): String {
        val connection = URL("http://127.0.0.1:${handshake.port}/v1/ping").openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        try {
            assertEquals(200, connection.responseCode)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun postEnsure(handshake: CovdClient.Handshake, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${handshake.port}/v1/ensure").openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 30_000
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${handshake.token}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonString(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun jsonInt(json: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()

    /** Live processes started with this test's cache directory. */
    private fun liveDaemons(): List<ProcessHandle> =
        ProcessHandle.allProcesses()
            .filter { it.isAlive && it.info().commandLine().orElse("").contains(cacheDir.absolutePath) }
            .toList()

    private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("timed out waiting for $what")
    }
}
