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
        val max: Double?,
        /** Mejora 9: the series the figures belong to; only results of one series are combined. */
        val seriesId: String = "",
        val seriesLabel: String = "",
        val comparable: Boolean = true,
        /** Mejora 1: per point of this SSID, its last attempt and its last valid measurement. */
        val points: List<PointFact> = emptyList(),
        /** Mejora 10: attempts of the series in the selection by outcome (see [Outcome]). */
        val outcomes: Map<String, Int> = emptyMap()
    )

    /** Mejora 10: how one attempt of a series ended. */
    object Outcome {
        const val VALID = "valid"
        const val PARTIAL = "partial"
        const val FAILED = "failed"
        const val SKIPPED = "skipped"
        const val CANCELLED = "cancelled"
        const val INTERRUPTED = "interrupted"
        const val NOT_CONFIGURED = "not_configured"
        val ORDER = listOf(VALID, PARTIAL, FAILED, SKIPPED, CANCELLED, INTERRUPTED, NOT_CONFIGURED)
    }

    /** One attempt of a series at a point: which run, when, how it ended and its value (null = none). */
    data class Fact(val runId: String, val at: Long, val status: String, val value: Double?, val code: String?)

    data class PointFact(val pointId: String, val label: String, val lastAttempt: Fact, val lastValid: Fact?)

    /** A run that belongs to this SSID (by its results or, without results, by the network it observed). */
    data class RunFact(val runId: String, val pointLabel: String, val at: Long, val status: String, val reason: String?, val withValues: Boolean)

    data class Event(val kind: String, val at: Long?, val from: String?, val to: String?, val detail: String?)

    data class SsidSection(
        /** Null: results whose association (SSID) was never identified. */
        val ssid: String?,
        val points: List<PointCoverage>,
        val metrics: List<Metric>,
        val apChanges: List<Event>,
        val outages: List<Event>,
        val runs: List<RunFact> = emptyList()
    ) {
        fun count(state: String) = points.count { it.state == state }
    }

    data class FloorSection(
        val floorName: String,
        val runs: Int,
        val remeasuredPoints: Int,
        val ssids: List<SsidSection>,
        /** Mejora 10: results without an identified association, with their reasons. */
        val unknownAssociation: SsidSection? = null
    )

    data class Model(
        val title: String,
        val generatedAt: Long,
        val selection: RunSelection,
        val weakDbm: Int,
        val profile: String = SignalProfile.DEFAULT.id,
        val floors: List<FloorSection>,
        /** Mejora 1: what the selection covers (all runs, one run, an interval). */
        val selectionDetail: String? = null
    )

    fun build(
        snapshot: ProjectExportSnapshot,
        generatedAt: Long,
        chosenSsid: String = "",
        selection: RunSelection = RunSelection.LATEST_PER_POINT,
        weakDbm: Int = SsidCoverage.DEFAULT_WEAK_DBM,
        profile: String = SignalProfile.DEFAULT.id,
        /** Only this run (a pass), or only runs started inside [fromMs]..[toMs] (a campaign). */
        runId: String? = null,
        fromMs: Long? = null,
        toMs: Long? = null
    ): Model = Model(
        profile = profile,
        selectionDetail = listOfNotNull(runId?.let { "run $it" },
            if (fromMs != null || toMs != null) "runs from ${fromMs ?: "start"} to ${toMs ?: "end"}" else null).joinToString("; ").ifEmpty { null },
        title = snapshot.title,
        generatedAt = generatedAt,
        selection = selection,
        weakDbm = weakDbm,
        floors = snapshot.floors.map { floor ->
            val survey = floor.survey
            // Selection is applied per SSID and series in section(), never across SSIDs.
            val runs = survey.activeRuns.filter { run ->
                (runId == null || run.runId == runId) &&
                    (fromMs == null || run.startedAt >= fromMs) && (toMs == null || run.startedAt <= toMs)
            }
            FloorSection(
                floorName = floor.name,
                runs = survey.activeRuns.size,
                remeasuredPoints = (survey.activeRuns.groupBy(ActiveRun::pointId).filterValues { it.size > 1 }.keys +
                    survey.scanPoints.filter(SsidCoverage::isValidScan).groupBy(ScanPoint::pointId).filterValues { it.size > 1 }.keys).size,
                ssids = ssidsFor(survey, chosenSsid).map { ssid -> section(ssid, survey, runs, weakDbm, selection) },
                unknownAssociation = section(null, survey, runs, weakDbm, selection).takeIf { it.runs.isNotEmpty() }
            )
        }
    )

    /** The chosen SSID, the SSIDs associated during active tests, else the three most widely detected. */
    fun ssidsFor(survey: ProjectSnapshot, chosen: String): List<String> {
        val associated = survey.activeRuns.flatMap { run ->
            val results = run.results
            listOfNotNull(run.observedSsid) + results?.tests.orEmpty().mapNotNull { it.ssid } +
                results?.networkTests.orEmpty().mapNotNull { it.ssid } + results?.scripts.orEmpty().mapNotNull { it.ssid }
        }
        val list = (listOfNotNull(chosen.trim().ifEmpty { null }) + associated).filter(String::isNotEmpty).distinct()
        return list.ifEmpty { SsidCoverage.ssids(survey.scanPoints, survey.continuousScanSessions).take(3) }
    }

    /** One measurement of a series at a point in one run (value null = attempted without a value). */
    private class Entry(val series: Series, val pointId: String, val run: ActiveRun, val value: Double?, val status: String, val code: String?, val partial: Boolean = false)

    private fun outcome(e: Entry): String = when {
        e.value != null && (e.partial || e.code == ErrorCodes.PARTIAL_LOSS) -> Outcome.PARTIAL
        e.value != null -> Outcome.VALID
        e.partial -> Outcome.PARTIAL
        e.code == ErrorCodes.NOT_CONFIGURED -> Outcome.NOT_CONFIGURED
        e.status == RunStatus.SKIPPED -> Outcome.SKIPPED
        e.status == RunStatus.CANCELLED -> Outcome.CANCELLED
        e.status == RunStatus.INTERRUPTED -> Outcome.INTERRUPTED
        else -> Outcome.FAILED
    }

    private fun section(ssid: String?, survey: ProjectSnapshot, runs: List<ActiveRun>, weakDbm: Int, selection: RunSelection): SsidSection {
        val labels = SsidCoverage.labels(survey.scanPoints, survey.plannedPoints)
        // Runs of this SSID: by the SSID of their results, else by the network observed before the run.
        val ssidRuns = runs.filter { run ->
            val r = run.results
            val fromResults = r?.tests.orEmpty().map { it.ssid } + r?.networkTests.orEmpty().map { it.ssid } + r?.scripts.orEmpty().map { it.ssid }
            if (ssid == null) fromResults.isNotEmpty() && fromResults.all { it == null } && run.observedSsid == null
            else ssid in fromResults || (fromResults.all { it == null } && run.observedSsid == ssid)
        }
        val metrics = mutableListOf<Metric>()
        fun metric(category: String, alias: String, measure: String, unit: String, method: String?, entries: List<Entry>) {
            if (entries.isEmpty()) return
            val series = entries.first().series
            // Mejora 1: per point, the last attempt and the last valid value are different facts.
            val points = entries.groupBy { it.pointId }.map { (pointId, list) ->
                val sorted = list.sortedBy { it.run.startedAt }
                fun fact(e: Entry) = Fact(e.run.runId, e.run.startedAt, e.status, e.value, e.code)
                PointFact(pointId, labels[pointId] ?: pointId, fact(sorted.last()), sorted.lastOrNull { it.value != null }?.let(::fact))
            }.sortedBy { it.label }
            val values = when (selection) {
                RunSelection.ALL -> entries.mapNotNull { it.value }
                RunSelection.LATEST_PER_POINT -> points.mapNotNull { it.lastValid?.value }
            }.sorted()
            val failures = when (selection) {
                RunSelection.ALL -> entries.count { it.value == null }
                RunSelection.LATEST_PER_POINT -> points.count { it.lastAttempt.value == null }
            }
            val counted = when (selection) {
                RunSelection.ALL -> entries
                RunSelection.LATEST_PER_POINT -> entries.groupBy { it.pointId }.values.map { list -> list.maxBy { it.run.startedAt } }
            }
            val outcomes = counted.groupingBy(::outcome).eachCount()
            metrics += Metric(
                category, alias, measure, unit, method, values.size, failures,
                values.takeIf { it.isNotEmpty() }?.let { round1(if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2) },
                values.firstOrNull(), values.lastOrNull(), series.id, series.label, series.comparable, points, outcomes
            )
        }
        fun categoryOf(t: ThroughputTestResult) = t.category ?: if (t.method == ThroughputTestResult.METHOD_IPERF3_TCP) TargetCategory.PRIVATE_IPERF else TargetCategory.INTERNET
        fun status(s: String?, value: Double?) = s ?: if (value != null) RunStatus.COMPLETED else RunStatus.FAILED

        // Throughput: grouped by series, never by alias, and only results of this SSID.
        val throughput = ssidRuns.flatMap { run -> run.results?.tests.orEmpty().filter { it.ssid == ssid }.map { run to it } }
        listOf(Series.DOWNLOAD, Series.UPLOAD, Series.LATENCY).forEach { direction ->
            val entries = throughput.map { (run, t) ->
                val series = Series.throughput(t, direction)
                when (direction) {
                    Series.DOWNLOAD -> Entry(series, run.pointId, run, t.downMbps, status(t.downloadStatus, t.downMbps), t.downloadErrorCode)
                    Series.UPLOAD -> Entry(series, run.pointId, run, t.upMbps, status(t.uploadStatus, t.upMbps), t.uploadErrorCode)
                    else -> Entry(series, run.pointId, run, t.latencyMs, status(t.latencyStatus, t.latencyMs), t.latencyErrorCode)
                }
            }.toMutableList()
            // A run of this SSID cut before this target produced a result still counts as an attempt
            // of the target's series (its phase status says how it ended).
            ssidRuns.filter { run -> run.results?.tests.orEmpty().none { it.ssid == ssid } }.forEach { run ->
                run.phases.filter { it.key.startsWith("target:") && it.key.endsWith(":$direction") }.forEach { phase ->
                    val targetId = phase.key.removePrefix("target:").substringBeforeLast(':')
                    val known = throughput.lastOrNull { it.second.targetId == targetId }?.second ?: return@forEach
                    entries += Entry(Series.throughput(known, direction), run.pointId, run, null, phase.status, phase.errorCode ?: run.statusReason)
                }
            }
            entries.groupBy { it.series.id }.forEach { (_, list) ->
                val first = throughput.first { Series.throughput(it.second, direction).id == list.first().series.id }.second
                when (direction) {
                    Series.DOWNLOAD -> metric(categoryOf(first), first.target, "download", "Mbit/s", first.method, list)
                    Series.UPLOAD -> metric(categoryOf(first), first.target, "upload", "Mbit/s", first.method, list)
                    else -> metric(categoryOf(first), first.target, "latency", "ms", first.latencyMethod ?: "tcp-connect", list)
                }
            }
        }
        ssidRuns.flatMap { run -> run.results?.networkTests.orEmpty().filter { it.ssid == ssid }.map { run to it } }
            .groupBy { Series.networkTest(it.second).id }
            .forEach { (_, list) ->
                val first = list.first().second
                val (measure, read) = when (first.type) {
                    NetworkTest.DNS -> "dns lookup" to { t: NetworkTestResult -> t.dnsMs }
                    NetworkTest.PING -> "round trip" to { t: NetworkTestResult -> t.rttAvgMs }
                    NetworkTest.TCP -> "tcp connect" to { t: NetworkTestResult -> t.connectMs }
                    else -> "http request" to { t: NetworkTestResult -> t.totalMs }
                }
                metric(first.category ?: TargetCategory.INTERNAL, first.alias ?: first.name, measure, "ms", first.method ?: first.type,
                    list.map { (run, t) -> Entry(Series.networkTest(t), run.pointId, run, read(t).takeIf { t.ok }, if (t.ok) RunStatus.COMPLETED else RunStatus.FAILED, t.errorCode) })
            }
        ssidRuns.flatMap { run -> run.results?.scripts.orEmpty().filter { it.ssid == ssid }.map { run to it } }
            .groupBy { Series.script(it.second).id }
            .forEach { (_, list) ->
                val first = list.first().second
                metric(TargetCategory.INTERNAL, first.alias ?: first.name, "script", "ms", "http-script",
                    list.map { (run, sr) ->
                        // Validated against the planned steps: a missing or failed step is not a complete run.
                        val planned = sr.stepsTotal ?: sr.steps.size
                        val complete = sr.steps.isNotEmpty() && sr.steps.all { it.ok } && sr.steps.size == planned
                        val partial = !complete && sr.steps.any { it.ok }
                        val status = when {
                            complete -> RunStatus.COMPLETED
                            sr.steps.isEmpty() && sr.errorCode == ErrorCodes.NOT_ON_WIFI -> RunStatus.SKIPPED
                            sr.errorCode == ErrorCodes.CANCELLED || sr.errorCode == ErrorCodes.SKIPPED_BY_USER -> RunStatus.CANCELLED
                            else -> RunStatus.FAILED
                        }
                        Entry(Series.script(sr), run.pointId, run, if (complete) sr.steps.sumOf { it.totalMs ?: 0.0 } else null,
                            status, sr.errorCode ?: sr.steps.firstOrNull { !it.ok }?.errorCode, partial = partial)
                    })
            }
        val runFacts = ssidRuns.sortedBy { it.startedAt }.map { run ->
            RunFact(run.runId, labels[run.pointId] ?: run.pointId, run.startedAt, run.status, run.statusReason,
                run.results?.let { !it.isEmpty } == true)
        }
        val results = ssidRuns.mapNotNull { it.results }

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
        val coverage = ssid?.let { SsidCoverage.points(survey.scanPoints, survey.plannedPoints, it, weakDbm) }.orEmpty()
        return SsidSection(ssid, coverage, metrics, apChanges, outages, runFacts)
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
        append(if (model.selection == RunSelection.ALL) "every run" else "the last valid value at each point, per SSID and series")
        model.selectionDetail?.let { append(" (").append(e(it)).append(")") }
        append(". Weak signal (profile ").append(e(model.profile)).append("): strongest AP of the SSID below ").append(model.weakDbm).append(" dBm in the latest valid scan. ")
        append("Signal values are the strongest AP of each SSID seen by the passive scan, not necessarily the AP the phone was connected to during the tests. ")
        append("Points imported from an ESX have no local measurement (their Ekahau data is kept untouched in the .esx).</p>")
        model.floors.forEach { floor ->
            append("<h2>Floor: ").append(e(floor.floorName)).append("</h2>")
            append("<p class=\"muted\">").append(floor.runs).append(" active test runs; ").append(floor.remeasuredPoints).append(" points measured more than once.</p>")
            if (floor.ssids.isEmpty()) append("<p>No local Wi-Fi scans on this floor.</p>")
            (floor.ssids + listOfNotNull(floor.unknownAssociation)).forEach { section ->
                if (section.ssid == null) {
                    append("<h3>Unknown association</h3><p class=\"muted\">Results recorded before the SSID could be identified (e.g. the Wi-Fi was lost). Reasons are listed per run below.</p><ul>")
                } else {
                    append("<h3>SSID: ").append(e(section.ssid)).append("</h3><ul>")
                }
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
                    append("<p class=\"muted\">Denominators: «Samples» are points with a valid value of the series (the last valid one per point unless all runs are selected); «Outcomes» count the attempts of the series in the selection (one per point: its last attempt), never mixing series, SSIDs or not-configured directions.</p>")
                    append("<table><tr><th>Service</th><th>Type</th><th>Measure</th><th>Median</th><th>Min–max</th><th>Unit</th><th>Method</th><th>Samples</th><th>Failures</th><th>Outcomes</th></tr>")
                    section.metrics.sortedWith(compareBy({ it.category }, { it.alias })).forEach { m ->
                        append("<tr><td>").append(e(m.alias)).append("<div class=\"muted\">").append(e(m.seriesLabel))
                            .append(if (m.comparable) "" else " · not comparable (saved before series existed)").append("</div></td><td>")
                            .append(e(categoryLabel(m.category))).append("</td><td>")
                            .append(e(m.measure)).append("</td><td>").append(e(m.median)).append("</td><td>")
                            .append(if (m.min != null) e("${m.min}–${m.max}") else "").append("</td><td>").append(e(m.unit)).append("</td><td>")
                            .append(e(m.method)).append("</td><td>").append(m.samples).append("</td><td>").append(m.failures).append("</td><td>")
                            .append(e(Outcome.ORDER.filter { it in m.outcomes }.joinToString(", ") { "$it ${m.outcomes.getValue(it)}" })).append("</td></tr>")
                    }
                    append("</table>")
                }
                section.metrics.filter { it.points.isNotEmpty() }.forEach { m ->
                    append("<details><summary>").append(e(m.alias)).append(" · ").append(e(m.measure)).append(" · per point</summary>")
                    append("<table><tr><th>Point</th><th>Last attempt</th><th>Last valid</th></tr>")
                    m.points.forEach { p ->
                        fun fact(f: Fact?) = f?.let { "${at(it.at)} · ${it.status}${it.code?.let { c -> " ($c)" } ?: ""}${it.value?.let { v -> " · $v ${m.unit}" } ?: ""} · run ${it.runId}" } ?: "—"
                        append("<tr><td>").append(e(p.label)).append("</td><td>").append(e(fact(p.lastAttempt))).append("</td><td>").append(e(fact(p.lastValid))).append("</td></tr>")
                    }
                    append("</table></details>")
                }
                if (section.runs.isNotEmpty()) {
                    append("<details><summary>Runs on this SSID (").append(section.runs.size).append(")</summary><table><tr><th>Run</th><th>Point</th><th>Started</th><th>Status</th><th>Values</th></tr>")
                    section.runs.forEach { r ->
                        append("<tr><td>").append(e(r.runId)).append("</td><td>").append(e(r.pointLabel)).append("</td><td>").append(e(at(r.at)))
                            .append("</td><td>").append(e(r.status)).append(r.reason?.let { " (" + e(it) + ")" } ?: "").append("</td><td>")
                            .append(if (r.withValues) "yes" else "none").append("</td></tr>")
                    }
                    append("</table></details>")
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
