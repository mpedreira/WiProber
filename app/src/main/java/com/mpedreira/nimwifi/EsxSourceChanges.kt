package com.mpedreira.nimwifi

import java.io.File

/**
 * Mejora 21: what a working export changes in an imported ESX, and the exact copy of the source.
 *
 * - **Exact source copy**: `base.esx` byte for byte (same SHA-256), whatever was done locally.
 * - **Working export**: the source with nimWifi's overlays. Original entries are kept intact,
 *   except the catalogues listed by [plan] for the floors it lists; new surveys, tracks and images
 *   are added as new entries. With no local change the working export is the exact copy too.
 */
object EsxSourceChanges {
    object Reason {
        const val NEW_FLOOR = "new-floor"
        /** The source has no scale and none was confirmed: the assumed scale is written (user consent). */
        const val SCALE_ASSUMED = "scale-assumed"
        const val SCALE_CHANGED = "scale-changed"
        const val LOCAL_SCANS = "local-scans"
        const val LOCAL_ROUTES = "local-routes"
        const val LOCAL_NOTES = "local-notes"
    }

    data class FloorChange(val floorId: String, val floorName: String, val reasons: List<String>)

    data class Plan(
        /** True when the working export is byte-identical to the source. */
        val exact: Boolean,
        val floors: List<FloorChange>,
        /** Source catalogue entries that may be rewritten (all other original entries are kept intact). */
        val catalogs: List<String>,
        /** True when new entries (surveys, tracks, images) are added. */
        val addsEntries: Boolean
    )

    private const val SCALE_EPSILON = 1e-9

    fun plan(snapshot: ProjectExportSnapshot): Plan {
        val floors = snapshot.floors.mapNotNull { floor ->
            val reasons = buildList {
                if (!floor.importedFromSource) add(Reason.NEW_FLOOR)
                else {
                    val exported = FloorScalePolicy.forExport(floor.survey.metersPerUnit)
                    val source = floor.sourceMetersPerUnit
                    if (source == null) add(if (FloorScalePolicy.isConfirmed(floor.survey.metersPerUnit)) Reason.SCALE_CHANGED else Reason.SCALE_ASSUMED)
                    else if (kotlin.math.abs(source - exported) > SCALE_EPSILON) add(Reason.SCALE_CHANGED)
                }
                if (floor.survey.scanPoints.any { !it.importedFromEsx }) add(Reason.LOCAL_SCANS)
                if (floor.survey.continuousScanSessions.any { !it.importedFromEsx }) add(Reason.LOCAL_ROUTES)
                if (floor.survey.notes.any { !it.importedFromEsx }) add(Reason.LOCAL_NOTES)
            }
            reasons.takeIf { it.isNotEmpty() }?.let { FloorChange(floor.id, floor.name, it) }
        }
        val all = floors.flatMap { it.reasons }.toSet()
        val localPhotos = snapshot.floors.any { floor -> floor.survey.notes.any { !it.importedFromEsx && it.photoId != null } }
        val measurements = Reason.LOCAL_SCANS in all || Reason.LOCAL_ROUTES in all
        val catalogs = buildList {
            if (Reason.NEW_FLOOR in all || Reason.SCALE_ASSUMED in all || Reason.SCALE_CHANGED in all) add("floorPlans.json")
            if (Reason.NEW_FLOOR in all || localPhotos) add("images.json")
            if (measurements) addAll(listOf("accessPointMeasurements.json", "surveyLookups.json", "accessPoints.json", "measuredRadios.json", "wifiAdapterInformations.json"))
            if (Reason.LOCAL_NOTES in all) addAll(listOf("notes.json", "pictureNotes.json"))
        }
        return Plan(floors.isEmpty(), floors, catalogs, Reason.NEW_FLOOR in all || measurements || localPhotos)
    }

    /** Copies [source] to [staged] and checks the copy has the same SHA-256; returns it. */
    fun stageExactCopy(source: File, staged: File, maxBytes: Long): String {
        require(source.isFile) { "The source ESX of this project is missing" }
        source.inputStream().use { BoundedIo.copyToFile(it, staged, maxBytes, "The ESX project is too large") }
        val expected = Hashes.sha256(source)
        val actual = Hashes.sha256(staged)
        check(expected == actual) { "The copy of the source ESX does not match it" }
        return actual
    }
}
