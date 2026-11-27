package com.mpedreira.nimwifi

import java.time.Instant

/**
 * Plain-language survey report per floor and SSID, written next to the ESX and the JSON
 * (`wiprober-report.html`). Built only from nimWifi's own data: imported ESX measurements stay
 * opaque, so imported points count as "no local measurement".
 */
object SurveyReport {
    const val FILE_NAME = "wiprober-report.html"

    /** Which runs feed the service figures. The JSON always has every run. */
    enum class RunSelection { LATEST_PER_POINT, ALL }

    data class Metric(
        val category: String,
        val alias: String,
        val measure: String,
        val unit: String,
        val method: String?,
        val samples: Int,
        val failures: Int,
        val median: Double?,
        val min: Double?,
        val max: Double?
    )

    data class Event(val kind: String, val at: Long?, val from: String?, val to: String?, val detail: String?)

    data class SsidSection(
        val ssid: String,
        val points: List<PointCoverage>,
        val metrics: List<Metric>,
        val apChanges: List<Event>,
        val outages: List<Event>
    ) {
        fun count(state: String) = points.count { it.state == state }
    }

    data class FloorSection(val floorName: String, val runs: Int, val remeasuredPoints: Int, val ssids: List<SsidSection>)

    data class Model(
        val title: String,
        val generatedAt: Long,
        val selection: RunSelection,
        val weakDbm: Int,
        val profile: String = SignalProfile.DEFAULT.id,
        val floors: List<FloorSection>
    )

    fun build(
        snapshot: ProjectExportSnapshot,
        generatedAt: Long,
        chosenSsid: String = "",
        selection: RunSelection = RunSelection.LATEST_PER_POINT,
        weakDbm: Int = SsidCoverage.DEFAULT_WEAK_DBM,
        profile: String = SignalProfile.DEFAULT.id
    ): Model = Model(
        profile = profile,
        title = snapshot.title,
        generatedAt = generatedAt,
        selection = selection,
        weakDbm = weakDbm,
        floors = snapshot.floors.map { floor ->
            val survey = floor.survey
            val runs = select(survey.activeRuns, selection)
            FloorSection(
                floorName = floor.name,
                runs = survey.activeRuns.size,
                remeasuredPoints = (survey.activeRuns.groupBy(ActiveRun::pointId).filterValues { it.size > 1 }.keys +
                    survey.scanPoints.filter(SsidCoverage::isValidScan).groupBy(ScanPoint::pointId).filterValues { it.size > 1 }.keys).size,
                ssids = ssidsFor(survey, chosenSsid).map { ssid -> section(ssid, survey, runs, weakDbm) }
            )
        }
    )

    /** The chosen SSID, the SSIDs associated during active tests, else the three most widely detected. */
    fun ssidsFor(survey: ProjectSnapshot, chosen: String): List<String> {
        val associated = survey.activeRuns.mapNotNull { it.results }.flatMap { results ->
            results.tests.mapNotNull { it.ssid } + results.networkTests.orEmpty().mapNotNull { it.ssid } +
                results.scripts.orEmpty().mapNotNull { it.ssid }
        }
        val list = (listOfNotNull(chosen.trim().ifEmpty { null }) + associated).filter(String::isNotEmpty).distinct()
        return list.ifEmpty { SsidCoverage.ssids(survey.scanPoints, survey.continuousScanSessions).take(3) }
    }

    fun select(runs: List<ActiveRun>, selection: RunSelection): List<ActiveRun> = when (selection) {
        RunSelection.ALL -> runs
        RunSelection.LATEST_PER_POINT -> runs.groupBy(ActiveRun::pointId).values.map { it.maxBy(ActiveRun::startedAt) }
    }

    private fun section(ssid: String, survey: ProjectSnapshot, runs: List<ActiveRun>, weakDbm: Int): SsidSection {
        val results = runs.mapNotNull { it.results }
        val metrics = mutableListOf<Metric>()
        fun metric(category: String, alias: String, measure: String, unit: String, method: String?, values: List<Double?>, failures: Int) {
            val ok = values.filterNotNull().sorted()
            if (ok.isEmpty() && failures == 0) return
            metrics += Metric(
                category, alias, measure, unit, method, ok.size, failures,
                ok.takeIf { it.isNotEmpty() }?.let { round1(if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2) },
                ok.firstOrNull(), ok.lastOrNull()
            )
        }
        results.flatMap { it.tests }.filter { it.ssid == ssid }
            .groupBy { (it.category ?: if (it.method == ThroughputTestResult.METHOD_IPERF3_TCP) TargetCategory.PRIVATE_IPERF else TargetCategory.INTERNET) to it.target }
            .forEach { (key, tests) ->
                val (category, alias) = key
                metric(category, alias, "download", "Mbit/s", tests.first().method, tests.map { it.downMbps }, tests.count { it.downMbps == null && (it.downloadErrorCode != null || it.error != null) })
                metric(category, alias, "upload", "Mbit/s", tests.first().method, tests.map { it.upMbps }, tests.count { it.upMbps == null && (it.uploadErrorCode != null || it.error != null) })
                metric(category, alias, "latency", "ms", tests.first().latencyMethod ?: "tcp-connect", tests.map { it.latencyMs }, tests.count { it.latencyMs == null && (it.latencyErrorCode != null || it.error != null) })
            }
        results.flatMap { it.networkTests.orEmpty() }.filter { it.ssid == ssid }
            .groupBy { (it.category ?: TargetCategory.INTERNAL) to (it.alias ?: it.name) }
            .forEach { (key, tests) ->
                val (category, alias) = key
                val type = tests.first().type
                val (measure, values) = when (type) {
                    NetworkTest.DNS -> "dns lookup" to tests.map { it.dnsMs }
                    NetworkTest.PING -> "round trip" to tests.map { it.rttAvgMs }
                    NetworkTest.TCP -> "tcp connect" to tests.map { it.connectMs }
                    else -> "http request" to tests.map { it.totalMs }
                }
                metric(category, alias, measure, "ms", tests.firstNotNullOfOrNull { it.method } ?: type, values.zip(tests) { v, t -> v.takeIf { t.ok } }, tests.count { !it.ok })
            }
        results.flatMap { it.scripts.orEmpty() }.filter { it.ssid == ssid }
            .groupBy { it.alias ?: it.name }
            .forEach { (alias, scriptRuns) ->
                metric(
                    TargetCategory.INTERNAL, alias, "script", "ms", "http-script",
                    scriptRuns.map { run -> run.steps.takeIf { s -> s.isNotEmpty() && s.all { it.ok } }?.sumOf { it.totalMs ?: 0.0 } },
                    scriptRuns.count { run -> run.steps.isEmpty() || run.steps.any { !it.ok } }
                )
            }

        val apChanges = mutableListOf<Event>()
        val outages = mutableListOf<Event>()
        results.flatMap { it.tests }.filter { it.ssid == ssid && it.associationChange in setOf(AssociationChange.BSSID_CHANGED, AssociationChange.SSID_CHANGED) }
            .forEach { apChanges += Event(it.associationChange!!, it.measuredAt, it.bssidStart, it.bssidEnd, it.target) }
        survey.continuousScanSessions.filterNot { it.importedFromEsx }.forEach { session ->
            session.roaming?.events.orEmpty().filter { it.fromSsid == ssid || it.toSsid == ssid || (it.fromSsid == null && it.toSsid == null) }.forEach { event ->
                val at = session.startTime + event.atMillis
                when (event.type) {
                    RoamingEvent.BSSID_CHANGE, RoamingEvent.SSID_CHANGE, RoamingEvent.ROAM ->
                        apChanges += Event(event.type, at, event.fromBssid, event.toBssid, if (event.outageObserved == true) "outage observed" else null)
                    else -> outages += Event(
                        event.type, at, event.fromBssid, event.toBssid,
                        when {
                            event.open == true -> "not recovered"
                            event.outageLowerBoundMs != null -> "${event.outageLowerBoundMs}–${event.outageUpperBoundMs ?: "?"} ms"
                            else -> null
                        }
                    )
                }
            }
        }
        return SsidSection(ssid, SsidCoverage.points(survey.scanPoints, survey.plannedPoints, ssid, weakDbm), metrics, apChanges, outages)
    }

    fun html(model: Model): String = buildString {
        fun e(text: Any?): String = text?.toString().orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun at(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).toString().substringBefore('.').replace('T', ' ') + " UTC" }.orEmpty()
        append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>").append(e(model.title)).append(" – Wi-Fi survey report</title><style>")
        append("body{font-family:system-ui,sans-serif;margin:16px;max-width:1000px;color:#1c1c1c;background:#fff}")
        append("table{border-collapse:collapse;width:100%;margin:8px 0 16px}th,td{border:1px solid #ccc;padding:4px 6px;text-align:left;font-size:14px}")
        append("th{background:#f0f0f0}.weak{color:#b35c00}.bad{color:#b00020}.ok{color:#1b6e20}.muted{color:#666;font-size:13px}")
        append("</style></head><body>")
        append("<h1>").append(e(model.title)).append("</h1>")
        append("<p class=\"muted\">Generated ").append(e(at(model.generatedAt))).append(". Service figures use ")
        append(if (model.selection == RunSelection.ALL) "every run" else "the latest run at each point")
        append(". Weak signal (profile ").append(e(model.profile)).append("): strongest AP of the SSID below ").append(model.weakDbm).append(" dBm in the latest valid scan. ")
        append("Signal values are the strongest AP of each SSID seen by the passive scan, not necessarily the AP the phone was connected to during the tests. ")
        append("Points imported from an ESX have no local measurement (their Ekahau data is kept untouched in the .esx).</p>")
        model.floors.forEach { floor ->
            append("<h2>Floor: ").append(e(floor.floorName)).append("</h2>")
            append("<p class=\"muted\">").append(floor.runs).append(" active test runs; ").append(floor.remeasuredPoints).append(" points measured more than once.</p>")
            if (floor.ssids.isEmpty()) append("<p>No local Wi-Fi scans on this floor.</p>")
            floor.ssids.forEach { section ->
                append("<h3>SSID: ").append(e(section.ssid)).append("</h3><ul>")
                append("<li class=\"ok\">Good signal: ").append(section.count(PointState.OK)).append(" points</li>")
                append("<li class=\"weak\">Weak signal measured: ").append(section.count(PointState.WEAK)).append(" points</li>")
                append("<li class=\"bad\">SSID not detected in a valid scan: ").append(section.count(PointState.SSID_NOT_DETECTED)).append(" points</li>")
                append("<li>No local measurement: ").append(section.count(PointState.NOT_MEASURED)).append(" points</li>")
                append("<li>AP changes: ").append(section.apChanges.size).append("; outages: ").append(section.outages.size).append("</li></ul>")
                val attention = section.points.filter { it.state != PointState.OK }.sortedBy { it.state }
                if (attention.isNotEmpty()) {
                    append("<table><tr><th>Point</th><th>State</th><th>Strongest AP (scan)</th><th>dBm</th><th>x, y (px)</th></tr>")
                    attention.forEach { p ->
                        append("<tr><td>").append(e(p.label)).append("</td><td>").append(e(stateLabel(p.state))).append("</td><td>")
                            .append(e(p.strongestBssid)).append("</td><td>").append(e(p.strongestRssi)).append("</td><td>")
                            .append(e("%.0f, %.0f".format(java.util.Locale.ROOT, p.x, p.y))).append("</td></tr>")
                    }
                    append("</table>")
                }
                if (section.metrics.isNotEmpty()) {
                    append("<table><tr><th>Service</th><th>Type</th><th>Measure</th><th>Median</th><th>Min–max</th><th>Unit</th><th>Method</th><th>Samples</th><th>Failures</th></tr>")
                    section.metrics.sortedWith(compareBy({ it.category }, { it.alias })).forEach { m ->
                        append("<tr><td>").append(e(m.alias)).append("</td><td>").append(e(categoryLabel(m.category))).append("</td><td>")
                            .append(e(m.measure)).append("</td><td>").append(e(m.median)).append("</td><td>")
                            .append(if (m.min != null) e("${m.min}–${m.max}") else "").append("</td><td>").append(e(m.unit)).append("</td><td>")
                            .append(e(m.method)).append("</td><td>").append(m.samples).append("</td><td>").append(m.failures).append("</td></tr>")
                    }
                    append("</table>")
                }
                (section.apChanges.map { "AP change" to it } + section.outages.map { "Outage" to it }).takeIf { it.isNotEmpty() }?.let { events ->
                    append("<table><tr><th>Event</th><th>Kind</th><th>When</th><th>From</th><th>To</th><th>Detail</th></tr>")
                    events.sortedBy { it.second.at }.forEach { (title, ev) ->
                        append("<tr><td>").append(title).append("</td><td>").append(e(ev.kind)).append("</td><td>").append(e(at(ev.at)))
                            .append("</td><td>").append(e(ev.from)).append("</td><td>").append(e(ev.to)).append("</td><td>").append(e(ev.detail)).append("</td></tr>")
                    }
                    append("</table>")
                }
            }
        }
        append("</body></html>")
    }

    fun stateLabel(state: String) = when (state) {
        PointState.WEAK -> "Weak signal measured"
        PointState.SSID_NOT_DETECTED -> "SSID not detected in a valid scan"
        PointState.NOT_MEASURED -> "No local measurement"
        else -> "Good signal"
    }

    private fun categoryLabel(category: String) = when (category) {
        TargetCategory.PRIVATE_IPERF -> "Private iPerf3 server"
        TargetCategory.INTERNET -> "Internet"
        else -> "Internal service"
    }
}
