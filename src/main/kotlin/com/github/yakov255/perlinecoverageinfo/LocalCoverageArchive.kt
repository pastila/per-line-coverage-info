package com.github.yakov255.perlinecoverageinfo

import java.io.FileOutputStream
import java.nio.file.Files

/** Immutable local run: a COV4 region followed by UTF-8 source snapshots, all on disk. */
internal object LocalCoverageArchive {
    private data class TextBlock(val offset: Long, val length: Int)

    fun store(
        tests: Set<String>,
        coverage: Map<String, Map<Int, List<String>>>,
        commit: String?,
        readSource: (String) -> String,
    ): LocalCoverageRun {
        val temporary = Files.createTempFile("local-coverage-", ".archive").toFile()
        var sourceFile: java.io.File? = null
        try {
            val sources = Files.createTempFile("local-coverage-", ".sources").toFile()
            sourceFile = sources
            val blocks = LinkedHashMap<String, TextBlock>(coverage.size)
            // Capture source contents before the potentially expensive coverage serialization.
            FileOutputStream(sources).buffered().use { output ->
                var offset = 0L
                for (path in coverage.keys) {
                    // Read and encode just one source file at a time, never the whole run.
                    val bytes = readSource(path).toByteArray(Charsets.UTF_8)
                    blocks[path] = TextBlock(offset, bytes.size)
                    output.write(bytes)
                    offset += bytes.size
                }
            }

            Cov4Writer.write(coverage, temporary)
            val coverageSize = temporary.length()
            FileOutputStream(temporary, true).buffered().use { output ->
                sources.inputStream().use { it.copyTo(output) }
            }

            // Both maps own the same snapshot. Fully superseded/cleared runs release it for
            // explicit cleanup once outstanding background readers finish; no project is captured.
            val snapshot = Cov4Snapshot(temporary)
            try {
                val reader = Cov4Reader(snapshot, coverageSize)
                val lines = LazyFileMap(reader.allFilePaths, reader::getCoverage)
                val sourceMap = LazyFileMap(blocks.keys) { path ->
                    blocks[path]?.let { block ->
                        String(snapshot.read(coverageSize + block.offset, block.length).array(), Charsets.UTF_8)
                    }
                }
                return LocalCoverageRun.archived(tests, lines, sourceMap, commit, reader)
            } catch (t: Throwable) {
                snapshot.close()
                throw t
            }
        } finally {
            sourceFile?.delete()
            temporary.delete()
        }
    }
}

/** Read-only map whose keys are cheap metadata and whose values are decoded only on lookup. */
internal class LazyFileMap<V>(
    override val keys: Set<String>,
    private val read: (String) -> V?,
) : AbstractMap<String, V>() {
    override val size: Int get() = keys.size
    override fun containsKey(key: String): Boolean = key in keys
    override fun get(key: String): V? = if (key in keys) read(key) else null

    override val entries: Set<Map.Entry<String, V>>
        get() = object : AbstractSet<Map.Entry<String, V>>() {
            override val size: Int get() = keys.size
            override fun iterator(): Iterator<Map.Entry<String, V>> = keys.asSequence().map { path ->
                java.util.AbstractMap.SimpleImmutableEntry(path, this@LazyFileMap.getValue(path))
            }.iterator()
        }
}
