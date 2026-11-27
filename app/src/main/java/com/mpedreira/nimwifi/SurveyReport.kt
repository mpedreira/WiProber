package com.mpedreira.nimwifi


/**
 * Plain-language survey report per floor and SSID, written next to the ESX and the JSON
 * (`wiprober-report.html`). Built only from nimWifi's own data: imported ESX measurements stay
 * opaque, so imported points count as "no local measurement".
 */
object SurveyReport {
    const val FILE_NAME = "wiprober-report.html"
    private const val MAX_REPORTED_SSIDS = 5

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

    /**
     * An AP change or a loss of reachability. [at] keeps milliseconds; [seq] keeps the order of
     * events in the same millisecond. [windowFrom]..[windowTo] is what the observations bound.
     */
    data class Event(
        val kind: String, val at: Long?, val from: String?, val to: String?, val detail: String?,
        val windowFrom: Long? = null, val windowTo: Long? = null,
        val fromSsid: String? = null, val toSsid: String? = null,
        val x: Float? = null, val y: Float? = null,
        val incidentId: String? = null, val seq: Int = 0, val source: String = "run",
        /** rc.3 · 4: stable identity in the floor: session/run and position there. */
        val key: String = "",
        /** Bounds of the outage (ms), as the probe observed them; null when not estimable. */
        val outageLowerMs: Long? = null,
        val outageUpperMs: Long? = null,
        /** Not recovered when the route ended. */
        val open: Boolean = false
    )

    data class SsidSection(
        /** Null: results whose association (SSID) was never identified. */
        val ssid: String?,
        val points: List<PointCoverage>,
        val metrics: List<Metric>,
        val apChanges: List<Event>,
        val outages: List<Event>,
        val runs: List<RunFact> = emptyList(),
        val marks: List<MapMark> = emptyList(),
        val sources: SourceCounts? = null,
        /** Verdict against the configured [Requirements]; null without requirements. */
        val verdict: SsidVerdict? = null
    ) {
        fun count(state: String) = points.count { it.state == state }
        /** Mejora 11: good primary signal and a second AP of the same SSID above the secondary threshold. */
        val redundant: Int get() = points.count { it.secondaryOk }
    }

    /** Mejora 8: the floor plan embedded in the report (downscaled; marks use original-image pixels). */
    data class PlanImage(val dataUri: String, val originalWidth: Int, val originalHeight: Int)

    /** One mark on the plan. [approximate]: a Continuous scan placed on the route by time. */
    data class MapMark(val x: Float, val y: Float, val source: String, val state: String, val rssi: Int?, val label: String?, val approximate: Boolean) {
        companion object {
            const val STOP = "stop"
            const val CONTINUOUS = "continuous"
            const val PLANNED = "planned"
            const val IMPORTED = "imported"
        }
    }

    /** Where the SSID's evidence comes from. */
    data class SourceCounts(
        val stopPoints: Int,
        val continuousPlaced: Int,
        /** Continuous scans that could not be placed (route without enough waypoints there): not drawn. */
        val continuousWithoutGeometry: Int,
        val imported: Int,
        val planned: Int,
        val continuousWeak: Int,
        val continuousNotDetected: Int,
        val continuousWorst: Int?
    )

    data class FloorSection(
        val floorName: String,
        val runs: Int,
        val remeasuredPoints: Int,
        val ssids: List<SsidSection>,
        /** Mejora 10: results without an identified association, with their reasons. */
        val unknownAssociation: SsidSection? = null,
        val image: PlanImage? = null,
        val floorPlanId: String? = null,
        /** How this floor's radios are named: user's table, band and channel. */
        val apLabels: ApLabels = ApLabels.EMPTY,
        /** rc.2 · 5: floor totals, each event counted once although it is shown in two sections. */
        val totals: FloorTotals = FloorTotals(0, 0)
    )

    data class FloorTotals(val apChanges: Int, val outages: Int) {
        companion object {
            /** Each event once, by its identity, although it may be shown in two sections. */
            fun of(events: List<FloorEvent>): FloorTotals {
                val distinct = events.distinctBy { it.event.key }
                return FloorTotals(distinct.count { !it.outage }, distinct.count { it.outage })
            }
        }
    }

    data class Model(
        val title: String,
        val generatedAt: Long,
        val selection: RunSelection,
        val weakDbm: Int,
        val profile: String = SignalProfile.DEFAULT.id,
        val floors: List<FloorSection>,
        /** Mejora 1: what the selection covers (all runs, one run, an interval). */
        val selectionDetail: String? = null,
        /** Requirements applied to the verdict; null (or empty): no verdict, the report as before. */
        val requirements: Requirements? = null,
        /** BSSID → AP names (display only); empty: BSSIDs as before. */
        val apNames: ApDirectory = ApDirectory.EMPTY
    ) {
        val hasVerdict: Boolean get() = requirements != null && !requirements.isEmpty
    }

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
        toMs: Long? = null,
        /** Floor plans by floorPlanId, to draw the maps. */
        planImages: Map<String, PlanImage> = emptyMap(),
        /** Thresholds of the verdict; without them the report is exactly as before. */
        requirements: Requirements? = null,
        /** The user's BSSID → AP table; unmapped BSSIDs are shown as they are. */
        apNames: ApDirectory = ApDirectory.EMPTY
    ): Model = Model(
        requirements = requirements?.takeUnless { it.isEmpty },
        apNames = apNames,
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
            val events = floorEvents(survey, runs)
            val apLabels = ApLabels(apNames, ApLabels.radios(survey))
            FloorSection(
                apLabels = apLabels,
                floorName = floor.name,
                runs = survey.activeRuns.size,
                remeasuredPoints = (survey.activeRuns.groupBy(ActiveRun::pointId).filterValues { it.size > 1 }.keys +
                    survey.scanPoints.filter(SsidCoverage::isValidScan).groupBy(ScanPoint::pointId).filterValues { it.size > 1 }.keys).size,
                ssids = ssidsFor(survey, chosenSsid).map { ssid -> section(ssid, survey, runs, weakDbm, selection, SignalProfile.of(profile).secondaryDbm, events, requirements, floor.name, apLabels) },
                // Also without active tests: roaming events without any SSID are shown there once.
                unknownAssociation = section(null, survey, runs, weakDbm, selection, events = events)
                    .takeIf { it.runs.isNotEmpty() || it.apChanges.isNotEmpty() || it.outages.isNotEmpty() },
                image = planImages[floor.esxFloorPlanId],
                floorPlanId = floor.esxFloorPlanId,
                // rc.3 · 4: from the floor's events, not from the (limited) sections shown.
                totals = FloorTotals.of(events)
            )
        }
    )

    /** The chosen SSID, the SSIDs associated during active tests and the three most widely detected (max five). */
    fun ssidsFor(survey: ProjectSnapshot, chosen: String): List<String> {
        val associated = survey.activeRuns.flatMap { run ->
            val results = run.results
            listOfNotNull(run.observedSsid) + results?.tests.orEmpty().mapNotNull { it.ssid } +
                results?.networkTests.orEmpty().mapNotNull { it.ssid } + results?.scripts.orEmpty().mapNotNull { it.ssid }
        }
        // The chosen SSID, those used by the tests, then the most widely detected (at most five).
        return (listOfNotNull(chosen.trim().ifEmpty { null }) + associated + SsidCoverage.ssids(survey.scanPoints, survey.continuousScanSessions).take(3))
            .filter(String::isNotEmpty).distinct().take(MAX_REPORTED_SSIDS)
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

    /**
     * rc.3 · 4: one event of the floor, inside the report's scope, with an identity tied to its
     * session or run and its position there ([Event.key]). Sections show the events that belong
     * to their SSID; floor totals count this list, whatever sections are shown.
     */
    class FloorEvent(val event: Event, val outage: Boolean, private val memberSsids: Set<String?>, private val unknownSsid: Boolean) {
        fun belongsTo(ssid: String?): Boolean = if (ssid == null) unknownSsid else ssid in memberSsids
    }

    /** Every AP change and reachability loss of the floor in scope ([runs]: the report's runs). */
    fun floorEvents(survey: ProjectSnapshot, runs: List<ActiveRun>): List<FloorEvent> = buildList {
        runs.forEach { run ->
            run.results?.tests.orEmpty().forEachIndexed { index, test ->
                if (test.associationChange !in setOf(AssociationChange.BSSID_CHANGED, AssociationChange.SSID_CHANGED)) return@forEachIndexed
                val event = Event(test.associationChange!!, test.measuredAt, test.bssidStart, test.bssidEnd, test.target,
                    fromSsid = test.ssid, toSsid = test.ssidEnd, seq = index, source = "run", key = "run:${run.runId}:$index")
                // A run's AP change is reported under the SSID the test ran on.
                add(FloorEvent(event, outage = false, memberSsids = setOf(test.ssid), unknownSsid = test.ssid == null))
            }
        }
        survey.continuousScanSessions.filterNot { it.importedFromEsx }.forEach { session ->
            val route = session.waypoints.sortedBy(RoutePointWrapper::timestamp)
            session.roamingLogs.forEachIndexed { logIndex, log ->
                log.events.forEachIndexed { index, event ->
                    val at = session.startTime + event.atMillis
                    val position = if (route.size >= 2 && event.atMillis in route.first().timestamp..route.last().timestamp) SurveyGuide.positionAt(route, event.atMillis) else null
                    val detail = when {
                        event.open == true -> "sin recuperar al terminar"
                        event.outageObserved == false -> "sin fallos observados" + (event.outageUpperBoundMs?.let { " (resolución ≤ $it ms)" } ?: "")
                        event.outageLowerBoundMs != null -> "corte de ${event.outageLowerBoundMs} a ${event.outageUpperBoundMs ?: "?"} ms"
                        else -> "duración no estimable (hueco sin muestras)"
                    } + " · sonda " + probeLabel(log)
                    val built = Event(event.type, at, event.fromBssid, event.toBssid, detail,
                        windowFrom = event.lastReachableAtMillis?.let { session.startTime + it },
                        windowTo = event.firstReachableAtMillis?.let { session.startTime + it },
                        fromSsid = event.fromSsid, toSsid = event.toSsid, x = position?.first, y = position?.second,
                        incidentId = event.incidentId, seq = index, source = "roaming", key = "roaming:${session.id}:$logIndex:$index",
                        outageLowerMs = event.outageLowerBoundMs, outageUpperMs = event.outageUpperBoundMs, open = event.open == true)
                    val outage = event.type !in setOf(RoamingEvent.BSSID_CHANGE, RoamingEvent.SSID_CHANGE, RoamingEvent.ROAM)
                    // rc.2 · 5: without SSID on either side only «SSID desconocido»; a change between
                    // two known SSIDs belongs to both (shown twice, counted once).
                    add(FloorEvent(built, outage, setOfNotNull(event.fromSsid, event.toSsid), event.fromSsid == null && event.toSsid == null))
                }
            }
        }
    }

    private fun section(ssid: String?, survey: ProjectSnapshot, runs: List<ActiveRun>, weakDbm: Int, selection: RunSelection, secondaryDbm: Int = SignalProfile.of(null).secondaryDbm,
                        events: List<FloorEvent> = floorEvents(survey, runs), requirements: Requirements? = null, floorName: String = "", apLabels: ApLabels = ApLabels.EMPTY): SsidSection {
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

        // rc.3 · 4: this section's share of the floor's events (each with its stable identity).
        val mine = events.filter { it.belongsTo(ssid) }
        val apChanges = mine.filterNot { it.outage }.map { it.event }
        val outages = mine.filter { it.outage }.map { it.event }
        val coverage = ssid?.let { SsidCoverage.points(survey.scanPoints, survey.plannedPoints, it, weakDbm, secondaryDbm, survey.activeRuns) }.orEmpty()
        val (marks, sources) = if (ssid != null) marks(ssid, survey, coverage, weakDbm) else emptyList<MapMark>() to null
        // The verdict judges this SSID alone, with the same points, runs and events as the section.
        val verdict = if (ssid != null && requirements != null && !requirements.isEmpty) {
            VerdictEvaluator.evaluate(requirements, ssid, floorName, coverage, runs, events, weakDbm, apLabels)
        } else {
            null
        }
        return SsidSection(ssid, coverage, metrics, apChanges, outages, runFacts, marks, sources, verdict)
    }

    /**
     * Mejora 8: what the plan shows for one SSID. Stop-and-Go points at their exact position;
     * Continuous scans only where the route has enough geometry (two waypoints around the scan
     * time), marked approximate; the others are counted, never drawn.
     */
    /** rc.2 · 4: the probe of a route stretch; drafts saved before rc.2 have no method: «desconocido». */
    internal fun probeLabel(log: RoamingLog): String {
        val method = when (log.method) {
            RoamingTracker.METHOD -> "TCP"
            RoamingTracker.METHOD_ICMP -> "ICMP"
            else -> "método desconocido"
        }
        return "${log.targetAlias ?: "destino"} · $method · cada ${log.intervalMillis} ms"
    }

    private fun marks(ssid: String, survey: ProjectSnapshot, coverage: List<PointCoverage>, weakDbm: Int): Pair<List<MapMark>, SourceCounts> {
        val marks = coverage.map { p ->
            val source = when {
                p.planned && p.scans == 0 -> MapMark.PLANNED
                p.imported -> MapMark.IMPORTED
                else -> MapMark.STOP
            }
            MapMark(p.x, p.y, source, p.state, p.strongestRssi, p.label, approximate = false)
        }.toMutableList()
        var placed = 0
        var unplaced = 0
        var weak = 0
        var notDetected = 0
        var worst: Int? = null
        survey.continuousScanSessions.filterNot { it.importedFromEsx }.forEach { session ->
            val route = session.waypoints.sortedBy(RoutePointWrapper::timestamp)
            session.scanResults.forEach { scan ->
                val position = if (route.size >= 2 && scan.timestamp in route.first().timestamp..route.last().timestamp) {
                    SurveyGuide.positionAt(route, scan.timestamp)
                } else null
                val strongest = scan.wifiNetworks.filter { it.ssid == ssid }.maxOfOrNull { it.level }
                val state = when {
                    scan.wifiNetworks.isEmpty() -> PointState.NOT_MEASURED
                    strongest == null -> PointState.SSID_NOT_DETECTED
                    strongest < weakDbm -> PointState.WEAK
                    else -> PointState.OK
                }
                if (state == PointState.WEAK) weak++
                if (state == PointState.SSID_NOT_DETECTED) notDetected++
                if (strongest != null) worst = minOf(worst ?: strongest, strongest)
                if (position == null) {
                    unplaced++
                } else {
                    placed++
                    marks += MapMark(position.first, position.second, MapMark.CONTINUOUS, state, strongest, null, approximate = true)
                }
            }
        }
        val sources = SourceCounts(
            stopPoints = coverage.count { !it.imported && it.scans > 0 },
            continuousPlaced = placed,
            continuousWithoutGeometry = unplaced,
            imported = coverage.count { it.imported },
            planned = coverage.count { it.planned && it.scans == 0 },
            continuousWeak = weak,
            continuousNotDetected = notDetected,
            continuousWorst = worst
        )
        return marks to sources
    }

    /** Mejora 19: readable report for Operations, in Spanish, self-contained (no scripts). */
    fun html(model: Model): String = buildString {
        fun e(text: Any?): String = text?.toString().orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>").append(e(model.title)).append(" – informe Wi-Fi</title><style>")
        append("body{font-family:system-ui,sans-serif;margin:16px;max-width:1000px;color:#1c1c1c;background:#fff}")
        append("table{border-collapse:collapse;width:100%;margin:8px 0 16px}th,td{border:1px solid #ccc;padding:4px 6px;text-align:left;font-size:14px}")
        append("th{background:#f0f0f0}.weak{color:#b35c00}.bad{color:#b00020}.ok{color:#1b6e20}.muted{color:#666;font-size:13px}.box{border:1px solid #ccc;padding:8px 12px;margin:8px 0;background:#fafafa}")
        append("</style></head><body>")
        append("<h1>").append(e(model.title)).append("</h1>")
        append("<p class=\"muted\">Generado el ").append(e(time(model.generatedAt))).append(". Perfil de señal «").append(e(model.profile))
            .append("»: señal débil por debajo de ").append(model.weakDbm).append(" dBm. Cifras de servicio: ")
        append(if (model.selection == RunSelection.ALL) "todas las ejecuciones" else "la última medición válida de cada punto, por SSID y serie")
        model.selectionDetail?.let { append(" (").append(e(it)).append(")") }
        append(".</p>")
        append("<div class=\"box\"><b>Cómo leer este informe.</b> <b>Señal</b>: lo que el escaneo pasivo oyó de cada SSID (el AP más fuerte, no necesariamente al que estaba conectado el móvil). ")
        append("<b>Degradación de red</b>: la conexión Wi-Fi sigue pero rinde menos (descarga, subida o latencia peores, pérdidas parciales). ")
        append("<b>Caída hacia un servicio</b>: un destino concreto deja de responder (fallos o códigos de error en sus pruebas) aunque la red siga; mírese junto a los cambios de AP del mismo intervalo. ")
        append("El iPerf3 privado mide la red hasta el servidor propio; Internet incluye además la salida a Internet; los servicios internos son las aplicaciones (DNS, ping, TCP, HTTP, scripts).</div>")
        if (model.hasVerdict) appendVerdictSummary(this, model, ::e)
        model.floors.forEach { floor ->
            append("<h2>Planta: ").append(e(floor.floorName)).append("</h2>")
            append("<p class=\"muted\">").append(floor.runs).append(" ejecuciones de pruebas; ").append(floor.remeasuredPoints).append(" puntos medidos más de una vez.</p>")
            append("<p>Total de la planta: ").append(floor.totals.apChanges).append(" cambios de AP · ").append(floor.totals.outages)
                .append(" pérdidas de alcance (cada evento una vez, aunque aparezca en dos SSID).</p>")
            if (floor.ssids.isEmpty()) append("<p>Sin escaneos Wi-Fi locales en esta planta.</p>")
            (floor.ssids + listOfNotNull(floor.unknownAssociation)).forEach { section -> appendSection(this, model, floor, section, ::e) }
        }
        append("</body></html>")
    }

    /** `2,4 GHz −58 · 5 GHz −71` for one point; `—` when no band was heard. */
    private fun bandCell(p: PointCoverage): String =
        p.bands.joinToString(" · ") { "${WifiBands.label(it.band)} ${it.rssi}" }.ifEmpty { "—" }

    /** Per band: points with good signal, weak, and measured points where the band was not heard. */
    internal fun bandSummary(points: List<PointCoverage>, weakDbm: Int): String? {
        val measured = points.filter { it.state != PointState.NOT_MEASURED }
        val bands = WifiBands.ORDER.filter { b -> measured.any { it.band(b) != null } }
        if (bands.isEmpty()) return null
        return bands.joinToString(" · ") { b ->
            val good = measured.count { (it.band(b)?.rssi ?: Int.MIN_VALUE) >= weakDbm }
            val weak = measured.count { it.band(b)?.let { s -> s.rssi < weakDbm } == true }
            val missing = measured.count { it.band(b) == null }
            "${WifiBands.label(b)}: buena $good, débil $weak, sin oír $missing de ${measured.size}"
        }
    }

    private fun stateClass(state: String) = when (state) {
        VerdictState.PASS -> "ok"
        VerdictState.FAIL -> "bad"
        else -> "weak"
    }

    private fun num(value: Double?): String = when {
        value == null -> "—"
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> "%.1f".format(java.util.Locale.ROOT, value)
    }

    /** Verdict at the top: per floor and SSID, with explicit denominators, then the requirements applied. */
    private fun appendVerdictSummary(out: StringBuilder, model: Model, e: (Any?) -> String) = with(out) {
        append("<h2>Veredicto</h2><p class=\"muted\">Cada SSID se juzga por separado. «Cumple» sobre los puntos con datos válidos de todos los requisitos; ")
        append("«faltan datos»: puntos planificados, importados sin RF local o con pruebas omitidas, canceladas o sin valor. Un dato ausente nunca cuenta como 0.</p>")
        append("<table><tr><th>Planta</th><th>SSID</th><th>Veredicto</th><th>Puntos que cumplen</th><th>Faltan datos</th><th>Comprobaciones de planta</th></tr>")
        model.floors.forEach { floor ->
            floor.ssids.mapNotNull { it.verdict }.forEach { v ->
                append("<tr><td>").append(e(floor.floorName)).append("</td><td>").append(e(v.ssid)).append("</td><td class=\"").append(stateClass(v.state)).append("\">")
                    .append(VerdictState.label(v.state)).append("</td><td>")
                append(v.passing).append(" de ").append(v.withData).append(" con datos")
                v.percentPassing?.let { append(" (").append(num(it)).append(" %)") }
                append("</td><td>").append(v.missing).append(" de ").append(v.points.size).append("</td><td>")
                append(e(v.floorChecks.filter { it.state != VerdictState.PASS }.joinToString("; ") { "${it.label}: ${VerdictState.label(it.state)}" }.ifEmpty { if (v.floorChecks.isEmpty()) "—" else "cumplen" }))
                append("</td></tr>")
            }
        }
        append("</table>")
        val r = model.requirements ?: return@with
        append("<details><summary>Requisitos aplicados</summary><ul>")
        append("<li>Señal: AP más fuerte ≥ ").append(model.weakDbm).append(" dBm (perfil «").append(e(model.profile)).append("»)</li>")
        r.throughput.orEmpty().forEach { t ->
            append("<li>iPerf3 privado «").append(e(t.alias)).append("»: ")
            append(listOfNotNull(t.minDownMbps?.let { "bajada ≥ ${num(it)} Mbit/s" }, t.minUpMbps?.let { "subida ≥ ${num(it)} Mbit/s" },
                t.maxLatencyMedianMs?.let { "latencia ≤ ${num(it)} ms" }, t.maxLatencyP95Ms?.let { "latencia p95 ≤ ${num(it)} ms" },
                t.maxLatencyLossPct?.let { "pérdida de muestras ≤ ${num(it)} %" }).joinToString(", ").let(e)).append("</li>")
        }
        r.networkTests.orEmpty().forEach { t ->
            append("<li>Prueba «").append(e(t.alias)).append("»: ")
            append(listOfNotNull(t.maxMedianMs?.let { "tiempo ≤ ${num(it)} ms" }, t.maxP95Ms?.let { "p95 ≤ ${num(it)} ms" },
                t.maxLossPct?.let { "pérdida ≤ ${num(it)} %" }).joinToString(", ").let(e)).append("</li>")
        }
        r.roaming?.let { ro ->
            append("<li>Roaming: ").append(listOfNotNull(ro.maxOutageMs?.let { "ventana de pérdida ≤ $it ms" },
                ro.maxLossesPerFloor?.let { "como mucho $it pérdidas por planta" }).joinToString(", ").let(e)).append("</li>")
        }
        append("<li>Un corte de alcance durante la medición de un punto hace que no cumpla, aunque su mediana sea buena.</li></ul></details>")
    }

    /** Per section: the points that fail and why (requirement, value, threshold, run). */
    private fun appendVerdict(out: StringBuilder, v: SsidVerdict, e: (Any?) -> String) = with(out) {
        append("<p>Veredicto: <b class=\"").append(stateClass(v.state)).append("\">").append(VerdictState.label(v.state)).append("</b> — ")
            .append(v.passing).append(" de ").append(v.withData).append(" puntos con datos cumplen")
        v.percentPassing?.let { append(" (").append(num(it)).append(" %)") }
        append("; faltan datos en ").append(v.missing).append(".")
        if (v.outagesOnFloor > 0) {
            append(" <b>").append(v.outagesOnFloor).append(" pérdidas de alcance</b> en esta planta")
            if (v.floorChecks.none { it.requirement.startsWith("roaming:") }) append(" (sin requisito de roaming configurado: no deciden el veredicto, véase «Roaming y cortes»)")
            append(".")
        }
        append("</p>")
        val failing = v.points.filter { it.state == VerdictState.FAIL }
        val floorFails = v.floorChecks.filter { it.state != VerdictState.PASS }
        if (failing.isNotEmpty() || floorFails.isNotEmpty()) {
            append("<h4>Qué no cumple</h4><table><tr><th>Punto</th><th>Requisito</th><th>Valor</th><th>Umbral</th><th>Ejecución</th><th>Detalle</th></tr>")
            failing.forEach { p ->
                p.failed.forEach { c ->
                    append("<tr><td>").append(e(p.label)).append("</td><td>").append(e(c.label)).append("</td><td>").append(e(num(c.value) + " " + c.unit))
                        .append("</td><td>").append(e(c.threshold?.let { num(it) + " " + c.unit } ?: "—")).append("</td><td>").append(e(c.runId ?: "—"))
                        .append("</td><td>").append(e(c.reason ?: "—")).append("</td></tr>")
                }
            }
            floorFails.forEach { c ->
                append("<tr><td>planta</td><td>").append(e(c.label)).append("</td><td>").append(e(num(c.value) + " " + c.unit)).append("</td><td>")
                    .append(e(c.threshold?.let { num(it) + " " + c.unit } ?: "—")).append("</td><td>—</td><td>")
                    .append(e(VerdictState.label(c.state) + (c.reason?.let { " · $it" } ?: ""))).append("</td></tr>")
            }
            append("</table>")
        }
        val missing = v.points.filter { it.state == VerdictState.MISSING }
        if (missing.isNotEmpty()) {
            append("<details><summary>Puntos sin datos suficientes (").append(missing.size).append(")</summary><ul>")
            missing.forEach { p ->
                append("<li>").append(e(p.label)).append(": ")
                    .append(e(p.checks.filter { it.state == VerdictState.MISSING }.joinToString("; ") { "${it.label} — ${it.reason ?: "sin dato"}" })).append("</li>")
            }
            append("</ul></details>")
        }
    }

    private fun time(ms: Long?): String = ms?.let {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(it)) + " UTC"
    }.orEmpty()

    private fun appendSection(out: StringBuilder, model: Model, floor: FloorSection, section: SsidSection, e: (Any?) -> String) = with(out) {
        if (section.ssid == null) {
            append("<h3>SSID desconocido · ").append(e(floor.floorName)).append("</h3>")
            append("<p class=\"muted\">Asociación desconocida: resultados registrados antes de identificar el SSID (p. ej. se perdió el Wi-Fi) y eventos de roaming sin SSID de origen ni de destino. No cuentan en ningún SSID; los motivos van por ejecución.</p>")
            append("<ul><li>Cambios de AP: ").append(section.apChanges.size).append(" · pérdidas de alcance: ").append(section.outages.size).append("</li></ul>")
        } else {
            append("<h3>SSID «").append(e(section.ssid)).append("» · ").append(e(floor.floorName)).append("</h3>")
            section.verdict?.let { appendVerdict(this, it, e) }
            // Summary
            append("<ul><li class=\"ok\">Buena señal: ").append(section.count(PointState.OK)).append(" puntos</li>")
            append("<li class=\"weak\">Señal débil medida: ").append(section.count(PointState.WEAK)).append(" puntos</li>")
            append("<li class=\"bad\">SSID no detectado en un escaneo válido: ").append(section.count(PointState.SSID_NOT_DETECTED)).append(" puntos</li>")
            append("<li>Sin medición local: ").append(section.count(PointState.NOT_MEASURED)).append(" puntos</li>")
            append("<li>Segundo AP del mismo SSID por encima del umbral secundario (indicio de redundancia): ").append(section.redundant).append(" puntos</li>")
            append("<li>Cambios de AP: ").append(section.apChanges.size).append(" · pérdidas de alcance: ").append(section.outages.size).append("</li></ul>")
            bandSummary(section.points, model.weakDbm)?.let { append("<p>Por banda (último escaneo válido de cada punto): ").append(e(it)).append("</p>") }
            section.sources?.let { src ->
                append("<p class=\"muted\">Fuentes: ").append(src.stopPoints).append(" puntos Stop-and-Go con RF local; ")
                    .append(src.continuousPlaced).append(" escaneos continuos en el plano (posición aproximada)")
                if (src.continuousWithoutGeometry > 0) append(", ").append(src.continuousWithoutGeometry).append(" sin geometría de ruta suficiente (no se dibujan)")
                append("; ").append(src.imported).append(" puntos importados sin RF local; ").append(src.planned).append(" puntos planificados.")
                if (src.continuousPlaced + src.continuousWithoutGeometry > 0) {
                    append(" Continuo: ").append(src.continuousWeak).append(" débiles (por debajo de ").append(model.weakDbm).append(" dBm), ")
                        .append(src.continuousNotDetected).append(" sin el SSID")
                    src.continuousWorst?.let { append(", peor ").append(it).append(" dBm") }
                    append(".")
                }
                append("</p>")
            }
            floor.image?.let { appendMap(this, it, section, model.weakDbm, e) }
            // Worst points first
            val attention = section.points.filter { it.state != PointState.OK }
                .sortedWith(compareBy({ stateRank(it.state) }, { it.strongestRssi ?: Int.MIN_VALUE }))
            if (attention.isNotEmpty()) {
                append("<h4>Peores puntos</h4><table><tr><th>Punto</th><th>Estado</th><th>AP más fuerte (escaneo)</th><th>dBm</th><th>Por banda</th><th>Segundo AP</th><th>AP asociado (pruebas)</th><th>x, y (px)</th></tr>")
                attention.forEach { p ->
                    append("<tr><td>").append(e(p.label)).append("</td><td>").append(e(stateLabel(p.state)))
                        .append(if (p.state == PointState.WEAK) " (umbral ${model.weakDbm} dBm)" else "").append("</td><td>")
                        .append(e(floor.apLabels.label(p.strongestBssid) ?: "—")).append("</td><td>").append(e(p.strongestRssi ?: "—")).append("</td><td>")
                        .append(e(bandCell(p))).append("</td><td>")
                        .append(e(p.secondBssid?.let { "${floor.apLabels.label(it)} ${p.secondRssi} dBm" } ?: "ninguno")).append("</td><td>")
                        .append(e(p.associatedBssid?.let { "${floor.apLabels.label(it)} ${p.associatedRssi ?: "?"} dBm" + if (p.apChanges > 0) " · ${p.apChanges} cambios de AP" else "" } ?: "—"))
                        .append("</td><td>").append(e("%.0f, %.0f".format(java.util.Locale.ROOT, p.x, p.y))).append("</td></tr>")
                }
                append("</table>")
            }
        }
        // Services by category
        if (section.metrics.isNotEmpty()) {
            append("<p class=\"muted\">Denominadores: «Muestras» son puntos con un valor válido de la serie (el último válido de cada punto salvo que se elijan todas las ejecuciones); «Resultados» cuenta los intentos de la serie en la selección (uno por punto: el último). Nunca se mezclan series, SSID ni direcciones no configuradas.</p>")
            listOf(TargetCategory.PRIVATE_IPERF, TargetCategory.INTERNET, TargetCategory.INTERNAL).forEach { category ->
                val list = section.metrics.filter { (it.category.takeIf { c -> c in setOf(TargetCategory.PRIVATE_IPERF, TargetCategory.INTERNET) } ?: TargetCategory.INTERNAL) == category }
                if (list.isEmpty()) return@forEach
                append("<h4>").append(categoryLabel(category)).append("</h4>")
                append("<table><tr><th>Destino (alias)</th><th>Medida</th><th>Mediana</th><th>Mín–máx</th><th>Unidad</th><th>Método</th><th>Muestras</th><th>Fallos</th><th>Resultados</th></tr>")
                list.sortedWith(compareBy({ it.alias }, { it.measure })).forEach { m ->
                    append("<tr><td>").append(e(m.alias)).append("<div class=\"muted\">").append(e(m.seriesLabel))
                        .append(if (m.comparable) "" else " · no comparable (guardado antes de existir las series)").append("</div></td><td>")
                        .append(e(measureLabel(m.measure))).append("</td><td>").append(e(m.median ?: "—")).append("</td><td>")
                        .append(if (m.min != null) e("${m.min}–${m.max}") else "—").append("</td><td>").append(e(m.unit)).append("</td><td>")
                        .append(e(m.method)).append("</td><td>").append(m.samples).append("</td><td>").append(m.failures).append("</td><td>")
                        .append(e(Outcome.ORDER.filter { it in m.outcomes }.joinToString(", ") { "${outcomeLabel(it)} ${m.outcomes.getValue(it)}" })).append("</td></tr>")
                }
                append("</table>")
                list.filter { it.points.isNotEmpty() }.forEach { m ->
                    append("<details><summary>").append(e(m.alias)).append(" · ").append(e(measureLabel(m.measure))).append(" · por punto</summary>")
                    append("<table><tr><th>Punto</th><th>Último intento</th><th>Última válida</th></tr>")
                    m.points.forEach { p ->
                        fun fact(f: Fact?) = f?.let { "${time(it.at)} · ${it.status}${it.code?.let { c -> " ($c)" } ?: ""}${it.value?.let { v -> " · $v ${m.unit}" } ?: ""} · run ${it.runId}" } ?: "—"
                        append("<tr><td>").append(e(p.label)).append("</td><td>").append(e(fact(p.lastAttempt))).append("</td><td>").append(e(fact(p.lastValid))).append("</td></tr>")
                    }
                    append("</table></details>")
                }
            }
        }
        // Roaming events: milliseconds, observed window, association, approximate position
        val events = (section.apChanges.map { "Cambio de AP" to it } + section.outages.map { "Pérdida de alcance" to it })
            .sortedWith(compareBy({ it.second.at ?: Long.MAX_VALUE }, { it.second.source }, { it.second.seq }))
        if (events.isNotEmpty()) {
            append("<h4>Roaming y cortes</h4><table><tr><th>Evento</th><th>Hora (UTC, ms)</th><th>Ventana observada</th><th>De</th><th>A</th><th>Detalle</th><th>Posición</th></tr>")
            events.forEach { (title, ev) ->
                append("<tr><td>").append(title).append("<div class=\"muted\">").append(e(ev.kind)).append(ev.incidentId?.let { " · incidente $it" } ?: "")
                    .append(e(floor.apLabels.bandChange(ev.from, ev.to)?.let { " · $it" } ?: "")).append("</div></td><td>")
                    .append(e(time(ev.at))).append("</td><td>")
                    .append(if (ev.windowFrom != null || ev.windowTo != null) e("${time(ev.windowFrom).ifEmpty { "?" }} → ${time(ev.windowTo).ifEmpty { "sin recuperar" }}") else "—")
                    .append("</td><td>").append(e(listOfNotNull(ev.fromSsid, floor.apLabels.label(ev.from)).joinToString(" · ").ifEmpty { "—" }))
                    .append("</td><td>").append(e(listOfNotNull(ev.toSsid, floor.apLabels.label(ev.to)).joinToString(" · ").ifEmpty { "—" }))
                    .append("</td><td>").append(e(ev.detail ?: "—")).append("</td><td>")
                    .append(if (ev.x != null && ev.y != null) e("%.0f, %.0f px (aprox.)".format(java.util.Locale.ROOT, ev.x, ev.y)) else "—").append("</td></tr>")
            }
            append("</table>")
        }
        if (section.runs.isNotEmpty()) {
            append("<details><summary>Ejecuciones en ").append(e(section.ssid ?: "asociación desconocida")).append(" (").append(section.runs.size)
                .append(")</summary><table><tr><th>Ejecución</th><th>Punto</th><th>Inicio</th><th>Estado</th><th>Valores</th></tr>")
            section.runs.forEach { r ->
                append("<tr><td>").append(e(r.runId)).append("</td><td>").append(e(r.pointLabel)).append("</td><td>").append(e(time(r.at)))
                    .append("</td><td>").append(e(r.status)).append(r.reason?.let { " (" + e(it) + ")" } ?: "").append("</td><td>")
                    .append(if (r.withValues) "sí" else "ninguno").append("</td></tr>")
            }
            append("</table></details>")
        }
    }

    private fun stateRank(state: String) = when (state) {
        PointState.SSID_NOT_DETECTED -> 0
        PointState.WEAK -> 1
        PointState.NOT_MEASURED -> 2
        else -> 3
    }

    private fun measureLabel(measure: String) = when (measure) {
        "download" -> "descarga"
        "upload" -> "subida"
        "latency" -> "latencia"
        "dns lookup" -> "resolución DNS"
        "round trip" -> "ida y vuelta"
        "tcp connect" -> "conexión TCP"
        "http request" -> "petición HTTP"
        else -> measure
    }

    private fun outcomeLabel(outcome: String) = when (outcome) {
        Outcome.VALID -> "válidas"
        Outcome.PARTIAL -> "parciales"
        Outcome.FAILED -> "fallidas"
        Outcome.SKIPPED -> "omitidas"
        Outcome.CANCELLED -> "canceladas"
        Outcome.INTERRUPTED -> "interrumpidas"
        else -> "no configuradas"
    }

    private fun stateColor(state: String) = when (state) {
        PointState.OK -> "#1b6e20"
        PointState.WEAK -> "#e67e00"
        PointState.SSID_NOT_DETECTED -> "#b00020"
        else -> "#777777"
    }

    /** The plan with the SSID's marks, in original-image pixels. */
    private fun appendMap(out: StringBuilder, image: PlanImage, section: SsidSection, weakDbm: Int, e: (Any?) -> String) {
        val w = image.originalWidth
        val h = image.originalHeight
        val r = maxOf(w, h) / 110f
        out.append("<figure><svg viewBox=\"0 0 $w $h\" style=\"width:100%;height:auto;border:1px solid #ccc\" role=\"img\" aria-label=\"")
            .append(e(section.ssid)).append(" plano\"><image href=\"").append(image.dataUri).append("\" width=\"$w\" height=\"$h\"/>")
        section.marks.sortedBy { if (it.source == MapMark.CONTINUOUS) 0 else 1 }.forEach { m ->
            val color = stateColor(m.state)
            val title = buildString {
                append(m.label ?: m.source).append(": ").append(stateLabel(m.state))
                m.rssi?.let { append(" · ").append(it).append(" dBm (umbral ").append(weakDbm).append(")") }
                if (m.approximate) append(" · posición aproximada")
            }
            when {
                m.source == MapMark.CONTINUOUS -> out.append("<circle cx=\"${m.x}\" cy=\"${m.y}\" r=\"${r * 0.45f}\" fill=\"$color\" fill-opacity=\"0.75\" stroke=\"#fff\" stroke-dasharray=\"2 2\"><title>")
                    .append(e(title)).append("</title></circle>")
                m.state == PointState.NOT_MEASURED -> out.append("<circle cx=\"${m.x}\" cy=\"${m.y}\" r=\"$r\" fill=\"none\" stroke=\"#555\" stroke-width=\"${r / 4}\" stroke-dasharray=\"${r / 2} ${r / 3}\"><title>")
                    .append(e(title)).append("</title></circle>")
                else -> {
                    out.append("<circle cx=\"${m.x}\" cy=\"${m.y}\" r=\"$r\" fill=\"$color\" stroke=\"#fff\" stroke-width=\"${r / 5}\"><title>").append(e(title)).append("</title></circle>")
                    m.rssi?.let { out.append("<text x=\"${m.x}\" y=\"${m.y + r / 3}\" font-size=\"${r * 0.9f}\" text-anchor=\"middle\" fill=\"#fff\">").append(it).append("</text>") }
                }
            }
        }
        out.append("</svg><figcaption class=\"muted\">Verde: buena señal · naranja: débil (por debajo de ").append(weakDbm)
            .append(" dBm, con su valor) · rojo: SSID no detectado en un escaneo válido · anillo discontinuo: sin medición local · puntos pequeños: escaneos continuos, posición aproximada. Las zonas sin marca no tienen muestra.</figcaption></figure>")
    }

    fun stateLabel(state: String) = when (state) {
        PointState.WEAK -> "Señal débil medida"
        PointState.SSID_NOT_DETECTED -> "SSID no detectado en un escaneo válido"
        PointState.NOT_MEASURED -> "Sin medición local"
        else -> "Buena señal"
    }

    private fun categoryLabel(category: String) = when (category) {
        TargetCategory.PRIVATE_IPERF -> "iPerf3 privado (red hasta el servidor propio)"
        TargetCategory.INTERNET -> "Internet"
        else -> "Servicios internos"
    }
}
