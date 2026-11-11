package com.github.yakov255.perlinecoverageinfo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The covd binaries are committed under `src/main/resources/bin/` and shipped inside the plugin
 * jar. These tests guard the three ways that went wrong before: a release zip without a daemon,
 * a manifest that no longer matches the files, and a half-written extraction taken for a binary.
 */
class BundledCovdTest {

    private val platforms = mapOf(
        "linux-x64" to "covd",
        "linux-arm64" to "covd",
        "mac-x64" to "covd",
        "mac-arm64" to "covd",
        "win-x64" to "covd.exe",
    )

    private val temp: File = Files.createTempDirectory("bundled-covd-test").toFile()

    @After
    fun deleteTemp() {
        temp.deleteRecursively()
    }

    private fun resourceBytes(platform: String): ByteArray {
        val name = platforms.getValue(platform)
        return BundledCovdTest::class.java.getResourceAsStream("/bin/$platform/$name")?.use { it.readBytes() }
            ?: error("the plugin resources carry no covd for $platform (run scripts/update-covd.sh)")
    }

    private fun manifest(): Properties = Properties().apply {
        val stream = BundledCovdTest::class.java.getResourceAsStream("/bin/covd-manifest.properties")
            ?: error("missing /bin/covd-manifest.properties")
        stream.use { load(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // --- the committed resources ---------------------------------------------------------

    @Test
    fun `every supported platform has a daemon binary`() {
        for (platform in platforms.keys) {
            val bytes = resourceBytes(platform)
            assertTrue("$platform binary is suspiciously small: ${bytes.size} bytes", bytes.size > 1_000_000)
        }
    }

    @Test
    fun `the manifest matches the committed binaries`() {
        val manifest = manifest()
        assertTrue("the manifest names the covd version", !manifest.getProperty("version").isNullOrBlank())
        for (platform in platforms.keys) {
            assertEquals(
                "run scripts/update-covd.sh: the manifest hash of $platform is stale",
                manifest.getProperty(platform),
                sha256(resourceBytes(platform)),
            )
        }
    }

    @Test
    fun `each binary has the executable format of its platform`() {
        fun head(platform: String) = resourceBytes(platform).copyOf(4).map { it.toInt() and 0xFF }

        assertEquals(listOf(0x7F, 'E'.code, 'L'.code, 'F'.code), head("linux-x64"))
        assertEquals(listOf(0x7F, 'E'.code, 'L'.code, 'F'.code), head("linux-arm64"))
        assertEquals("Mach-O 64-bit little endian", listOf(0xCF, 0xFA, 0xED, 0xFE), head("mac-x64"))
        assertEquals("Mach-O 64-bit little endian", listOf(0xCF, 0xFA, 0xED, 0xFE), head("mac-arm64"))
        assertEquals("PE/MZ", listOf('M'.code, 'Z'.code), head("win-x64").take(2))
    }

    // --- extraction ----------------------------------------------------------------------

    private fun extractLinux(root: File): File? =
        CovdClient.extractBundledBinary("/bin/linux-x64/covd", "covd", root)

    @Test
    fun `extraction names the directory after the binary content`() {
        val bytes = resourceBytes("linux-x64")

        val file = extractLinux(temp)

        assertNotNull(file)
        assertEquals("bin-covd-${sha256(bytes).take(16)}", file!!.parentFile.name)
        assertEquals(bytes.size.toLong(), file.length())
        assertTrue("the extracted daemon must be executable", file.canExecute())
        assertEquals(sha256(bytes), sha256(file.readBytes()))
    }

    @Test
    fun `extraction is reused and leaves no temporary files`() {
        val first = extractLinux(temp)!!
        val stamp = first.lastModified()

        val second = extractLinux(temp)!!

        assertEquals(first, second)
        assertEquals("a complete file must not be written again", stamp, second.lastModified())
        assertEquals(listOf("covd"), first.parentFile.list()!!.toList())
    }

    @Test
    fun `a file with the wrong size is replaced instead of reused`() {
        val good = extractLinux(temp)!!
        val expected = good.length()
        good.writeBytes(ByteArray(1024)) // what an interrupted older extraction left behind
        assertNotEquals(expected, good.length())

        val repaired = extractLinux(temp)!!

        assertEquals(good, repaired)
        assertEquals(expected, repaired.length())
        assertEquals(sha256(resourceBytes("linux-x64")), sha256(repaired.readBytes()))
    }

    @Test
    fun `a missing resource is reported as no binary`() {
        assertNull(CovdClient.extractBundledBinary("/bin/no-such-platform/covd", "covd", temp))
        assertFalse(File(temp, "bin-covd-").exists())
    }

    @Test
    fun `eight processes extracting at once end up with one complete binary`() {
        val expected = sha256(resourceBytes("linux-x64"))
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit<File?> {
                    barrier.await(10, TimeUnit.SECONDS)
                    extractLinux(temp)
                }
            }
            val files = futures.map { it.get(60, TimeUnit.SECONDS) }

            assertEquals("everyone gets the same file", 1, files.map { it?.absolutePath }.toSet().size)
            val file = files.first()!!
            assertEquals(expected, sha256(file.readBytes()))
            assertEquals("no temporary files are left", listOf("covd"), file.parentFile.list()!!.toList())
        } finally {
            pool.shutdownNow()
        }
    }
}
