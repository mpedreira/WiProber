package com.mpedreira.nimwifi

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
            floor.survey.continuousScanSessions.any { !it.importedFromEsx && it.roamingLogs.isNotEmpty() }
    }

    /** Where a run's point is: exact floor-plan pixels plus the survey of the run's scan in this ESX, if any. */
    private class Position(val surveyId: String?, val floorPlanId: String, val floorName: String, val x: Float, val y: Float)

    fun build(report: EkahauReport?, esxFileName: String, generatedAt: Long, snapshot: ProjectExportSnapshot? = null): JsonObject {
        val rows = JsonArray()
        val networkRows = JsonArray()
        val scriptRows = JsonArray()
        val scriptRunRows = JsonArray()
        val runRows = JsonArray()
        val surveyByScan = report?.stopAndGoSurveys.orEmpty().mapNotNull { ref -> ref.point.scanId?.let { it to ref.surveyId } }.toMap()
        snapshot?.floors.orEmpty().forEach { floor ->
            val points = floor.survey.scanPoints
            floor.survey.activeRuns.forEach { run ->
                // The run's own scan, else any scan of the same physical point (same coordinates),
                // else the position saved with the run (imported or planned point).
                val point = points.firstOrNull { it.scanId == run.scanId } ?: points.firstOrNull { it.pointId == run.pointId }
                val x = point?.x ?: run.x ?: floor.survey.plannedPoints.firstOrNull { it.pointId == run.pointId }?.x ?: return@forEach
                val y = point?.y ?: run.y ?: floor.survey.plannedPoints.firstOrNull { it.pointId == run.pointId }?.y ?: return@forEach
                val position = Position(run.scanId?.let(surveyByScan::get), floor.esxFloorPlanId, floor.name, x, y)
                runRows.add(runRow(position, run))
                val results = run.results ?: return@forEach
                results.tests.forEach { test -> rows.add(row(position, run, test)) }
                results.networkTests.orEmpty().forEach { test -> networkRows.add(networkRow(position, run, test)) }
                results.scripts.orEmpty().forEach { script ->
                    scriptRunRows.add(scriptRunRow(position, run, script))
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
            add("scriptRuns", scriptRunRows)
            add("scriptSteps", scriptRows)
            // Every local Continuous route, with how it was recorded (stretches, interruptions).
            add("continuousSessions", JsonArray().apply {
                report?.continuousSurveys.orEmpty().forEach { ref -> add(continuousSessionRow(ref)) }
            })
            add("roamingSessions", JsonArray().apply {
                // rc.2 · 4: one row per probe; a resumed route with two probes has two rows (`stretch`).
                report?.continuousSurveys.orEmpty().forEach { ref ->
                    ref.session.roamingLogs.forEachIndexed { index, log -> add(roamingSessionRow(ref, log).also { stretch(it, ref, index, log) }) }
                }
            })
            add("roamingIncidents", JsonArray().apply {
                report?.continuousSurveys.orEmpty().forEach { ref ->
                    ref.session.roamingLogs.forEachIndexed { index, log -> log.incidents.orEmpty().forEach { incident ->
                        add(JsonObject().apply {
                            addProperty("surveyId", ref.surveyId)
                            stretch(this, ref, index, log)
                            addProperty("incidentId", incident.id)
                            addProperty("start", isoUtc(ref.session.startTime + incident.startMs))
                            addProperty("end", incident.endMs?.let { isoUtc(ref.session.startTime + it) })
                            addProperty("events", incident.events)
                            addProperty("failedAttempts", incident.failedAttempts)
                            addProperty("lowerBoundMs", incident.lowerBoundMs)
                            addProperty("upperBoundMs", incident.upperBoundMs)
                            addProperty("open", incident.open)
                        })
                    } }
                }
            })
            // Optional diagnostic trace (roaming.trace): bounded, alias only, no hosts.
            add("roamingTrace", JsonArray().apply {
                report?.continuousSurveys.orEmpty().forEach { ref ->
                    ref.session.roamingLogs.forEachIndexed { index, log -> log.trace.orEmpty().forEach { attempt ->
                        add(JsonObject().apply {
                            addProperty("surveyId", ref.surveyId)
                            stretch(this, ref, index, log)
                            addProperty("startMs", attempt.startMs)
                            addProperty("endMs", attempt.endMs)
                            addProperty("start", isoUtc(ref.session.startTime + attempt.startMs))
                            addProperty("result", attempt.result)
                            addProperty("ssid", attempt.ssid)
                            addProperty("bssid", attempt.bssid)
                            addProperty("rssi", attempt.rssi)
                            // rc.2 · 4: the probe's own RTT (ICMP `time=`) and the attempt's full duration.
                            addProperty("rttMs", attempt.rttMs)
                            addProperty("durationMs", attempt.endMs - attempt.startMs)
                        })
                    } }
                }
            })
            add("roamingEvents", JsonArray().apply {
                report?.continuousSurveys.orEmpty().forEach { ref ->
                    ref.session.roamingLogs.forEachIndexed { index, log ->
                        log.events.forEach { add(roamingEventRow(ref, it).also { row -> stretch(row, ref, index, log) }) }
                    }
                }
            })
        }
    }

    fun toBytes(json: JsonObject): ByteArray =
        GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(json)
            .toByteArray(StandardCharsets.UTF_8)

    /** Writes the export bundle: the ESX archive untouched plus the performance file and the readable report. */
    fun writeBundle(output: OutputStream, esxFileName: String, esxArchive: File, performanceJson: ByteArray, reportHtml: ByteArray? = null) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(esxFileName))
            esxArchive.inputStream().use { it.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(FILE_NAME))
            zip.write(performanceJson)
            zip.closeEntry()
            reportHtml?.let {
                zip.putNextEntry(ZipEntry(SurveyReport.FILE_NAME))
                zip.write(it)
                zip.closeEntry()
            }
        }
    }

    private fun continuousSessionRow(ref: ContinuousSurveyRef) = JsonObject().apply {
        val session = ref.session
        addProperty("surveyId", ref.surveyId)
        addProperty("floorPlanId", ref.floorPlanId)
        addProperty("floorName", ref.floorName)
        addProperty("startedAt", isoUtc(session.startTime))
        addProperty("endedAt", isoUtc(session.endTime))
        addProperty("timeBase", session.timeBase ?: "wall-clock")
        addProperty("waypoints", session.waypoints.size)
        addProperty("scans", session.scanResults.size)
        addProperty("segments", session.segments ?: 1)
        addProperty("recoveredAfterInterruption", session.recoveredAfterInterruption == true)
        add("interruptions", JsonArray().apply {
            session.interruptions.orEmpty().forEach { gap ->
                add(JsonObject().apply {
                    addProperty("from", isoUtc(session.startTime + gap.fromMs))
                    addProperty("to", isoUtc(session.startTime + gap.toMs))
                    addProperty("fromMs", gap.fromMs)
                    addProperty("toMs", gap.toMs)
                })
            }
        })
        addProperty("roamingProbe", session.roamingLogs.isNotEmpty())
        session.scanWindows?.let { windows ->
            addProperty("rfScanAttempts", windows.size)
            addProperty("rfScansSucceeded", windows.count { it.outcome == ScanWindow.SUCCESS })
            addProperty("rfScansFailed", windows.count { it.outcome == ScanWindow.FAILED })
            addProperty("rfScansCancelled", windows.count { it.outcome == ScanWindow.CANCELLED })
        }
        // Spans without RF observation (pauses, throttling, failures): never read as "no signal".
        add("rfGaps", JsonArray().apply {
            session.rfGaps.orEmpty().forEach { gap ->
                add(JsonObject().apply {
                    addProperty("from", isoUtc(session.startTime + gap.fromMs))
                    addProperty("to", isoUtc(session.startTime + gap.toMs))
                    addProperty("durationMs", gap.toMs - gap.fromMs)
                })
            }
        })
    }

    /** rc.2 · 4: which probe of a resumed route a row belongs to (only with more than one). */
    private fun stretch(row: JsonObject, ref: ContinuousSurveyRef, index: Int, log: RoamingLog) {
        if (ref.session.roamingLogs.size < 2) return
        row.addProperty("stretch", index + 1)
        log.stretchStartMs?.let { row.addProperty("stretchStart", isoUtc(ref.session.startTime + it)) }
        log.stretchEndMs?.let { row.addProperty("stretchEnd", isoUtc(ref.session.startTime + it)) }
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
        // rc.2 · 4: ICMP `time=` for icmp-echo (null without a valid reply), the handshake for tcp-connect.
        addProperty("avgRttMs", log.avgRttMs)
        addProperty("avgAttemptMs", log.avgAttemptMs)
        addProperty("probeErrors", log.probeErrors)
        addProperty("connected", log.connected)
        addProperty("refused", log.refused)
        addProperty("timeouts", log.timeouts)
        addProperty("unreachable", log.unreachable)
        addProperty("resolutionFailures", log.resolutionFailures)
        addProperty("invalidTarget", log.invalidTarget)
        addProperty("noWifi", log.noWifi)
        addProperty("samplingGaps", log.samplingGaps)
        addProperty("targetAlias", log.targetAlias)
        addProperty("method", log.method)
        // TCP denominator: only attempts made against a valid target (mejora 12).
        addProperty("tcpAttempts", log.tcpAttempts)
        addProperty("tcpLossPct", log.tcpLossPct)
        addProperty("incidents", log.incidents?.size)
        add("unobserved", JsonArray().apply {
            log.unobserved.orEmpty().forEach { gap ->
                add(JsonObject().apply {
                    addProperty("from", isoUtc(ref.session.startTime + gap.fromMs)); addProperty("to", isoUtc(ref.session.startTime + gap.toMs))
                    addProperty("durationMs", gap.toMs - gap.fromMs)
                })
            }
        })
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
        addProperty("scanOverlapOutcomes", event.scanOverlapOutcomes?.joinToString(","))
        addProperty("incidentId", event.incidentId)
        addProperty("durationEvidence", event.durationEvidence)
        addProperty("atMs", event.atMillis)
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
        addProperty("x", position.x.toDouble())
        addProperty("y", position.y.toDouble())
        addProperty("measuredAt", isoUtc(measuredAt))
    }

    private fun runRow(position: Position, run: ActiveRun) = JsonObject().apply {
        position(position, run, run.startedAt)
        addProperty("startedAt", isoUtc(run.startedAt))
        addProperty("endedAt", run.endedAt?.let(::isoUtc))
        addProperty("status", run.status)
        addProperty("statusReason", run.statusReason)
        addProperty("profile", run.profile)
        addProperty("budgetMs", run.budgetMillis)
        addProperty("expectedSsid", run.expectedSsid)
        addProperty("observedSsid", run.observedSsid)
        addProperty("networkVerification", run.networkVerification)
        addProperty("networkDecision", run.networkDecision)
        addProperty("networkId", run.networkId)
        // The run's status is separate from what its phases produced.
        add("phaseSummary", JsonObject().apply { run.phaseSummary.toSortedMap().forEach { (status, n) -> addProperty(status, n) } })
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
        addProperty("redirectLocation", test.redirectLocation)
        addProperty("seriesId", Series.networkTest(test).id)
        addProperty("seriesComparable", Series.networkTest(test).comparable)
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

    /** One row per script per run, also when it was skipped (no Wi-Fi) or failed before its first step. */
    private fun scriptRunRow(position: Position, activeRun: ActiveRun, run: ScriptRun) = JsonObject().apply {
        position(position, activeRun, run.measuredAt)
        val ok = run.steps.count { it.ok }
        addProperty("script", run.name)
        addProperty("scriptAlias", run.alias ?: run.name)
        addProperty("seriesId", Series.script(run).id)
        addProperty("seriesComparable", Series.script(run).comparable)
        addProperty("definitionHash", run.definitionHash)
        addProperty(
            "status",
            when {
                run.steps.isEmpty() && run.errorCode != null -> RunStatus.SKIPPED
                run.steps.isNotEmpty() && ok == run.steps.size && (run.stepsTotal == null || run.steps.size == run.stepsTotal) -> RunStatus.COMPLETED
                // Mejora 10: some steps passed, others failed or never ran (cancel, deadline).
                ok > 0 -> "partial"
                else -> RunStatus.FAILED
            }
        )
        addProperty("stepsTotal", run.stepsTotal ?: run.steps.size)
        addProperty("stepsPending", ((run.stepsTotal ?: run.steps.size) - run.steps.size).coerceAtLeast(0))
        addProperty("stepsRun", run.steps.size)
        addProperty("stepsOk", ok)
        addProperty("stepsFailed", run.steps.size - ok)
        addProperty("totalMs", run.steps.mapNotNull { it.totalMs }.takeIf { it.isNotEmpty() }?.sum()?.let(::round1))
        addProperty("ssid", run.ssid)
        addProperty("bssidStart", run.bssidStart)
        addProperty("bssidEnd", run.bssidEnd)
        addProperty("rssi", run.rssi)
        addProperty("errorCode", run.errorCode ?: run.steps.firstOrNull { !it.ok }?.errorCode)
        addProperty("error", run.error)
    }

    private fun scriptRow(position: Position, activeRun: ActiveRun, run: ScriptRun, index: Int, step: ScriptStepResult) = JsonObject().apply {
        position(position, activeRun, run.measuredAt)
        addProperty("script", run.name)
        addProperty("scriptAlias", run.alias ?: run.name)
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
        addProperty("redirectLocation", step.redirectLocation)
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
        // Mejora 9: stable series of each direction (join and compare by these, not by alias).
        addProperty("seriesDownload", Series.throughput(test, Series.DOWNLOAD).id)
        addProperty("seriesUpload", Series.throughput(test, Series.UPLOAD).id)
        addProperty("seriesLatency", Series.throughput(test, Series.LATENCY).id)
        addProperty("seriesComparable", Series.throughput(test, Series.DOWNLOAD).comparable)
        addProperty("downloadEndpointId", test.downloadEndpointId)
        addProperty("uploadEndpointId", test.uploadEndpointId)
        addProperty("downloadPrecheckAddress", test.downloadPrecheckAddress)
        addProperty("uploadPrecheckAddress", test.uploadPrecheckAddress)
        addProperty("uploadPrecheckErrorCode", test.uploadPrecheckErrorCode)
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
