package com.github.yakov255.perlinecoverageinfo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * Damage tests for [Cov4Reader] (review items M1/M2).
 *
 * A real COV4 file is written with [Cov4TestWriter], then its bytes are damaged the way a
 * truncated download, a half-written cache or another IDE deleting the file would damage it.
 * The reader must never throw `NegativeArraySizeException`/`OutOfMemoryError` and must keep
 * serving the undamaged files of the same cache.
 */
class Cov4ReaderDamageTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cov4-damage-").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- happy path ------------------------------------------------------------------

    @Test
    fun `an intact file is read through the buffered index`() {
        val file = write(mapOf("src/Foo.php" to mapOf(1 to listOf("Test::a"), 2 to emptyList())))

        Cov4Reader(file).use { reader ->
            assertEquals(setOf("src/Foo.php"), reader.allFilePaths)
            assertEquals(listOf("Test::a"), reader.getCoverage("src/Foo.php")?.get(1))
            assertEquals(emptyList<String>(), reader.getCoverage("src/Foo.php")?.get(2))
        }
    }

    @Test
    fun `a large index is read entry by entry through one buffer`() {
        val coverage = (1..1_500).associate { i ->
            "src/Dir${i % 17}/File$i.php" to mapOf(1 to listOf("Test::file$i"))
        }
        val file = write(coverage)

        val samplePath = coverage.keys.first { it.endsWith("File1500.php") }
        Cov4Reader(file).use { reader ->
            assertEquals(coverage.size, reader.allFilePaths.size)
            assertEquals(listOf("Test::file1500"), reader.getCoverage(samplePath)?.get(1))
        }
    }

    // --- M2(b): damaged lengths and counts ------------------------------------------

    @Test
    fun `a truncated file raises a parse error instead of reading past its end`() {
        val file = write(mapOf("src/Foo.php" to mapOf(1 to listOf("Test::a"))))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))

        val error = assertThrows(CoverageApiException::class.java) { Cov4Reader(file) }

        assertEquals(CoverageErrorKind.ARTIFACT_PARSE, error.kind)
    }

    @Test
    fun `a negative string length is reported as a parse error`() {
        val file = write(mapOf("src/Foo.php" to mapOf(1 to listOf("Test::a"))))
        // The first global test name starts right after the 28-byte header.
        putInt(file, HEADER_SIZE, -1)

        val error = assertThrows(CoverageApiException::class.java) { Cov4Reader(file) }

        assertEquals(CoverageErrorKind.ARTIFACT_PARSE, error.kind)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("negative string length"))
    }

    @Test
    fun `an implausible test count is rejected before anything is allocated`() {
        val file = write(mapOf("src/Foo.php" to mapOf(1 to listOf("Test::a"))))
        putInt(file, 12, Int.MAX_VALUE) // header: test count

        val error = assertThrows(CoverageApiException::class.java) { Cov4Reader(file) }

        assertEquals(CoverageErrorKind.ARTIFACT_PARSE, error.kind)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("implausible test count"))
    }

    @Test
    fun `a negative section size in the index is rejected`() {
        val file = write(mapOf("src/Foo.php" to mapOf(1 to listOf("Test::a"))))
        val entry = indexOffset(file) + INDEX_HEADER_SIZE
        putInt(file, entry + DATA_SIZE_OFFSET, -1)

        val error = assertThrows(CoverageApiException::class.java) { Cov4Reader(file) }

        assertEquals(CoverageErrorKind.ARTIFACT_PARSE, error.kind)
    }

    @Test
    fun `a section pointing outside the file is rejected`() {
        val file = write(mapOf("src/Foo.php" to mapOf(1 to listOf("Test::a"))))
        val entry = indexOffset(file) + INDEX_HEADER_SIZE
        putInt(file, entry + DATA_OFFSET_OFFSET, Int.MAX_VALUE)

        assertThrows(CoverageApiException::class.java) { Cov4Reader(file) }
    }

    // --- M2(c): CRC ----------------------------------------------------------------

    @Test
    fun `a damaged section yields no coverage for that file and keeps the others`() {
        val file = write(
            mapOf(
                "src/Broken.php" to mapOf(1 to listOf("Test::broken")),
                "src/Intact.php" to mapOf(1 to listOf("Test::intact")),
            ),
        )
        val brokenEntry = findEntry(file, "src/Broken.php")
        // Flip the last byte of the section: the size stays valid, the CRC does not match.
        val lastByte = readByte(file, brokenEntry.dataOffset + brokenEntry.dataSize - 1)
        writeByte(file, brokenEntry.dataOffset + brokenEntry.dataSize - 1, lastByte xor 0x01)

        Cov4Reader(file).use { reader ->
            assertTrue("the index stays readable", reader.allFilePaths.contains("src/Broken.php"))
            assertEquals("a damaged file has no coverage", emptyMap<Int, List<String>>(), reader.getCoverage("src/Broken.php"))
            // Reading it again is still quiet, not an exception and not a different result.
            assertEquals(emptyMap<Int, List<String>>(), reader.getCoverage("src/Broken.php"))
            assertEquals(listOf("Test::intact"), reader.getCoverage("src/Intact.php")?.get(1))
        }
    }

    // --- M2(d): file removed by another IDE ---------------------------------------

    @Test
    fun `a cache file deleted under the reader serves no coverage instead of throwing`() {
        val file = write(mapOf("src/Foo.php" to mapOf(1 to listOf("Test::a"))))
        Cov4Reader(file).use { reader ->
            assertTrue(reader.hasData())

            Files.delete(file.toPath())

            assertEquals(emptySet<String>(), reader.allFilePaths)
            assertFalse(reader.hasData())
            assertNull(reader.getCoverage("src/Foo.php"))
            // A second access must not retry the failing open or throw either.
            assertEquals(emptySet<String>(), reader.allFilePaths)
        }
    }

    @Test
    fun `a replaced file is reindexed and the new paths are served`() {
        val file = write(mapOf("src/Old.php" to mapOf(1 to listOf("Test::old"))))
        Cov4Reader(file).use { reader ->
            assertEquals(setOf("src/Old.php"), reader.allFilePaths)

            write(mapOf("src/New.php" to mapOf(1 to listOf("Test::new"))), file)

            assertEquals(setOf("src/New.php"), reader.allFilePaths)
            assertNotNull(reader.getCoverage("src/New.php"))
            assertNull(reader.getCoverage("src/Old.php"))
        }
    }

    // --- helpers -------------------------------------------------------------------

    private data class Entry(val dataOffset: Int, val dataSize: Int)

    /** Reads the INDX entry of [path] by walking the index the reader would walk. */
    private fun findEntry(file: File, path: String): Entry = RandomAccessFile(file, "r").use { raf ->
        val indexOffset = readIntAt(raf, 20L)
        raf.seek(indexOffset.toLong())
        assertEquals(MARKER_INDEX, readLeInt(raf))
        val count = readLeInt(raf)
        for (i in 0 until count) {
            readLeLong(raf) // path hash
            val pathOffset = readLeInt(raf)
            val dataOffset = readLeInt(raf)
            val dataSize = readLeInt(raf)
            readLeInt(raf) // crc
            val next = raf.filePointer
            raf.seek((dataOffset + pathOffset).toLong())
            val length = readLeInt(raf)
            val bytes = ByteArray(length)
            raf.readFully(bytes)
            raf.seek(next)
            if (String(bytes, Charsets.UTF_8) == path) return@use Entry(dataOffset, dataSize)
        }
        error("no index entry for $path")
    }

    private fun indexOffset(file: File): Long = RandomAccessFile(file, "r").use { readIntAt(it, 20L).toLong() }

    private fun readIntAt(raf: RandomAccessFile, offset: Long): Int {
        raf.seek(offset)
        return readLeInt(raf)
    }

    private fun readLeInt(raf: RandomAccessFile): Int = Integer.reverseBytes(raf.readInt())

    private fun readLeLong(raf: RandomAccessFile): Long = java.lang.Long.reverseBytes(raf.readLong())

    private fun putInt(file: File, offset: Long, value: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.writeInt(Integer.reverseBytes(value))
        }
    }

    private fun readByte(file: File, offset: Int): Int =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset.toLong())
            raf.readUnsignedByte()
        }

    private fun writeByte(file: File, offset: Int, value: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset.toLong())
            raf.writeByte(value)
        }
    }

    private fun write(coverage: Map<String, Map<Int, List<String>>>, target: File? = null): File {
        val file = target ?: File(dir, "cache.cov4")
        Cov4TestWriter.write(coverage, file)
        return file
    }

    private companion object {
        const val HEADER_SIZE = 28L
        const val MARKER_INDEX = 0x494E4458
        const val INDEX_HEADER_SIZE = 8L

        /** Entry: path hash (8) + path offset (4), then data offset, data size and CRC32. */
        const val DATA_OFFSET_OFFSET = 12
        const val DATA_SIZE_OFFSET = 16
    }
}
