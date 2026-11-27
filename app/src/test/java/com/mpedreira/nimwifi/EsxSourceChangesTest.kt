package com.mpedreira.nimwifi

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Mejora 21: exact source copy versus working export of an imported ESX. */
class EsxSourceChangesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val floorId = "11111111-1111-4111-8111-111111111111"
    private val imageId = "22222222-2222-4222-8222-222222222222"

    private fun sourceEntries(scale: String?) = linkedMapOf(
        "project.json" to """{"project":{"id":"src","title":"Src"}}""".toByteArray(),
        "floorPlans.json" to """{"floorPlans":[{"id":"$floorId","name":"Ground","width":100,"height":80,"imageId":"$imageId"${scale?.let { ",\"metersPerUnit\":$it" } ?: ""},"keep":"x"}]}""".toByteArray(),
        "images.json" to """{"images":[{"id":"$imageId","imageFormat":"PNG"}]}""".toByteArray(),
        "accessPointMeasurements.json" to """{"accessPointMeasurements":[]}""".toByteArray(),
        "surveyLookups.json" to """{"surveyLookups":[]}""".toByteArray(),
        "accessPoints.json" to """{"accessPoints":[]}""".toByteArray(),
        "measuredRadios.json" to """{"measuredRadios":[]}""".toByteArray(),
        "notes.json" to """{"notes":[]}""".toByteArray(),
        "pictureNotes.json" to """{"pictureNotes":[]}""".toByteArray(),
        "wifiAdapterInformations.json" to """{"wifiAdapterInformations":[]}""".toByteArray(),
        "image-$imageId" to byteArrayOf(1, 2, 3),
        "survey-old.json" to """{"surveys":[{"id":"old","floorPlanId":"$floorId"}]}""".toByteArray(),
        "track-old.bin" to byteArrayOf(9, 9, 9),
        "future/entity.bin" to byteArrayOf(42)
    )

    private fun zip(entries: Map<String, ByteArray>) = File(temp.root, "source-${System.nanoTime()}.esx").also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }

    private fun read(file: File) = ZipFile(file).use { z -> z.entries().toList().filterNot { it.isDirectory }.associate { it.name to z.getInputStream(it).readBytes() } }

    private fun snapshot(source: File, sourceScale: Double?, currentScale: Double?, points: List<ScanPoint> = emptyList()) =
        ProjectExportSnapshot("p", "Src", source, listOf(FloorExportSnapshot("floor-1", "Ground", File("m.png"), floorId, imageId, true, sourceScale,
            ProjectSnapshot(MapInfo("m.png", 100, 80), currentScale, points, emptyList(), emptyList()))))

    @Test
    fun theExactCopyHasTheSourceSha256WhateverChangedLocally() {
        val source = zip(sourceEntries(null))
        val staged = File(temp.root, "copy.esx")
        val local = ScanPoint(1L, 5f, 5f, listOf(WifiNetworkInfo("Ops", "aa:01", -60, 5_180, "WPA2", emptyList(), "")))
        assertFalse(EsxSourceChanges.plan(snapshot(source, null, 0.1, listOf(local))).exact)
        val sha = EsxSourceChanges.stageExactCopy(source, staged, 1L shl 30)
        assertEquals(Hashes.sha256(source), sha)
        assertArrayEquals(source.readBytes(), staged.readBytes())
    }

    @Test
    fun withConsentAndNoOtherChangeOnlyTheScaleCatalogueChanges() {
        val originals = sourceEntries(null)
        val source = zip(originals)
        val snap = snapshot(source, sourceScale = null, currentScale = null)
        val plan = EsxSourceChanges.plan(snap)
        assertFalse(plan.exact)
        assertEquals(listOf(EsxSourceChanges.Reason.SCALE_ASSUMED), plan.floors.single().reasons)
        assertEquals(listOf("floorPlans.json"), plan.catalogs)
        assertFalse(plan.addsEntries)
        assertTrue(EsxExportService.hasSourceOverlayChanges(snap))

        val output = File(temp.root, "work.esx")
        val report = EkahauReportBuilder().build("Src", listOf(EkahauFloorInput("Ground", MapInfo("m.png", 100, 80), null, emptyList(), emptyList(), emptyList(), floorId, imageId)))
        EsxArchiveOverlayWriter(GsonBuilder().create()).write(source, output, snap, report,
            EsxWifiAdapterInformation(name = "T", driverVersion = "1", driverDate = "01-01-2026", revision = "t"), emptyMap())
        val exported = read(output)
        assertEquals(originals.keys, exported.keys)
        val floorPlan = JsonParser.parseString(exported.getValue("floorPlans.json").decodeToString()).asJsonObject
            .getAsJsonArray("floorPlans")[0].asJsonObject
        assertEquals(FloorScalePolicy.ASSUMED_METERS_PER_PIXEL, floorPlan["metersPerUnit"].asDouble, 0.0)
        assertEquals("x", floorPlan["keep"].asString)
        // Every other entry, uncompressed, byte for byte.
        (originals.keys - "floorPlans.json").forEach { name -> assertArrayEquals(name, originals.getValue(name), exported.getValue(name)) }
    }

    @Test
    fun aConfirmedScaleEqualToTheSourceIsNoChangeAndAMissingOneIsListed() {
        val source = zip(sourceEntries("0.05"))
        val same = EsxSourceChanges.plan(snapshot(source, 0.05, 0.05))
        assertTrue(same.exact)
        assertTrue(same.catalogs.isEmpty())
        assertFalse(EsxExportService.hasSourceOverlayChanges(snapshot(source, 0.05, 0.05)))
        val changed = EsxSourceChanges.plan(snapshot(source, 0.05, 0.1))
        assertEquals(listOf(EsxSourceChanges.Reason.SCALE_CHANGED), changed.floors.single().reasons)
        // Source with a scale, current one missing: the assumed scale replaces it, and that is a change.
        assertEquals(listOf(EsxSourceChanges.Reason.SCALE_CHANGED), EsxSourceChanges.plan(snapshot(source, 0.05, null)).floors.single().reasons)
        val withScans = EsxSourceChanges.plan(snapshot(source, 0.05, 0.05,
            listOf(ScanPoint(1L, 5f, 5f, listOf(WifiNetworkInfo("Ops", "aa:01", -60, 5_180, "WPA2", emptyList(), ""))))))
        assertTrue(withScans.addsEntries && "accessPointMeasurements.json" in withScans.catalogs && "floorPlans.json" !in withScans.catalogs)
    }
}
