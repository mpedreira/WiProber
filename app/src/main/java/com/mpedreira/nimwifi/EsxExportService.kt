package com.mpedreira.nimwifi

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Writes an immutable, whole-project snapshot to an ESX archive. With [bundleEsxName] the
 * destination receives a ZIP with that same ESX plus [PerformanceExport.FILE_NAME].
 */
class EsxExportService(
    context: Context,
    private val reportBuilder: EkahauReportBuilder = EkahauReportBuilder(),
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    /**
     * Mejora 21: the imported source ESX byte for byte (same SHA-256), without any local change.
     * Staged, verified and then copied like any export; returns the SHA-256.
     */
    fun exportSourceCopy(destinationUri: Uri, snapshot: ProjectExportSnapshot): String {
        val source = requireNotNull(snapshot.sourceArchive) { "This project was not imported from an ESX" }
        StagedExport.removeStale(appContext.cacheDir)
        val staged = File(appContext.cacheDir, "${StagedExport.STAGING_PREFIX}source-${System.nanoTime()}.esx")
        try {
            val sha = EsxSourceChanges.stageExactCopy(source, staged, DEFAULT_ESX_LIMITS.maxSourceBytes)
            StagedExport.deliver(staged) { contentResolver.openOutputStream(destinationUri, "wt") }
            return sha
        } finally {
            staged.delete()
        }
    }

    /** [reportSsid]: SSID chosen on the map, reported first (empty = automatic). */
    fun export(
        destinationUri: Uri,
        snapshot: ProjectExportSnapshot,
        bundleEsxName: String? = null,
        reportSsid: String = "",
        reportProfile: SignalProfile = SignalProfile.DEFAULT
    ) {
        StagedExport.removeStale(appContext.cacheDir)
        val exportDirectory = File(appContext.cacheDir, "${StagedExport.STAGING_PREFIX}${System.nanoTime()}")
        val completeArchive = File(appContext.cacheDir, "${exportDirectory.name}.esx")
        val bundleArchive = File(appContext.cacheDir, "${exportDirectory.name}.zip")
        require(exportDirectory.mkdirs()) { "Cannot create the ESX export workspace" }

        try {
            val sourceArchive = snapshot.sourceArchive
            val report: EkahauReport? = when {
                sourceArchive != null && !hasSourceOverlayChanges(snapshot) -> {
                    validateSourceInventory(sourceArchive, snapshot.sourceCompatibility)
                    sourceArchive.inputStream().use { input ->
                        BoundedIo.copyToFile(
                            input,
                            completeArchive,
                            DEFAULT_ESX_LIMITS.maxSourceBytes,
                            "The ESX project is too large"
                        )
                    }
                    null // nothing local to add, so no local measurements either
                }
                sourceArchive != null -> writeOverlayExport(
                    sourceArchive,
                    snapshot,
                    exportDirectory,
                    completeArchive
                )
                else -> writeCompleteExport(snapshot, exportDirectory, completeArchive)
            }

            require(completeArchive.isFile && completeArchive.length() in 1..DEFAULT_ESX_LIMITS.maxSourceBytes) {
                "The ESX archive could not be created"
            }
            // Mejora 18: the whole artifact is staged and verified before the destination is opened.
            val artifact = if (bundleEsxName != null) {
                val now = System.currentTimeMillis()
                val performance = PerformanceExport.build(report, bundleEsxName, now, snapshot)
                bundleArchive.outputStream().buffered().use { output ->
                    PerformanceExport.writeBundle(
                        output,
                        bundleEsxName,
                        completeArchive,
                        PerformanceExport.toBytes(performance),
                        SurveyReport.html(SurveyReport.build(snapshot, now, reportSsid, weakDbm = reportProfile.primaryDbm, profile = reportProfile.id,
                            planImages = planImages(snapshot))).toByteArray(Charsets.UTF_8)
                    )
                }
                StagedExport.verifyZip(bundleArchive, setOf(bundleEsxName, PerformanceExport.FILE_NAME, SurveyReport.FILE_NAME), nestedEsx = bundleEsxName)
                bundleArchive
            } else {
                StagedExport.verifyZip(completeArchive, setOf(StagedExport.ESX_PROJECT_ENTRY))
                completeArchive
            }
            StagedExport.deliver(artifact) { contentResolver.openOutputStream(destinationUri, "wt") }
        } finally {
            exportDirectory.deleteRecursively()
            completeArchive.delete()
            bundleArchive.delete()
        }
    }

    private fun writeCompleteExport(
        snapshot: ProjectExportSnapshot,
        exportDirectory: File,
        completeArchive: File
    ): EkahauReport {
        val report = buildReport(snapshot, localOnly = false)
        val filePayloads = linkedMapOf<String, File>()
        require(snapshot.floors.size == report.floorPlans.floorPlans.size) {
            "Export floor-plan mapping is inconsistent"
        }
        snapshot.floors.zip(report.floorPlans.floorPlans).forEach { (floor, floorPlan) ->
            require(floor.esxImageId == floorPlan.imageId) { "Export image mapping is inconsistent" }
            require(floor.mapFile.isFile) { "Project map payload is missing" }
            require(filePayloads.put("image-${floorPlan.imageId}", floor.mapFile) == null) {
                "Duplicate generated floor-plan image ID"
            }
        }
        stageNotePhotos(exportDirectory, snapshot, filePayloads, localOnly = false)
        val staticPayloads = STATIC_PAYLOADS.associateWith { assetName ->
            { appContext.assets.open(assetName) }
        }
        GeneratedEsxArchiveWriter(gson).write(
            destinationArchive = completeArchive,
            report = report,
            adapterInformation = createAdapterInformation(),
            staticPayloads = staticPayloads,
            filePayloads = filePayloads
        )
        return report
    }

    private fun writeOverlayExport(
        sourceArchive: File,
        snapshot: ProjectExportSnapshot,
        exportDirectory: File,
        completeArchive: File
    ): EkahauReport {
        val report = buildReport(snapshot, localOnly = true)
        val additionalPayloads = linkedMapOf<String, File>()
        snapshot.floors.filterNot(FloorExportSnapshot::importedFromSource).forEach { floor ->
            require(floor.mapFile.isFile) { "Project map payload is missing" }
            require(additionalPayloads.put("image-${floor.esxImageId}", floor.mapFile) == null) {
                "Duplicate generated floor-plan image ID"
            }
        }
        stageNotePhotos(exportDirectory, snapshot, additionalPayloads, localOnly = true)
        EsxArchiveOverlayWriter(gson).write(
            sourceArchive = sourceArchive,
            destinationArchive = completeArchive,
            snapshot = snapshot,
            report = report,
            adapterInformation = createAdapterInformation(),
            additionalPayloads = additionalPayloads
        )
        return report
    }

    private fun buildReport(snapshot: ProjectExportSnapshot, localOnly: Boolean): EkahauReport =
        reportBuilder.build(
            projectName = snapshot.title,
            floors = snapshot.floors.map { floor ->
                require(EsxIdFactory.isUuid(floor.esxFloorPlanId)) { "Floor-plan ESX ID is not a UUID" }
                require(EsxIdFactory.isUuid(floor.esxImageId)) { "Floor-plan image ESX ID is not a UUID" }
                EkahauFloorInput(
                    name = floor.name,
                    mapInfo = floor.survey.mapInfo,
                    metersPerUnit = floor.survey.metersPerUnit,
                    scanPoints = floor.survey.scanPoints.filter { !localOnly || !it.importedFromEsx },
                    continuousSessions = floor.survey.continuousScanSessions
                        .filter { !localOnly || !it.importedFromEsx },
                    notes = floor.survey.notes.filter { !localOnly || !it.importedFromEsx },
                    floorPlanId = floor.esxFloorPlanId,
                    imageId = floor.esxImageId
                )
            }
        )

    private fun validateSourceInventory(
        sourceArchive: File,
        expected: EsxSourceCompatibility?
    ) {
        EsxArchiveReader(sourceArchive).use { source ->
            if (expected != null) {
                require(source.compatibilityInventory() == expected) {
                    "The imported ESX source archive no longer matches its stored inventory"
                }
            }
        }
    }

    private fun createAdapterInformation(): EsxWifiAdapterInformation {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val userDefinedName = runCatching {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        val adapterName = if (!userDefinedName.isNullOrBlank() && userDefinedName != model) {
            "$userDefinedName ($manufacturer $model)"
        } else {
            "$manufacturer $model"
        }
        return EsxWifiAdapterInformation(name = adapterName)
    }

    private fun stageNotePhotos(
        exportDirectory: File,
        snapshot: ProjectExportSnapshot,
        additionalPayloads: MutableMap<String, File>,
        localOnly: Boolean
    ) {
        snapshot.floors.flatMap { it.survey.notes }
            .filter { !localOnly || !it.importedFromEsx }
            .forEach { note ->
                if (note.photoUri != null && note.photoId != null) {
                    val entryName = "image-${note.photoId}"
                    val destination = File(exportDirectory, entryName)
                    requireNotNull(contentResolver.openInputStream(Uri.parse(note.photoUri))) {
                        "A note photo payload is missing"
                    }.use { input ->
                        BoundedIo.copyToFile(
                            input,
                            destination,
                            DEFAULT_ESX_LIMITS.maxPhotoBytes,
                            "A note photo is too large"
                        )
                    }
                    require(additionalPayloads.put(entryName, destination) == null) {
                        "Duplicate generated note image ID"
                    }
                }
            }
    }

    /** Mejora 8: every floor plan, downscaled for the report (marks keep original-image pixels). */
    private fun planImages(snapshot: ProjectExportSnapshot): Map<String, SurveyReport.PlanImage> = snapshot.floors.mapNotNull { floor ->
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(floor.mapFile.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > REPORT_MAP_MAX_SIDE) sample *= 2
            val bitmap = android.graphics.BitmapFactory.decodeFile(floor.mapFile.path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                bitmap.recycle()
                out.toByteArray()
            }
            floor.esxFloorPlanId to SurveyReport.PlanImage(
                "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
                floor.survey.mapInfo.width, floor.survey.mapInfo.height
            )
        }.getOrNull()
    }.toMap()

    internal companion object {
        private const val SCALE_EPSILON = 1e-12
        private const val REPORT_MAP_MAX_SIDE = 1_600

        /**
         * Whether the imported archive needs a local overlay. Active runs and planned points never do:
         * their data goes to wiprober-performance.json, so the source ESX is copied byte for byte.
         */
        internal fun hasSourceOverlayChanges(snapshot: ProjectExportSnapshot): Boolean =
            snapshot.floors.any { floor ->
                !floor.importedFromSource ||
                    !sameScale(floor.sourceMetersPerUnit, FloorScalePolicy.forExport(floor.survey.metersPerUnit)) ||
                    floor.survey.scanPoints.any { !it.importedFromEsx } ||
                    floor.survey.continuousScanSessions.any { !it.importedFromEsx } ||
                    floor.survey.notes.any { !it.importedFromEsx }
            }

        private fun sameScale(first: Double?, second: Double?): Boolean {
            if (first == null || second == null) return first == second
            return kotlin.math.abs(first - second) <= SCALE_EPSILON
        }

        val STATIC_PAYLOADS = listOf(
            "projectConfiguration.json",
            "requirements.json",
            "usageProfiles.json",
            "version",
            "wallTypes.json",
            "applicationProfiles.json",
            "attenuationAreaTypes.json",
            "deviceProfiles.json",
            "floorTypes.json",
            "networkCapacitySettings.json"
        )
    }
}
