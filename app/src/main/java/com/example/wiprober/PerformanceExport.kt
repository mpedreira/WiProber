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

    fun hasMeasurements(snapshot: ProjectExportSnapshot): Boolean = snapshot.floors.any { floor ->
        floor.survey.scanPoints.any { it.throughput?.isEmpty == false } ||
            floor.survey.continuousScanSessions.any { !it.importedFromEsx && it.roaming != null }
    }

    fun build(report: EkahauReport?, esxFileName: String, generatedAt: Long): JsonObject {
        val rows = JsonArray()
        val networkRows = JsonArray()
        val scriptRows = JsonArray()
        report?.stopAndGoSurveys.orEmpty().forEach { ref ->
            val throughput = ref.point.throughput ?: return@forEach
            throughput.tests.forEach { test -> rows.add(row(ref, test)) }
            throughput.networkTests.orEmpty().forEach { test -> networkRows.add(networkRow(ref, test)) }
            throughput.scripts.orEmpty().forEach { run ->
                run.steps.forEachIndexed { index, step -> scriptRows.add(scriptRow(ref, run, index, step)) }
            }
        }
        return JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            addProperty("generatedAt", isoUtc(generatedAt))
            addProperty("esxFile", esxFileName)
            add("measurements", rows)
            add("networkTests", networkRows)
            add("scriptSteps", scriptRows)
            add("roamingSessions", JsonArray().apply {
                report?.continuousSurveys.orEmpty().forEach { ref -> ref.session.roaming?.let { add(roamingSessionRow(ref, it)) } }
            })
            add("roamingEvents", JsonArray().apply {
                report?.continuousSurveys.orEmpty().forEach { ref ->
                    ref.session.roaming?.events.orEmpty().forEach { add(roamingEventRow(ref, it)) }
                }
            })
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

    private fun roamingSessionRow(ref: ContinuousSurveyRef, log: RoamingLog) = JsonObject().apply {
        addProperty("surveyId", ref.surveyId)
        addProperty("floorPlanId", ref.floorPlanId)
        addProperty("floorName", ref.floorName)
        addProperty("startedAt", isoUtc(ref.session.startTime))
        addProperty("endedAt", isoUtc(ref.session.endTime))
        addProperty("target", log.target)
        addProperty("intervalMs", log.intervalMillis)
        addProperty("probes", log.probes)
        addProperty("lost", log.lost)
        addProperty("lossPct", if (log.probes > 0) round1(log.lost * 100.0 / log.probes) else null)
        addProperty("avgRttMs", log.avgRttMs)
        addProperty("roams", log.events.count { it.type == RoamingEvent.ROAM })
        addProperty("outages", log.events.count { it.type == RoamingEvent.OUTAGE })
    }

    /** Placed on the marked route by time, like the Continuous scans in the ESX. */
    private fun roamingEventRow(ref: ContinuousSurveyRef, event: RoamingEvent) = JsonObject().apply {
        val route = ref.session.waypoints.sortedBy(RoutePointWrapper::timestamp)
        val position = SurveyGuide.positionAt(route, event.atMillis)
        addProperty("surveyId", ref.surveyId)
        addProperty("floorPlanId", ref.floorPlanId)
        addProperty("floorName", ref.floorName)
        addProperty("x", position?.first?.toDouble())
        addProperty("y", position?.second?.toDouble())
        addProperty("at", isoUtc(ref.session.startTime + event.atMillis))
        addProperty("type", event.type)
        addProperty("fromBssid", event.fromBssid)
        addProperty("toBssid", event.toBssid)
        addProperty("fromRssi", event.fromRssi)
        addProperty("toRssi", event.toRssi)
        addProperty("outageMs", event.outageMillis)
        addProperty("lostProbes", event.lostProbes)
        addProperty("duringScan", event.duringScan)
    }

    /** Position fields shared by every row type. */
    private fun JsonObject.position(ref: StopAndGoSurveyRef, measuredAt: Long) {
        addProperty("surveyId", ref.surveyId)
        addProperty("floorPlanId", ref.floorPlanId)
        addProperty("floorName", ref.floorName)
        addProperty("x", ref.point.x.toDouble())
        addProperty("y", ref.point.y.toDouble())
        addProperty("measuredAt", isoUtc(measuredAt))
    }

    private fun networkRow(ref: StopAndGoSurveyRef, test: NetworkTestResult) = JsonObject().apply {
        position(ref, test.measuredAt)
        addProperty("name", test.name)
        addProperty("alias", test.alias ?: test.name)
        addProperty("category", test.category ?: TargetCategory.INTERNAL)
        addProperty("type", test.type)
        addProperty("target", test.target)
        addProperty("method", test.method)
        addProperty("ok", test.ok)
        addProperty("networkId", test.networkId)
        addProperty("associationSource", test.associationSource)
        addProperty("ssid", test.ssid)
        addProperty("bssid", test.bssid)
        addProperty("bssidEnd", test.bssidEnd)
        addProperty("associationChange", test.associationChange)
        addProperty("rssi", test.rssi)
        addProperty("dnsServer", test.dnsServer)
        addProperty("resolvedAddress", test.resolvedAddress)
        addProperty("dnsMs", test.dnsMs)
        addProperty("connectMs", test.connectMs)
        addProperty("tlsMs", test.tlsMs)
        addProperty("ttfbMs", test.ttfbMs)
        addProperty("downloadMs", test.downloadMs)
        addProperty("totalMs", test.totalMs)
        addProperty("status", test.status)
        addProperty("bytes", test.bytes)
        addProperty("sent", test.sent)
        addProperty("received", test.received)
        addProperty("lossPct", test.lossPct)
        addProperty("rttMinMs", test.rttMinMs)
        addProperty("rttAvgMs", test.rttAvgMs)
        addProperty("rttMaxMs", test.rttMaxMs)
        addProperty("jitterMs", test.jitterMs)
        addProperty("errorCode", test.errorCode)
        addProperty("error", test.error)
    }

    private fun scriptRow(ref: StopAndGoSurveyRef, run: ScriptRun, index: Int, step: ScriptStepResult) = JsonObject().apply {
        position(ref, run.measuredAt)
        addProperty("script", run.name)
        addProperty("stepIndex", index + 1)
        addProperty("step", step.name)
        addProperty("method", step.method)
        addProperty("host", step.host)
        addProperty("path", step.path)
        addProperty("status", step.status)
        addProperty("ok", step.ok)
        addProperty("ssid", run.ssid)
        addProperty("bssidStart", run.bssidStart)
        addProperty("bssidEnd", run.bssidEnd)
        addProperty("rssi", run.rssi)
        addProperty("dnsMs", step.dnsMs)
        addProperty("connectMs", step.connectMs)
        addProperty("tlsMs", step.tlsMs)
        addProperty("ttfbMs", step.ttfbMs)
        addProperty("downloadMs", step.downloadMs)
        addProperty("totalMs", step.totalMs)
        addProperty("bytes", step.bytes)
        addProperty("error", step.error ?: run.error)
    }

    private fun row(ref: StopAndGoSurveyRef, test: ThroughputTestResult) = JsonObject().apply {
        position(ref, test.measuredAt)
        addProperty("target", test.target)
        addProperty("targetId", test.targetId)
        addProperty("category", test.category)
        addProperty("method", test.method)
        addProperty("durationSeconds", test.durationSeconds)
        addProperty("networkId", test.networkId)
        addProperty("networkSelection", test.networkSelection)
        addProperty("associationSource", test.associationSource)
        addProperty("ssid", test.ssid)
        addProperty("ssidEnd", test.ssidEnd)
        addProperty("bssidStart", test.bssidStart)
        addProperty("bssidEnd", test.bssidEnd)
        addProperty("associationChange", test.associationChange)
        addProperty("resolvedAddress", test.resolvedAddress)
        addProperty("downloadEndpoint", test.downloadEndpoint)
        addProperty("uploadEndpoint", test.uploadEndpoint)
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
