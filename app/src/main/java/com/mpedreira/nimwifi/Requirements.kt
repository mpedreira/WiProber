package com.mpedreira.nimwifi

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Optional acceptance criteria of a survey (`requirements` in `wiprober-config.json`): what a
 * warehouse running handhelds needs from each SSID. Every threshold is the user's: nothing here
 * has a default value. RC4 · 5: coverage and the second AP are always judged with the signal
 * profile ([SignalProfile]), as on the map; these criteria add performance, services, bands and
 * roaming when declared.
 */
@JsonModel
data class Requirements(
    /** Private iPerf3 targets, matched by alias (the configured target label). */
    val throughput: List<ThroughputRequirement>? = null,
    /** Network tests (DNS, ping, TCP, HTTP), matched by alias (or name when it has no alias). */
    val networkTests: List<ServiceRequirement>? = null,
    val roaming: RoamingRequirement? = null,
    /** Minimum signal of the SSID in a given band (e.g. 5 GHz), on top of the profile's threshold. */
    val bands: List<BandRequirement>? = null
) {
    val isEmpty: Boolean get() = throughput.isNullOrEmpty() && networkTests.isNullOrEmpty() && roaming == null && bands.isNullOrEmpty()
}

@JsonModel
data class BandRequirement(
    /** [WifiBands.B24], [WifiBands.B5] or [WifiBands.B6]. */
    val band: String,
    /** The strongest AP of the SSID in that band at the point must be at least this (dBm). */
    val minRssiDbm: Double
)

@JsonModel
data class ThroughputRequirement(
    val alias: String,
    val minDownMbps: Double? = null,
    val minUpMbps: Double? = null,
    /** The point's TCP-connect latency (itself the median of its samples). */
    val maxLatencyMedianMs: Double? = null,
    /** 95th percentile (nearest rank) of the points' latencies of the SSID on the floor. */
    val maxLatencyP95Ms: Double? = null,
    /** Failed handshakes over latency samples, %. */
    val maxLatencyLossPct: Double? = null
)

@JsonModel
data class ServiceRequirement(
    val alias: String,
    /** The point's value (DNS lookup, ping average RTT, TCP connect or HTTP total). */
    val maxMedianMs: Double? = null,
    val maxP95Ms: Double? = null,
    /** Ping loss, %. */
    val maxLossPct: Double? = null
)

@JsonModel
data class RoamingRequirement(
    /** Longest acceptable loss window of one outage (upper bound of the observed outage). */
    val maxOutageMs: Long? = null,
    /** Most reachability/association losses acceptable on one floor for one SSID. */
    val maxLossesPerFloor: Int? = null
)

/** cumple / no cumple / no concluyente (missing data). */
object VerdictState {
    const val PASS = "pass"
    const val FAIL = "fail"
    const val MISSING = "missing_data"

    /** A failure decides; otherwise any missing datum; otherwise it passes. Nothing to judge: missing. */
    fun combine(states: Collection<String>): String = when {
        FAIL in states -> FAIL
        MISSING in states || states.isEmpty() -> MISSING
        else -> PASS
    }

    fun label(state: String): String = when (state) {
        PASS -> "cumple"
        FAIL -> "no cumple"
        else -> "no concluyente"
    }

    /** RC4 · 5: the heading of a section's decision. */
    fun title(state: String): String = label(state).replaceFirstChar { it.uppercase() }
}

/**
 * One requirement judged once. [value] null means it was not measured validly (never 0): the
 * check is then [VerdictState.MISSING] with the reason. [runId] is the run the value comes from.
 */
data class Check(
    val requirement: String,
    val label: String,
    val state: String,
    val value: Double?,
    val threshold: Double?,
    val unit: String,
    val runId: String? = null,
    val reason: String? = null
)

data class PointVerdict(val pointId: String, val label: String, val state: String, val checks: List<Check>) {
    val failed: List<Check> get() = checks.filter { it.state == VerdictState.FAIL }
}

/** The verdict of one SSID on one floor (each SSID is judged on its own). */
data class SsidVerdict(
    val ssid: String,
    val floorName: String,
    val state: String,
    val points: List<PointVerdict>,
    /** Checks of the SSID on the floor as a whole (p95, roaming outages and their count). */
    val floorChecks: List<Check>,
    /** Reachability/association losses of this SSID on the floor, always reported (also without a roaming requirement). */
    val outagesOnFloor: Int = 0,
    /** RC4 · 5: what is judged (signal profile always; declared criteria) and what is not declared. */
    val scope: String = "",
    /** RC4 · 5: what remains to be measured for a conclusive verdict, grouped by requirement. */
    val pending: List<String> = emptyList()
) {
    /** RC4 · 5: why the verdict is not conclusive; null when it is. */
    val inconclusiveReason: String?
        get() = if (state != VerdictState.MISSING) null else when {
            points.isEmpty() && floorChecks.isEmpty() -> "ningún punto del SSID en esta planta"
            withData == 0 -> "ningún punto con muestras válidas de todos los criterios"
            else -> "${missing} de ${points.size} puntos sin muestras válidas de algún criterio"
        }

    val passing: Int get() = points.count { it.state == VerdictState.PASS }
    val failing: Int get() = points.count { it.state == VerdictState.FAIL }
    val missing: Int get() = points.count { it.state == VerdictState.MISSING }
    /** Points with valid data for every requirement (the denominator of [percentPassing]). */
    val withData: Int get() = passing + failing
    val percentPassing: Double? get() = if (withData == 0) null else ((passing * 1000.0 / withData).roundToLong() / 10.0)
}

/**
 * Android-free evaluation of [Requirements] for one SSID on one floor. Inputs are what the report
 * already computes: per-point coverage ([SsidCoverage.points], with the profile's RSSI threshold),
 * the report's runs in scope, and the floor's events with their identity.
 */
object VerdictEvaluator {
    private val OUTAGES = setOf(RoamingEvent.REACHABILITY_LOSS, RoamingEvent.ASSOCIATION_LOST, RoamingEvent.OUTAGE)

    fun evaluate(
        requirements: Requirements,
        ssid: String,
        floorName: String,
        coverage: List<PointCoverage>,
        runs: List<ActiveRun>,
        events: List<SurveyReport.FloorEvent>,
        weakDbm: Int,
        apNames: ApLabels = ApLabels.EMPTY,
        /** RC4 · 5: the profile's second-AP threshold; null skips the second-AP check (as before RC4). */
        secondaryDbm: Int? = null
    ): SsidVerdict {
        val ssidEvents = events.filter { it.belongsTo(ssid) }
        val points = coverage.map { point -> pointVerdict(requirements, ssid, point, runs.filter { it.pointId == point.pointId }, ssidEvents, weakDbm, apNames, secondaryDbm) }
        val floorChecks = floorChecks(requirements, ssid, coverage, runs, ssidEvents, apNames)
        val state = VerdictState.combine(points.map { it.state } + floorChecks.map { it.state })
        return SsidVerdict(ssid, floorName, state, points, floorChecks, ssidEvents.filter { it.outage }.distinctBy { it.event.key }.size,
            scope = scope(requirements, weakDbm, secondaryDbm), pending = pending(points, floorChecks))
    }

    /** What the verdict judges and what was not declared (so it is not judged). */
    private fun scope(requirements: Requirements, weakDbm: Int, secondaryDbm: Int?): String {
        val judged = listOfNotNull(
            "cobertura (AP más fuerte ≥ $weakDbm dBm)",
            secondaryDbm?.let { "segundo AP (≥ $it dBm)" },
            "bandas".takeIf { !requirements.bands.isNullOrEmpty() },
            "rendimiento iPerf3".takeIf { !requirements.throughput.isNullOrEmpty() },
            "servicios".takeIf { !requirements.networkTests.isNullOrEmpty() },
            "roaming".takeIf { requirements.roaming != null }
        )
        val notDeclared = listOfNotNull(
            "rendimiento".takeIf { requirements.throughput.isNullOrEmpty() },
            "servicios".takeIf { requirements.networkTests.isNullOrEmpty() },
            "roaming".takeIf { requirements.roaming == null }
        )
        return "Se juzga: " + judged.joinToString(", ") + "." +
            (if (notDeclared.isEmpty()) "" else " Sin criterios declarados de " + notDeclared.joinToString(", ") + ": no deciden el veredicto.")
    }

    /** Per requirement, the points without a valid sample and why (the first reasons). */
    private fun pending(points: List<PointVerdict>, floorChecks: List<Check>): List<String> {
        val byCheck = linkedMapOf<String, MutableList<Pair<String, String?>>>()
        points.forEach { p -> p.checks.filter { it.state == VerdictState.MISSING }.forEach { c -> byCheck.getOrPut(c.label) { mutableListOf() } += p.label to c.reason } }
        return byCheck.map { (label, list) ->
            val reasons = list.mapNotNull { it.second }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} ${it.value}" }
            "$label: ${list.size} puntos (${list.take(8).joinToString(", ") { it.first }}${if (list.size > 8) "…" else ""})" +
                (if (reasons.isNotEmpty()) " — $reasons" else "")
        } + floorChecks.filter { it.state == VerdictState.MISSING }.map { "${it.label} (planta): ${it.reason ?: "sin muestras válidas"}" }
    }

    private fun pointVerdict(
        requirements: Requirements,
        ssid: String,
        point: PointCoverage,
        runs: List<ActiveRun>,
        events: List<SurveyReport.FloorEvent>,
        weakDbm: Int,
        apNames: ApLabels,
        secondaryDbm: Int?
    ): PointVerdict {
        val checks = mutableListOf<Check>()
        checks += signal(point, weakDbm, apNames)
        secondaryDbm?.let { secondAp(point, it, apNames) }?.let { checks += it }
        requirements.bands.orEmpty().forEach { req -> checks += bandSignal(point, req, apNames) }
        val ordered = runs.sortedBy { it.startedAt }
        requirements.throughput.orEmpty().forEach { req ->
            val attempts = ordered.flatMap { run -> run.results?.tests.orEmpty().filter { it.ssid == ssid && isTarget(it, req.alias) }.map { run to it } }
            val why = missingReason(attempts.map { it.first }, ordered)
            fun last(read: (ThroughputTestResult) -> Double?) = attempts.lastOrNull { read(it.second) != null }
            last { it.downMbps }.let { checks += atLeast("iperf3:${req.alias}:download", "iPerf3 ${req.alias} bajada", it?.second?.downMbps, req.minDownMbps, "Mbit/s", it?.first?.runId, why) }
            last { it.upMbps }.let { checks += atLeast("iperf3:${req.alias}:upload", "iPerf3 ${req.alias} subida", it?.second?.upMbps, req.minUpMbps, "Mbit/s", it?.first?.runId, why) }
            last { it.latencyMs }.let { checks += atMost("iperf3:${req.alias}:latency", "Latencia ${req.alias}", it?.second?.latencyMs, req.maxLatencyMedianMs, "ms", it?.first?.runId, why) }
            last { latencyLoss(it) }.let { checks += atMost("iperf3:${req.alias}:latency-loss", "Pérdida de muestras ${req.alias}", it?.second?.let(::latencyLoss), req.maxLatencyLossPct, "%", it?.first?.runId, why) }
        }
        requirements.networkTests.orEmpty().forEach { req ->
            val attempts = ordered.flatMap { run -> run.results?.networkTests.orEmpty().filter { it.ssid == ssid && (it.alias ?: it.name).equals(req.alias, ignoreCase = true) }.map { run to it } }
            val why = missingReason(attempts.map { it.first }, ordered) ?: attempts.lastOrNull()?.second?.errorCode?.let { "última prueba: $it" }
            val valued = attempts.lastOrNull { serviceValue(it.second) != null }
            checks += atMost("service:${req.alias}:time", "${req.alias} (tiempo)", valued?.second?.let(::serviceValue), req.maxMedianMs, "ms", valued?.first?.runId, why)
            // A ping with 100 % loss is a measured loss, not a missing datum.
            val lossy = attempts.lastOrNull { it.second.lossPct != null }
            checks += atMost("service:${req.alias}:loss", "${req.alias} (pérdida)", lossy?.second?.lossPct, req.maxLossPct, "%", lossy?.first?.runId, why)
        }
        // A good median must not hide a cut: a loss of reachability inside the point's measurement
        // window makes the point fail and says so. An AP change without loss is only explained.
        if (!requirements.isEmpty && ordered.isNotEmpty()) {
            val windows = ordered.mapNotNull { run -> run.endedAt?.let { run.startedAt..it } }
            val inside = events.filter { e -> e.outage && e.event.at != null && windows.any { e.event.at in it } }
            inside.forEach { e ->
                checks += Check("outage-during-measurement", "Corte durante la medición", VerdictState.FAIL, e.event.outageUpperMs?.toDouble() ?: e.event.outageLowerMs?.toDouble(),
                    requirements.roaming?.maxOutageMs?.toDouble(), "ms", runIdAt(ordered, e.event.at!!), listOf(e.event.kind, apNames.between(e.event.from, e.event.to)).filter { it.isNotEmpty() }.joinToString(" "))
            }
            val apChanges = events.count { e -> !e.outage && e.event.at != null && windows.any { e.event.at in it } } +
                ordered.sumOf { run -> run.results?.tests.orEmpty().count { it.ssid == ssid && it.associationChange == AssociationChange.BSSID_CHANGED } }
            if (apChanges > 0 && checks.none { it.requirement == "ap-change-during-measurement" }) {
                checks += Check("ap-change-during-measurement", "Cambio de AP durante la medición", VerdictState.PASS, apChanges.toDouble(), null, "cambios",
                    reason = "sin pérdida observada; se informa como contexto")
            }
        }
        return PointVerdict(point.pointId, point.label, VerdictState.combine(checks.map { it.state }), checks)
    }

    /** The profile's RSSI threshold, on the latest valid local scan of the point. */
    private fun signal(point: PointCoverage, weakDbm: Int, apNames: ApLabels): Check {
        val ap = point.strongestBssid?.let { ": ${apNames.label(it)}" }.orEmpty()
        return when (point.state) {
            PointState.OK -> Check("rssi", "Señal", VerdictState.PASS, point.strongestRssi?.toDouble(), weakDbm.toDouble(), "dBm", reason = "AP más fuerte$ap")
            PointState.WEAK -> Check("rssi", "Señal", VerdictState.FAIL, point.strongestRssi?.toDouble(), weakDbm.toDouble(), "dBm", reason = "AP más fuerte$ap")
            PointState.SSID_NOT_DETECTED -> Check("rssi", "Señal", VerdictState.FAIL, null, weakDbm.toDouble(), "dBm", reason = "SSID no detectado en un escaneo válido")
            else -> Check("rssi", "Señal", VerdictState.MISSING, null, weakDbm.toDouble(), "dBm",
                reason = when {
                    point.planned -> "punto planificado sin medir"
                    point.imported -> "punto importado sin RF local"
                    else -> "sin escaneo válido"
                })
        }
    }

    /**
     * RC4 · 5: the profile's second AP (redundancy for roaming) on the same latest valid scan. Not
     * judged where the SSID was not heard at all (the signal check already fails there).
     */
    private fun secondAp(point: PointCoverage, secondaryDbm: Int, apNames: ApLabels): Check? = when (point.state) {
        PointState.SSID_NOT_DETECTED -> null
        PointState.NOT_MEASURED -> Check("second-ap", "Segundo AP", VerdictState.MISSING, null, secondaryDbm.toDouble(), "dBm",
            reason = when {
                point.planned -> "punto planificado sin medir"
                point.imported -> "punto importado sin RF local"
                else -> "sin escaneo válido"
            })
        else -> {
            val rssi = point.secondRssi
            when {
                rssi == null -> Check("second-ap", "Segundo AP", VerdictState.FAIL, null, secondaryDbm.toDouble(), "dBm", reason = "un solo AP del SSID oído en el punto")
                rssi >= secondaryDbm -> Check("second-ap", "Segundo AP", VerdictState.PASS, rssi.toDouble(), secondaryDbm.toDouble(), "dBm",
                    reason = "segundo AP: ${apNames.label(point.secondBssid)}")
                else -> Check("second-ap", "Segundo AP", VerdictState.FAIL, rssi.toDouble(), secondaryDbm.toDouble(), "dBm",
                    reason = "segundo AP: ${apNames.label(point.secondBssid)}")
            }
        }
    }

    /**
     * The SSID's strongest AP in one band. A valid scan that did not hear the SSID in that band
     * fails (the band is not there); a point without a valid scan lacks data.
     */
    private fun bandSignal(point: PointCoverage, req: BandRequirement, apNames: ApLabels): Check {
        val id = "rssi:${req.band}"
        val label = "Señal ${WifiBands.label(req.band)}"
        if (point.state == PointState.NOT_MEASURED) {
            return Check(id, label, VerdictState.MISSING, null, req.minRssiDbm, "dBm", reason = if (point.planned) "punto planificado sin medir" else "sin escaneo válido")
        }
        val heard = point.band(req.band)
            ?: return Check(id, label, VerdictState.FAIL, null, req.minRssiDbm, "dBm", reason = "SSID no oído en ${WifiBands.label(req.band)} en un escaneo válido")
        return Check(id, label, if (heard.rssi >= req.minRssiDbm) VerdictState.PASS else VerdictState.FAIL, heard.rssi.toDouble(), req.minRssiDbm, "dBm",
            reason = "AP más fuerte en la banda: ${apNames.label(heard.bssid)}")
    }

    /** p95 of the series' point values, roaming outages and their count, per SSID and floor. */
    private fun floorChecks(requirements: Requirements, ssid: String, coverage: List<PointCoverage>, runs: List<ActiveRun>, events: List<SurveyReport.FloorEvent>,
                            apNames: ApLabels): List<Check> {
        val checks = mutableListOf<Check>()
        val pointIds = coverage.map { it.pointId }.toSet()
        fun lastPerPoint(values: List<Pair<ActiveRun, Double?>>) = values.filter { it.second != null && it.first.pointId in pointIds }
            .groupBy { it.first.pointId }.values.map { list -> list.maxBy { it.first.startedAt }.second!! }
        requirements.throughput.orEmpty().forEach { req ->
            req.maxLatencyP95Ms ?: return@forEach
            val values = lastPerPoint(runs.flatMap { run -> run.results?.tests.orEmpty().filter { it.ssid == ssid && isTarget(it, req.alias) }.map { run to it.latencyMs } })
            checks += p95Check("iperf3:${req.alias}:latency-p95", "Latencia ${req.alias} p95", values, req.maxLatencyP95Ms)
        }
        requirements.networkTests.orEmpty().forEach { req ->
            req.maxP95Ms ?: return@forEach
            val values = lastPerPoint(runs.flatMap { run ->
                run.results?.networkTests.orEmpty().filter { it.ssid == ssid && (it.alias ?: it.name).equals(req.alias, ignoreCase = true) }.map { run to serviceValue(it) }
            })
            checks += p95Check("service:${req.alias}:p95", "${req.alias} p95", values, req.maxP95Ms)
        }
        requirements.roaming?.let { roaming ->
            val outages = events.filter { it.outage && it.event.kind in OUTAGES }.distinctBy { it.event.key }
            roaming.maxOutageMs?.let { max ->
                outages.forEach { o ->
                    val e = o.event
                    // The upper bound is what the observations allow; an open outage at least its lower bound.
                    val duration = e.outageUpperMs ?: e.outageLowerMs?.takeIf { e.open }
                    checks += if (duration == null) {
                        Check("roaming:outage", "Ventana de pérdida", VerdictState.MISSING, null, max.toDouble(), "ms", reason = "duración no estimable (${e.kind} ${e.detail.orEmpty()})")
                    } else {
                        Check("roaming:outage", "Ventana de pérdida", if (duration <= max && !e.open) VerdictState.PASS else VerdictState.FAIL,
                            duration.toDouble(), max.toDouble(), "ms", reason = listOfNotNull(e.kind, apNames.between(e.from, e.to).ifEmpty { null }, if (e.open) "sin recuperar" else null).joinToString(" · ") + " · " + (e.key))
                    }
                }
            }
            roaming.maxLossesPerFloor?.let { max ->
                checks += Check("roaming:losses", "Pérdidas en la planta", if (outages.size <= max) VerdictState.PASS else VerdictState.FAIL,
                    outages.size.toDouble(), max.toDouble(), "pérdidas")
            }
        }
        return checks
    }

    private fun p95Check(id: String, label: String, values: List<Double>, max: Double): Check {
        if (values.isEmpty()) return Check(id, label, VerdictState.MISSING, null, max, "ms", reason = "sin muestras válidas")
        val p95 = percentile(values, 95.0)
        return Check(id, label, if (p95 <= max) VerdictState.PASS else VerdictState.FAIL, p95, max, "ms", reason = "${values.size} puntos")
    }

    /** Nearest-rank percentile (no interpolation): with few points it is the largest values. */
    fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun atLeast(id: String, label: String, value: Double?, min: Double?, unit: String, runId: String?, why: String?): List<Check> = when {
        min == null -> emptyList()
        value == null -> listOf(Check(id, label, VerdictState.MISSING, null, min, unit, runId, why ?: "sin medición válida"))
        else -> listOf(Check(id, label, if (value >= min) VerdictState.PASS else VerdictState.FAIL, value, min, unit, runId))
    }

    private fun atMost(id: String, label: String, value: Double?, max: Double?, unit: String, runId: String?, why: String?): List<Check> = when {
        max == null -> emptyList()
        value == null -> listOf(Check(id, label, VerdictState.MISSING, null, max, unit, runId, why ?: "sin medición válida"))
        else -> listOf(Check(id, label, if (value <= max) VerdictState.PASS else VerdictState.FAIL, value, max, unit, runId))
    }

    /** Why a point has no valid value: no run at all, or how its last run ended. */
    private fun missingReason(withTarget: List<ActiveRun>, all: List<ActiveRun>): String? {
        val last = (withTarget.lastOrNull() ?: all.lastOrNull()) ?: return "sin ejecución en el punto"
        return when (last.status) {
            RunStatus.SKIPPED -> "ejecución omitida" + (last.statusReason?.let { " ($it)" } ?: "")
            RunStatus.CANCELLED -> "ejecución cancelada"
            RunStatus.INTERRUPTED -> "ejecución interrumpida"
            RunStatus.RUNNING -> "ejecución sin terminar"
            RunStatus.FAILED -> "ejecución fallida" + (last.statusReason?.let { " ($it)" } ?: "")
            else -> if (withTarget.isEmpty()) "sin resultado de este destino" else null
        }
    }

    private fun isTarget(t: ThroughputTestResult, alias: String) = t.target.trim().equals(alias.trim(), ignoreCase = true) &&
        (t.category == TargetCategory.PRIVATE_IPERF || (t.category == null && t.method == ThroughputTestResult.METHOD_IPERF3_TCP))

    private fun latencyLoss(t: ThroughputTestResult): Double? {
        val samples = t.latencySamples ?: return null
        val failures = t.latencyFailures ?: return null
        if (samples <= 0) return null
        return (failures * 1000.0 / samples).roundToLong() / 10.0
    }

    /** The point's value of a network test, only when it succeeded (a failure is not a time). */
    fun serviceValue(t: NetworkTestResult): Double? = if (!t.ok) null else when (t.type) {
        NetworkTest.DNS -> t.dnsMs
        NetworkTest.PING -> t.rttAvgMs
        NetworkTest.TCP -> t.connectMs
        else -> t.totalMs
    }

    private fun runIdAt(runs: List<ActiveRun>, at: Long): String? = runs.lastOrNull { run -> run.endedAt?.let { at in run.startedAt..it } == true }?.runId
}
