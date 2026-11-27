package com.mpedreira.nimwifi

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Mejora 18: staged, verified, chunked export; partial copies are reported as such. */
class StagedExportTest {
    private val dir = Files.createTempDirectory("staged").toFile()

    private fun zip(name: String, entries: Map<String, ByteArray>) = File(dir, name).also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (entry, bytes) -> out.putNextEntry(ZipEntry(entry)); out.write(bytes); out.closeEntry() }
        }
    }

    private fun esx() = zip("p.esx", mapOf("project.json" to "{}".toByteArray()))

    @Test
    fun aLargeBundleIsVerifiedIncludingTheNestedEsxAndCopiedWhole() {
        // ~6 MB of incompressible synthetic data stands in for a large project.
        val big = ByteArray(6 * 1024 * 1024).also { java.util.Random(1).nextBytes(it) }
        val esx = zip("big.esx", mapOf("project.json" to "{}".toByteArray(), "image-1" to big))
        val bundle = File(dir, "b.zip")
        bundle.outputStream().use { PerformanceExport.writeBundle(it, "Site.esx", esx, "{}".toByteArray(), "<html>".toByteArray()) }
        StagedExport.verifyZip(bundle, setOf("Site.esx", PerformanceExport.FILE_NAME, SurveyReport.FILE_NAME), nestedEsx = "Site.esx")

        val out = ByteArrayOutputStream()
        assertEquals(bundle.length(), StagedExport.deliver(bundle) { out })
        assertArrayEquals(bundle.readBytes(), out.toByteArray())
        assertFalse("nested check leaves nothing behind", File(dir, "b.zip.nested").exists())
    }

    @Test
    fun anIncompleteArtifactIsRejectedBeforeTheDestinationIsOpened() {
        val missing = zip("m.zip", mapOf("Site.esx" to esx().readBytes()))
        var opened = false
        try {
            StagedExport.verifyZip(missing, setOf("Site.esx", PerformanceExport.FILE_NAME))
            StagedExport.deliver(missing) { opened = true; ByteArrayOutputStream() }
            fail()
        } catch (e: StagedExport.InvalidArtifactException) {
            assertTrue(e.message!!.contains(PerformanceExport.FILE_NAME))
        }
        assertFalse(opened)

        val truncated = File(dir, "t.zip").also { it.writeBytes(esx().readBytes().copyOf(20)) }
        try { StagedExport.verifyZip(truncated, setOf("project.json")); fail() } catch (_: StagedExport.InvalidArtifactException) {}
        val badNested = zip("n.zip", mapOf("Site.esx" to "not a zip".toByteArray()))
        try { StagedExport.verifyZip(badNested, setOf("Site.esx"), nestedEsx = "Site.esx"); fail() } catch (_: StagedExport.InvalidArtifactException) {}
    }

    @Test
    fun aCopyThatFailsMidwayIsReportedAsAPossiblyIncompleteDestination() {
        val artifact = File(dir, "a.bin").also { it.writeBytes(ByteArray(300_000) { 7 }) }
        val failing = object : OutputStream() {
            var written = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written + len > 100_000) throw IOException("provider gone")
                written += len
            }
        }
        try {
            StagedExport.deliver(artifact) { failing }
            fail()
        } catch (e: StagedExport.DestinationIncompleteException) {
            assertEquals(300_000L, e.totalBytes)
            assertTrue(e.copiedBytes in 1 until 300_000)
            assertEquals("provider gone", e.cause!!.message)
        }
        // A destination that cannot be opened is not "incomplete": it was never touched.
        try { StagedExport.deliver(artifact) { null }; fail() } catch (e: IOException) {
            assertFalse(e is StagedExport.DestinationIncompleteException)
        }
    }

    @Test
    fun staleStagingIsRemovedAndFreshIsKept() {
        val stale = File(dir, "${StagedExport.STAGING_PREFIX}1").apply { mkdirs(); setLastModified(0) }
        val fresh = File(dir, "${StagedExport.STAGING_PREFIX}2.esx").apply { writeText("x") }
        val other = File(dir, "other").apply { writeText("x"); setLastModified(0) }
        StagedExport.removeStale(dir, now = System.currentTimeMillis())
        assertFalse(stale.exists())
        assertTrue(fresh.exists() && other.exists())
    }

    @Test
    fun coverageIsRecomputedOnlyWhenItsInputsChange() {
        val cache = CoverageSampleCache()
        val net = WifiNetworkInfo("Ops", "aa:01", -60, 5_180, "WPA2", emptyList(), "")
        val points = listOf(ScanPoint(1, 5f, 5f, listOf(net)))
        val sessions = emptyList<ContinuousScanSession>()
        val first = cache.samples(points, sessions, "Ops")
        // Progress-only updates reuse the same lists: no recomputation.
        repeat(50) { assertTrue(cache.samples(points, sessions, "Ops") === first) }
        assertEquals(1, cache.computations)
        cache.samples(points, sessions, "Guest")
        cache.samples(points + ScanPoint(2, 9f, 9f, listOf(net)), sessions, "Guest")
        assertEquals(3, cache.computations)
    }
}
