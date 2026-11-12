package com.github.yakov255.perlinecoverageinfo.covd

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A GitLab stand-in that serves the four endpoints covd uses: branch pipelines, pipeline
 * jobs, the COVT artifact and the merge base. It mirrors the Go BDD fake in
 * `coverage-assistant/api/godog_steps_covd_test.go`.
 *
 * State is mutable from the test thread while the server answers on its own threads, so the
 * collections are copy-on-write and the counters atomic.
 */
class FakeGitLab : AutoCloseable {

    data class Pipeline(
        val id: Int,
        val sha: String,
        val ref: String,
        val status: String = "success",
        val source: String = "push",
    )

    data class Job(
        val id: Int,
        val pipeline: Int,
        val name: String,
        val status: String = "success",
        val hasArtifact: Boolean = true,
    )

    /** One request the fake saw: method, path with query, and the `PRIVATE-TOKEN`/`Authorization` value. */
    data class Seen(val method: String, val path: String, val query: String, val auth: String?)

    val pipelines = CopyOnWriteArrayList<Pipeline>()
    val jobs = CopyOnWriteArrayList<Job>()

    /** COVT bytes served for a job's artifact; [defaultShard] is used when the job has no entry. */
    val shards = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
    @Volatile var defaultShard: ByteArray = ByteArray(0)

    /** Answer returned by `/repository/merge_base`. */
    @Volatile var mergeBase: String = "d".repeat(40)

    /** Every API call answers 401, like a revoked token. */
    @Volatile var unauthorized = false

    /** Every API call answers 404, like a private project the token cannot see. */
    @Volatile var notFound = false

    /** When non-zero, every API call answers with this status and an empty JSON body. */
    @Volatile var forcedStatus = 0

    /** Sleep before every answer, to exercise client timeouts and request coalescing. */
    @Volatile var delayMs = 0L

    /** Sleep before the artifact answer only. */
    @Volatile var artifactDelayMs = 0L

    private val downloadCounter = AtomicInteger(0)
    private val listCounter = AtomicInteger(0)
    private val jobsCounter = AtomicInteger(0)
    val requests = CopyOnWriteArrayList<Seen>()

    val downloads: Int get() = downloadCounter.get()
    val pipelineListCalls: Int get() = listCounter.get()
    val jobListCalls: Int get() = jobsCounter.get()

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "fake-gitlab").apply { isDaemon = true }
    }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = this@FakeGitLab.executor
            start()
        }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun reset() {
        pipelines.clear(); jobs.clear(); shards.clear(); requests.clear()
        unauthorized = false; notFound = false; forcedStatus = 0; delayMs = 0; artifactDelayMs = 0
        downloadCounter.set(0); listCounter.set(0); jobsCounter.set(0)
    }

    /** Convenience: a successful pipeline with one finished job that carries an artifact. */
    fun addReadyPipeline(
        id: Int,
        sha: String,
        ref: String,
        jobName: String,
        jobId: Int = id * 10,
        shard: ByteArray? = null,
    ) {
        pipelines += Pipeline(id, sha, ref)
        jobs += Job(jobId, id, jobName)
        if (shard != null) shards[jobId] = shard
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    // --- request handling ----------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.rawPath
            val query = exchange.requestURI.rawQuery.orEmpty()
            val auth = exchange.requestHeaders.getFirst("PRIVATE-TOKEN")
                ?: exchange.requestHeaders.getFirst("Authorization")
            requests += Seen(exchange.requestMethod, path, query, auth)

            if (delayMs > 0) Thread.sleep(delayMs)
            when {
                unauthorized -> json(exchange, 401, """{"message":"401 Unauthorized"}""")
                notFound -> json(exchange, 404, """{"message":"404 Project Not Found"}""")
                forcedStatus != 0 -> json(exchange, forcedStatus, "{}")
                "/artifacts" in path -> artifact(exchange, path)
                path.endsWith("/repository/merge_base") -> json(exchange, 200, """{"id":"$mergeBase"}""")
                path.endsWith("/jobs") -> jobList(exchange, path)
                path.endsWith("/pipelines") -> pipelineList(exchange, query)
                else -> json(exchange, 404, """{"message":"404 Not Found"}""")
            }
        } catch (_: Throwable) {
            // The client may have given up (timeout test); there is nobody left to answer.
        } finally {
            exchange.close()
        }
    }

    private fun pipelineList(exchange: HttpExchange, query: String) {
        listCounter.incrementAndGet()
        val params = parseQuery(query)
        val ref = params["ref"].orEmpty()
        val sha = params["sha"].orEmpty()
        val body = pipelines
            .filter { ref.isEmpty() || it.ref == ref }
            .filter { sha.isEmpty() || it.sha == sha }
            .joinToString(",", "[", "]") { p ->
                """{"id":${p.id},"sha":"${p.sha}","ref":"${p.ref}","status":"${p.status}","source":"${p.source}",""" +
                    """"web_url":"https://gitlab.example.com/p/${p.id}"}"""
            }
        json(exchange, 200, body)
    }

    private fun jobList(exchange: HttpExchange, path: String) {
        jobsCounter.incrementAndGet()
        val pipelineId = segmentBefore(path, "jobs")
        val expires = java.time.Instant.now().plusSeconds(3600).toString()
        val body = jobs.filter { it.pipeline == pipelineId }.joinToString(",", "[", "]") { j ->
            val artifact = if (j.hasArtifact) {
                ""","artifacts_file":{"filename":"artifacts.zip","size":4096},"artifacts_expire_at":"$expires""""
            } else {
                ""
            }
            """{"id":${j.id},"name":"${j.name}","status":"${j.status}","stage":"test",""" +
                """"pipeline":{"id":${j.pipeline},"sha":"","ref":"refs/heads/feature","status":"success"}$artifact}"""
        }
        json(exchange, 200, body)
    }

    private fun artifact(exchange: HttpExchange, path: String) {
        downloadCounter.incrementAndGet()
        if (artifactDelayMs > 0) Thread.sleep(artifactDelayMs)
        val jobId = segmentBefore(path, "artifacts")
        val bytes = shards[jobId] ?: defaultShard
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun json(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** The numeric path segment right before [marker]: `/jobs/42/artifacts/x` → 42 for "artifacts". */
    private fun segmentBefore(path: String, marker: String): Int {
        val parts = path.trim('/').split('/')
        val index = parts.indexOf(marker)
        return if (index > 0) parts[index - 1].toIntOrNull() ?: -1 else -1
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val (key, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }
}
