package com.example.wiprober

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `wiprober-performance.json`: the per-point throughput results, exported next to the ESX (never
 * inside it, so Ekahau only ever sees an unchanged archive). Rows are joined to the ESX of the
 * same export by `surveyId`, and across exports by `floorPlanId` + `x`/`y` (floor-plan pixels,
 * the same values written to the survey's route point).
 */
object PerformanceExport {
    const val FILE_NAME = "wiprober-performance.json"
    const val FORMAT = "wiprober-performance"
    const val VERSION = 1

    fun hasMeasurements(snapshot: ProjectExportSnapshot): Boolean =
        snapshot.floors.any { floor -> floor.survey.scanPoints.any { !it.throughput?.tests.isNullOrEmpty() } }

    fun build(report: EkahauReport?, esxFileName: String, generatedAt: Long): JsonObject {
        val rows = JsonArray()
        report?.stopAndGoSurveys.orEmpty().forEach { ref ->
            ref.point.throughput?.tests.orEmpty().forEach { test -> rows.add(row(ref, test)) }
        }
        return JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            addProperty("generatedAt", isoUtc(generatedAt))
            addProperty("esxFile", esxFileName)
            add("measurements", rows)
        }
    }

    fun toBytes(json: JsonObject): ByteArray =
        GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(json)
            .toByteArray(StandardCharsets.UTF_8)

    /** Writes the export bundle: the ESX archive untouched plus the performance file. */
    fun writeBundle(output: OutputStream, esxFileName: String, esxArchive: File, performanceJson: ByteArray) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(esxFileName))
            esxArchive.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(FILE_NAME))
            zip.write(performanceJson)
            zip.closeEntry()
        }
    }

    private fun row(ref: StopAndGoSurveyRef, test: ThroughputTestResult) = JsonObject().apply {
        addProperty("surveyId", ref.surveyId)
        addProperty("floorPlanId", ref.floorPlanId)
        addProperty("floorName", ref.floorName)
        addProperty("x", ref.point.x.toDouble())
        addProperty("y", ref.point.y.toDouble())
        addProperty("measuredAt", isoUtc(test.measuredAt))
        addProperty("target", test.target)
        addProperty("method", test.method)
        addProperty("durationSeconds", test.durationSeconds)
        addProperty("ssid", test.ssid)
        addProperty("bssidStart", test.bssidStart)
        addProperty("bssidEnd", test.bssidEnd)
        addProperty("rssi", test.rssi)
        addProperty("linkMbps", test.linkMbps)
        addProperty("rxLinkMbps", test.rxLinkMbps)
        addProperty("frequencyMhz", test.frequencyMhz)
        addProperty("channel", test.channel)
        addProperty("channelWidthMhz", test.channelWidthMhz)
        addProperty("wifiStandard", test.wifiStandard)
        addProperty("bssStationCount", test.bssStationCount)
        addProperty("bssChannelUtilizationPct", test.bssChannelUtilizationPct)
        addProperty("downMbps", test.downMbps)
        addProperty("upMbps", test.upMbps)
        addProperty("latencyMs", test.latencyMs)
        addProperty("latencyAvgMs", test.latencyAvgMs)
        addProperty("jitterMs", test.jitterMs)
        addProperty("latencySamples", test.latencySamples)
        addProperty("deviceModel", test.deviceModel)
        addProperty("androidVersion", test.androidVersion)
        addProperty("error", test.error)
    }

    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
