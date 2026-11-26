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
    /** 2: one row per active run (runId/pointId/scanId, run and phase statuses); see the spec history. */
    const val VERSION = 2
    private val ROAM_TYPES = setOf(RoamingEvent.BSSID_CHANGE, RoamingEvent.SSID_CHANGE, RoamingEvent.ROAM)

    fun hasMeasurements(snapshot: ProjectExportSnapshot): Boolean = snapshot.floors.any { floor ->
        floor.survey.activeRuns.isNotEmpty() ||
            floor.survey.continuousScanSessions.any { !it.importedFromEsx && it.roaming != null }
    }

    /** Where a run's point is: exact floor-plan pixels plus the survey of the run's scan in this ESX, if any. */
    private class Position(val surveyId: String?, val floorPlanId: String, val floorName: String, val point: ScanPoint)

    fun build(report: EkahauReport?, esxFileName: String, generatedAt: Long, snapshot: ProjectExportSnapshot? = null): JsonObject {
        val rows = JsonArray()
        val networkRows = JsonArray()
        val scriptRows = JsonArray()
        val runRows = JsonArray()
        val surveyByScan = report?.stopAndGoSurveys.orEmpty().mapNotNull { ref -> ref.point.scanId?.let { it to ref.surveyId } }.toMap()
        snapshot?.floors.orEmpty().forEach { floor ->
            val points = floor.survey.scanPoints
            floor.survey.activeRuns.forEach { run ->
                // The run's own scan, else any scan of the same physical point (same coordinates).
                val point = points.firstOrNull { it.scanId == run.scanId } ?: points.firstOrNull { it.pointId == run.pointId }
                    ?: return@forEach
                val position = Position(run.scanId?.let(surveyByScan::get), floor.esxFloorPlanId, floor.name, point)
                runRows.add(runRow(position, run))
                val results = run.results ?: return@forEach
                results.tests.forEach { test -> rows.add(row(position, run, test)) }
                results.networkTests.orEmpty().forEach { test -> networkRows.add(networkRow(position, run, test)) }
                results.scripts.orEmpty().forEach { script ->
                    script.steps.forEachIndexed { index, step -> scriptRows.add(scriptRow(position, run, script, index, step)) }
                }
            }
        }
        return JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            addProperty("generatedAt", isoUtc(generatedAt))
            addProperty("esxFile", esxFileName)
            add("activeRuns", runRows)
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
        addProperty("timeBase", ref.session.timeBase ?: "wall-clock")
        addProperty("schema", log.schema ?: 1)
        addProperty("target", log.target)
        addProperty("intervalMs", log.intervalMillis)
        addProperty("requestedIntervalMs", log.intervalMillis)
        addProperty("observedMedianIntervalMs", log.observedMedianIntervalMs)
        addProperty("observedMaxIntervalMs", log.observedMaxIntervalMs)
        addProperty("probes", log.probes)
        addProperty("lost", log.lost)
        addProperty("lossPct", if (log.probes > 0) round1(log.lost * 100.0 / log.probes) else null)
        addProperty("avgRttMs", log.avgRttMs)
        addProperty("connected", log.connected)
        addProperty("refused", log.refused)
        addProperty("timeouts", log.timeouts)
        addProperty("unreachable", log.unreachable)
        addProperty("resolutionFailures", log.resolutionFailures)
        addProperty("invalidTarget", log.invalidTarget)
        addProperty("noWifi", log.noWifi)
        addProperty("samplingGaps", log.samplingGaps)
        addProperty("roams", log.events.count { it.type in ROAM_TYPES })
        addProperty("outages", log.events.count { it.type !in ROAM_TYPES })
        listOf(RoamingEvent.BSSID_CHANGE, RoamingEvent.SSID_CHANGE, RoamingEvent.ASSOCIATION_LOST, RoamingEvent.REACHABILITY_LOSS)
            .forEach { type -> addProperty(type, log.events.count { it.type == type }) }
    }

    /**
     * Placed on the marked route by time only when at least two waypoints span the event time;
     * the interpolated position is approximate. Otherwise the event has no coordinates.
     */
    private fun roamingEventRow(ref: ContinuousSurveyRef, event: RoamingEvent) = JsonObject().apply {
        val route = ref.session.waypoints.sortedBy(RoutePointWrapper::timestamp)
        val placeable = route.size >= 2 && event.atMillis in route.first().timestamp..route.last().timestamp
        val position = if (placeable) SurveyGuide.positionAt(route, event.atMillis) else null
        fun at(relative: Long?) = relative?.let { isoUtc(ref.session.startTime + it) }
        addProperty("surveyId", ref.surveyId)
        addProperty("floorPlanId", ref.floorPlanId)
        addProperty("floorName", ref.floorName)
        addProperty("x", position?.first?.toDouble())
        addProperty("y", position?.second?.toDouble())
        addProperty("positionApproximate", if (position != null) true else null)
        addProperty("at", at(event.atMillis))
        addProperty("type", event.type)
        addProperty("fromSsid", event.fromSsid)
        addProperty("toSsid", event.toSsid)
        addProperty("fromBssid", event.fromBssid)
        addProperty("toBssid", event.toBssid)
        addProperty("fromRssi", event.fromRssi)
        addProperty("toRssi", event.toRssi)
        addProperty("lastReachableAt", at(event.lastReachableAtMillis))
        addProperty("firstReachableAt", at(event.firstReachableAtMillis))
        addProperty("failedAttempts", event.failedAttempts)
        addProperty("outageObserved", event.outageObserved)
        addProperty("outageLowerBoundMs", event.outageLowerBoundMs)
        addProperty("outageUpperBoundMs", event.outageUpperBoundMs)
        addProperty("open", event.open)
        addProperty("samplingGap", event.samplingGap)
        addProperty("scanOverlap", event.scanOverlap)
        // Format-1 fields (logs recorded by fork release 4).
        addProperty("outageMs", event.outageMillis)
        addProperty("lostProbes", event.lostProbes)
        addProperty("duringScan", event.duringScan)
    }

    /** Position and run fields shared by every row type. */
    private fun JsonObject.position(position: Position, run: ActiveRun, measuredAt: Long) {
        addProperty("surveyId", position.surveyId)
        addProperty("floorPlanId", position.floorPlanId)
        addProperty("floorName", position.floorName)
        addProperty("pointId", run.pointId)
        addProperty("scanId", run.scanId)
        addProperty("runId", run.runId)
        addProperty("x", position.point.x.toDouble())
        addProperty("y", position.point.y.toDouble())
        addProperty("measuredAt", isoUtc(measuredAt))
    }

    private fun runRow(position: Position, run: ActiveRun) = JsonObject().apply {
        position(position, run, run.startedAt)
        addProperty("startedAt", isoUtc(run.startedAt))
        addProperty("endedAt", run.endedAt?.let(::isoUtc))
        addProperty("status", run.status)
        addProperty("statusReason", run.statusReason)
        addProperty("profile", run.profile)
        add("phases", JsonArray().apply {
            run.phases.forEach { phase ->
                add(JsonObject().apply {
                    addProperty("key", phase.key)
                    addProperty("label", phase.label)
                    addProperty("status", phase.status)
                    addProperty("errorCode", phase.errorCode)
                    addProperty("endedAt", phase.endedAt?.let(::isoUtc))
                })
            }
        })
    }

    private fun networkRow(position: Position, run: ActiveRun, test: NetworkTestResult) = JsonObject().apply {
        position(position, run, test.measuredAt)
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
        addProperty("rttKind", when (test.method) {
            "icmp" -> "icmp-echo"
            "tcp" -> "tcp-connect"
            else -> null
        })
        addProperty("rttMinMs", test.rttMinMs)
        addProperty("rttAvgMs", test.rttAvgMs)
        addProperty("rttMaxMs", test.rttMaxMs)
        addProperty("jitterMs", test.jitterMs)
        addProperty("errorCode", test.errorCode)
        addProperty("error", test.error)
    }

    private fun scriptRow(position: Position, activeRun: ActiveRun, run: ScriptRun, index: Int, step: ScriptStepResult) = JsonObject().apply {
        position(position, activeRun, run.measuredAt)
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
        addProperty("errorCode", step.errorCode)
        addProperty("error", step.error ?: run.error)
    }

    private fun row(position: Position, run: ActiveRun, test: ThroughputTestResult) = JsonObject().apply {
        position(position, run, test.measuredAt)
        addProperty("runStatus", run.status)
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
        addProperty("latencyFailures", test.latencyFailures)
        addProperty("latencyMethod", test.latencyMethod)
        addProperty("latencyStatus", test.latencyStatus)
        addProperty("latencyErrorCode", test.latencyErrorCode)
        addProperty("downloadStatus", test.downloadStatus)
        addProperty("downloadErrorCode", test.downloadErrorCode)
        addProperty("downBytes", test.downBytes)
        addProperty("downSeconds", test.downSeconds)
        addProperty("uploadStatus", test.uploadStatus)
        addProperty("uploadErrorCode", test.uploadErrorCode)
        addProperty("upBytes", test.upBytes)
        addProperty("upSeconds", test.upSeconds)
        addProperty("deviceModel", test.deviceModel)
        addProperty("androidVersion", test.androidVersion)
        addProperty("error", test.error)
    }

    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
