package com.mpedreira.nimwifi

import kotlin.math.max
import kotlin.math.roundToLong

/** Outcome of one roaming probe attempt. */
object ProbeResult {
    /** TCP handshake completed: the target port is accessible. */
    const val CONNECTED = "connected"
    /** ICMP echo answered (method `icmp`): the target is reachable. */
    const val REPLIED = "replied"
    /** The target answered with a refusal: reachable, but the port is not accessible. */
    const val REFUSED = "refused"
    const val TIMEOUT = "timeout"
    /** Other connect failure (no route, network error). */
    const val UNREACHABLE = "unreachable"
    /** The name did not resolve on the Wi-Fi network (not evidence of lost connectivity). */
    const val RESOLUTION_FAILED = "resolution_failed"
    /** The target could not be formed (e.g. `${gateway}` without a gateway): configuration, not connectivity. */
    const val INVALID_TARGET = "invalid_target"
    /** No Wi-Fi network: association lost. */
    const val NO_WIFI = "no_wifi"

    fun reachable(result: String) = result == CONNECTED || result == REFUSED || result == REPLIED
    /** Only these count as observed loss of connectivity towards the target. */
    fun connectivityFailure(result: String) = result == TIMEOUT || result == UNREACHABLE
}

/**
 * One probe attempt during a Continuous track. Times are milliseconds on the session's monotonic
 * time base (the same as waypoints and scans), relative to the session start.
 */
@JsonModel
data class ProbeAttempt(
    val startMs: Long,
    val endMs: Long,
    val result: String,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val associationKnown: Boolean = false
) {
    val hasBssid: Boolean get() = associationKnown && bssid != null
}

/**
 * An association change or a reachability loss observed during a Continuous track. [atMillis] is
 * when it was first observed, relative to the session start (monotonic), so the export places it
 * on the route the user marked.
 *
 * Duration is only estimated within the observations: [outageLowerBoundMs] spans the failed
 * attempts actually observed, [outageUpperBoundMs] the time between the last reachable attempt
 * before and the first one after (the resolution of the estimate). With no failed attempt
 * [outageObserved] is false: a change "without observed outage" is not proof of a seamless roam.
 * [open] events had not recovered when the track ended. [scanOverlap] means a Wi-Fi scan
 * overlapped the event window (temporal coincidence, not a cause).
 */
@JsonModel
data class RoamingEvent(
    val type: String,
    val atMillis: Long,
    val fromBssid: String? = null,
    val toBssid: String? = null,
    val fromRssi: Int? = null,
    val toRssi: Int? = null,
    /** Format 1 (fork release 4): gap between successes minus one interval; superseded by the bounds. */
    val outageMillis: Long? = null,
    val lostProbes: Int = 0,
    /** Format 1: a scan flag sampled at a single instant; superseded by [scanOverlap]. */
    val duringScan: Boolean = false,
    val fromSsid: String? = null,
    val toSsid: String? = null,
    val lastReachableAtMillis: Long? = null,
    val firstReachableAtMillis: Long? = null,
    val failedAttempts: Int? = null,
    val outageObserved: Boolean? = null,
    val outageLowerBoundMs: Long? = null,
    val outageUpperBoundMs: Long? = null,
    val open: Boolean? = null,
    /** The event window contains a gap without samples longer than [RoamingTracker.GAP_FACTOR] intervals. */
    val samplingGap: Boolean? = null,
    val scanOverlap: Boolean? = null,
    /** Mejora 12: events of one reachability incident (e.g. two AP hops in one cut) share it. */
    val incidentId: String? = null,
    /** Outcomes (success, failed, cancelled) of the scans that overlapped the event window. */
    val scanOverlapOutcomes: List<String>? = null,
    /** `bounded`, `lower_bound_only` (not recovered), `upper_bound_only` (no failure seen) or `unknown` (gap without samples). */
    val durationEvidence: String? = null
) {
    companion object {
        /** BSSID changed within the same SSID. */
        const val BSSID_CHANGE = "bssid_change"
        const val SSID_CHANGE = "ssid_change"
        /** The Wi-Fi network disappeared (no association). */
        const val ASSOCIATION_LOST = "association_lost"
        /** ≥ [RoamingTracker.MIN_FAILURE_RUN] consecutive failed attempts without an association change. */
        const val REACHABILITY_LOSS = "reachability_loss"

        /** Format 1 event types. */
        const val ROAM = "roam"
        const val OUTAGE = "outage"
    }
}

/** Roaming probe results of one Continuous track. */
@JsonModel
data class RoamingLog(
    /** Configured target (`host:port`, placeholders kept as configured). */
    val target: String,
    /** Requested interval between attempts. */
    val intervalMillis: Int,
    val probes: Int,
    /** Attempts that did not reach the target (any non-reachable result). */
    val lost: Int,
    /** Mean handshake time of the attempts that connected. */
    val avgRttMs: Double?,
    val events: List<RoamingEvent>,
    /** 2 for logs written by [RoamingTracker]; null for format-1 logs (fork release 4). */
    val schema: Int? = null,
    val timeBase: String? = null,
    val observedMedianIntervalMs: Long? = null,
    val observedMaxIntervalMs: Long? = null,
    val connected: Int? = null,
    val refused: Int? = null,
    val timeouts: Int? = null,
    val unreachable: Int? = null,
    val resolutionFailures: Int? = null,
    val invalidTarget: Int? = null,
    val noWifi: Int? = null,
    val samplingGaps: Int? = null,
    /** Mejora 12: display alias of the target and the probe method. */
    val targetAlias: String? = null,
    val method: String? = null,
    /** Attempts actually made against a valid target (connected, refused, timeout, unreachable): the TCP denominator. */
    val tcpAttempts: Int? = null,
    /** Timeouts and unreachable over [tcpAttempts]; DNS, invalid-target and no-Wi-Fi attempts are not in it. */
    val tcpLossPct: Double? = null,
    /** Spans without attempts longer than [RoamingTracker.GAP_FACTOR] intervals: not observed. */
    val unobserved: List<RouteInterruption>? = null,
    val incidents: List<RoamingIncident>? = null,
    /** Optional bounded diagnostic trace of the attempts (enable it in the roaming settings). */
    val trace: List<ProbeAttempt>? = null
)

/** Mejora 12: several events (AP hops, loss) inside one span between reachable attempts. */
@JsonModel
data class RoamingIncident(
    val id: String,
    val startMs: Long,
    val endMs: Long?,
    val events: Int,
    /** Failed attempts of the incident, each counted once. */
    val failedAttempts: Int,
    val lowerBoundMs: Long?,
    val upperBoundMs: Long?,
    val open: Boolean
)

/**
 * Collects the probe attempts of one Continuous track and, when the track ends, turns them into
 * association-change and reachability-loss events whose durations are bounded by what was
 * actually observed. Scheduler delays, pauses, isolated timeouts and gaps without samples are
 * never reported as outages by themselves.
 */
class RoamingTracker(
    private val target: String,
    private val intervalMillis: Int,
    private val targetAlias: String? = null,
    private val traceLimit: Int = 0,
    /** [METHOD] (TCP connect) or [METHOD_ICMP] (one echo per attempt). */
    private val method: String = METHOD
) {
    private val attempts = mutableListOf<ProbeAttempt>()

    /** Attempts so far (for the route draft). */
    fun attempts(): List<ProbeAttempt> = attempts.toList()

    /** Attempts must arrive in order; out-of-order or stale ones are ignored. */
    fun add(attempt: ProbeAttempt) {
        if (attempts.size >= MAX_ATTEMPTS) return
        val last = attempts.lastOrNull()
        if (last != null && attempt.startMs < last.startMs) return
        if (attempt.startMs < 0 || attempt.endMs < attempt.startMs) return
        attempts += attempt
    }

    /** [scanWindows]: start..end of every successful Wi-Fi scan of the track, on the same time base. */
    fun finish(scanWindows: List<LongRange> = emptyList()): RoamingLog =
        finishWindows(scanWindows.map { ScanWindow(it.first, it.last, ScanWindow.SUCCESS) })

    /** [scanWindows]: every scan attempt of the track with its outcome, on the same time base. */
    fun finishWindows(scanWindows: List<ScanWindow>): RoamingLog {
        val events = mutableListOf<RoamingEvent>()
        val covered = BooleanArray(attempts.size)

        // 1. Association changes and losses.
        var previous: Int? = null // last attempt with a known BSSID
        var lostAt: Int? = null   // first attempt without Wi-Fi after a known association
        attempts.forEachIndexed { index, attempt ->
            when {
                attempt.result == ProbeResult.NO_WIFI -> if (previous != null && lostAt == null) lostAt = index
                attempt.hasBssid -> {
                    val before = previous?.let(attempts::get)
                    val lost = lostAt
                    if (lost != null && before != null) {
                        events += associationEvent(RoamingEvent.ASSOCIATION_LOST, before, attempt, lost, index, covered, scanWindows)
                    } else if (before != null && before.bssid != attempt.bssid) {
                        val type = if (before.ssid != null && attempt.ssid != null && before.ssid != attempt.ssid) {
                            RoamingEvent.SSID_CHANGE
                        } else {
                            RoamingEvent.BSSID_CHANGE
                        }
                        events += associationEvent(type, before, attempt, index, index, covered, scanWindows)
                    }
                    lostAt = null
                    previous = index
                }
            }
        }
        // Association lost and never back before the end: open event.
        lostAt?.let { lost ->
            val before = previous?.let(attempts::get)
            events += associationEvent(RoamingEvent.ASSOCIATION_LOST, before, null, lost, attempts.size, covered, scanWindows)
        }

        // 2. Reachability losses: runs of consecutive connectivity failures not explained above.
        var runStart: Int? = null
        fun closeRun(endExclusive: Int) {
            val start = runStart ?: return
            runStart = null
            if (endExclusive - start < MIN_FAILURE_RUN || (start until endExclusive).any { covered[it] }) return
            events += failureEvent(start, endExclusive, scanWindows)
        }
        attempts.forEachIndexed { index, attempt ->
            if (ProbeResult.connectivityFailure(attempt.result)) {
                if (runStart == null) runStart = index
            } else {
                closeRun(index)
            }
        }
        closeRun(attempts.size)

        val withIncidents = incidents(events.sortedWith(compareBy({ it.atMillis }, { it.type })))
        val intervals = attempts.zipWithNext { a, b -> b.startMs - a.startMs }.sorted()
        val connectedTimes = attempts.filter { it.result == ProbeResult.CONNECTED || it.result == ProbeResult.REPLIED }.map { it.endMs - it.startMs }
        val tcp = attempts.count { it.result in TCP_RESULTS }
        val tcpLost = attempts.count { ProbeResult.connectivityFailure(it.result) }
        return RoamingLog(
            target = target,
            intervalMillis = intervalMillis,
            probes = attempts.size,
            lost = attempts.count { !ProbeResult.reachable(it.result) },
            avgRttMs = connectedTimes.takeIf { it.isNotEmpty() }?.average()?.let { (it * 10).roundToLong() / 10.0 },
            events = withIncidents.first,
            schema = 2,
            timeBase = TIME_BASE,
            observedMedianIntervalMs = intervals.getOrNull(intervals.size / 2),
            observedMaxIntervalMs = intervals.lastOrNull(),
            connected = attempts.count { it.result == ProbeResult.CONNECTED },
            refused = attempts.count { it.result == ProbeResult.REFUSED },
            timeouts = attempts.count { it.result == ProbeResult.TIMEOUT },
            unreachable = attempts.count { it.result == ProbeResult.UNREACHABLE },
            resolutionFailures = attempts.count { it.result == ProbeResult.RESOLUTION_FAILED },
            invalidTarget = attempts.count { it.result == ProbeResult.INVALID_TARGET },
            noWifi = attempts.count { it.result == ProbeResult.NO_WIFI },
            samplingGaps = intervals.count { it > gapThreshold() },
            targetAlias = targetAlias,
            method = method,
            tcpAttempts = tcp,
            tcpLossPct = if (tcp > 0) (tcpLost * 1000.0 / tcp).roundToLong() / 10.0 else null,
            unobserved = attempts.zipWithNext().filter { (a, b) -> b.startMs - a.startMs > gapThreshold() }
                .map { (a, b) -> RouteInterruption(a.endMs, b.startMs) },
            incidents = withIncidents.second,
            trace = if (traceLimit > 0) attempts.take(traceLimit) else null
        )
    }

    /**
     * Events whose windows (last reachable attempt before → first one after) overlap belong to
     * one incident. Their failed attempts were assigned once (see [associationEvent]).
     */
    private fun incidents(events: List<RoamingEvent>): Pair<List<RoamingEvent>, List<RoamingIncident>> {
        val out = mutableListOf<RoamingEvent>()
        val incidents = mutableListOf<RoamingIncident>()
        var current: MutableList<RoamingEvent>? = null
        var currentEnd = Long.MIN_VALUE
        fun close() {
            val group = current ?: return
            val id = "inc-${incidents.size + 1}"
            val failed = group.sumOf { it.failedAttempts ?: 0 }
            val start = group.minOf { it.lastReachableAtMillis ?: it.atMillis }
            val end = group.mapNotNull { it.firstReachableAtMillis }.maxOrNull()
            val open = group.any { it.open == true }
            val lower = group.mapNotNull { it.outageLowerBoundMs }.maxOrNull()
            incidents += RoamingIncident(id, start, if (open) null else end, group.size, failed,
                if (failed > 0) lower else lower, if (open) null else end?.let { it - start }, open)
            out += group.map { it.copy(incidentId = if (group.size > 1 || failed > 0) id else null) }
            current = null
        }
        events.forEach { event ->
            val start = event.lastReachableAtMillis ?: event.atMillis
            val end = event.firstReachableAtMillis ?: Long.MAX_VALUE
            if (current != null && start <= currentEnd) {
                current!! += event
                currentEnd = maxOf(currentEnd, end)
            } else {
                close()
                current = mutableListOf(event)
                currentEnd = end
            }
        }
        close()
        // Single events without failures are not incidents of reachability.
        return out to incidents.filter { it.events > 1 || it.failedAttempts > 0 }
    }

    /**
     * Event between [before] (last attempt with the old association) and [after] (first with the
     * new one, null if never). [changeIndex] is the first attempt that showed the change;
     * attempts in the window are marked as explained by this event.
     */
    private fun associationEvent(
        type: String,
        before: ProbeAttempt?,
        after: ProbeAttempt?,
        changeIndex: Int,
        afterIndex: Int,
        covered: BooleanArray,
        scans: List<ScanWindow>
    ): RoamingEvent {
        val lastReachable = lastReachableBefore(changeIndex)
        val firstReachable = firstReachableFrom(afterIndex)
        val windowStart = lastReachable?.let { attempts[it].endMs } ?: before?.endMs ?: attempts[changeIndex.coerceAtMost(attempts.lastIndex)].startMs
        val windowEnd = firstReachable?.let { attempts[it].startMs } ?: attempts.last().endMs
        val from = (lastReachable ?: 0)
        val to = firstReachable ?: attempts.size
        val windowFailed = (from until to).filter { i ->
            ProbeResult.connectivityFailure(attempts[i].result) || attempts[i].result == ProbeResult.NO_WIFI
        }
        // Mejora 12: a failed attempt already explained by an earlier event of the same span is
        // not counted again (two AP hops in one cut share its failures).
        val failed = windowFailed.filterNot { covered[it] }
        windowFailed.forEach { covered[it] = true }
        val gap = hasGap(from, to)
        val overlapping = overlapping(windowStart, windowEnd, scans)
        return RoamingEvent(
            type = type,
            atMillis = attempts[changeIndex.coerceAtMost(attempts.lastIndex)].startMs,
            fromSsid = before?.ssid,
            toSsid = after?.ssid,
            fromBssid = before?.bssid,
            toBssid = after?.bssid,
            fromRssi = before?.rssi,
            toRssi = after?.rssi,
            lastReachableAtMillis = lastReachable?.let { attempts[it].endMs },
            firstReachableAtMillis = firstReachable?.let { attempts[it].startMs },
            failedAttempts = failed.size,
            outageObserved = failed.isNotEmpty(),
            outageLowerBoundMs = when {
                failed.isNotEmpty() -> attempts[failed.last()].endMs - attempts[failed.first()].startMs
                gap -> null
                else -> 0L
            },
            outageUpperBoundMs = if (lastReachable != null && firstReachable != null) windowEnd - windowStart else null,
            open = firstReachable == null,
            samplingGap = gap,
            scanOverlap = overlapping.isNotEmpty(),
            scanOverlapOutcomes = overlapping.map { it.outcome }.distinct().takeIf { it.isNotEmpty() },
            lostProbes = failed.size,
            durationEvidence = evidence(windowFailed.isNotEmpty(), firstReachable == null, gap, lastReachable != null && firstReachable != null)
        )
    }

    private fun evidence(failures: Boolean, open: Boolean, gap: Boolean, bothSides: Boolean): String = when {
        open -> if (failures) LOWER_BOUND_ONLY else UNKNOWN_DURATION
        !failures && gap -> UNKNOWN_DURATION
        !failures -> UPPER_BOUND_ONLY
        bothSides -> BOUNDED
        else -> LOWER_BOUND_ONLY
    }

    private fun failureEvent(start: Int, endExclusive: Int, scans: List<ScanWindow>): RoamingEvent {
        val first = attempts[start]
        val last = attempts[endExclusive - 1]
        val lastReachable = lastReachableBefore(start)
        val firstReachable = firstReachableFrom(endExclusive)
        val windowStart = lastReachable?.let { attempts[it].endMs } ?: first.startMs
        val windowEnd = firstReachable?.let { attempts[it].startMs } ?: last.endMs
        return RoamingEvent(
            type = RoamingEvent.REACHABILITY_LOSS,
            atMillis = first.startMs,
            fromSsid = first.ssid, toSsid = last.ssid,
            fromBssid = first.bssid, toBssid = last.bssid,
            fromRssi = first.rssi, toRssi = last.rssi,
            lastReachableAtMillis = lastReachable?.let { attempts[it].endMs },
            firstReachableAtMillis = firstReachable?.let { attempts[it].startMs },
            failedAttempts = endExclusive - start,
            outageObserved = true,
            outageLowerBoundMs = last.endMs - first.startMs,
            outageUpperBoundMs = if (lastReachable != null && firstReachable != null) windowEnd - windowStart else null,
            open = firstReachable == null,
            samplingGap = hasGap(start, endExclusive),
            scanOverlap = overlapping(windowStart, windowEnd, scans).isNotEmpty(),
            scanOverlapOutcomes = overlapping(windowStart, windowEnd, scans).map { it.outcome }.distinct().takeIf { it.isNotEmpty() },
            lostProbes = endExclusive - start,
            durationEvidence = evidence(true, firstReachable == null, hasGap(start, endExclusive), lastReachable != null && firstReachable != null)
        )
    }

    private fun lastReachableBefore(index: Int): Int? =
        (index - 1 downTo 0).firstOrNull { ProbeResult.reachable(attempts[it].result) }

    private fun firstReachableFrom(index: Int): Int? =
        (index until attempts.size).firstOrNull { ProbeResult.reachable(attempts[it].result) }

    private fun hasGap(from: Int, toExclusive: Int): Boolean =
        (max(from, 1) until minOf(toExclusive + 1, attempts.size)).any { attempts[it].startMs - attempts[it - 1].startMs > gapThreshold() }

    private fun gapThreshold(): Long = GAP_FACTOR * intervalMillis.toLong()

    /** Whole-interval overlap: every scan attempt (any outcome) that intersects [start, end]. */
    private fun overlapping(start: Long, end: Long, scans: List<ScanWindow>): List<ScanWindow> =
        scans.filter { scan -> scan.startMs <= end && scan.endMs >= start }

    companion object {
        const val TIME_BASE = "monotonic-relative"
        /** Consecutive failed attempts needed for a reachability loss; fewer are isolated failures. */
        const val MIN_FAILURE_RUN = 3
        /** An interval between attempts longer than this many requested intervals is a sampling gap. */
        const val GAP_FACTOR = 3L
        private const val MAX_ATTEMPTS = 100_000
        const val METHOD = "tcp-connect"
        const val METHOD_ICMP = "icmp-echo"
        /** Upper bound of the optional trace. */
        const val MAX_TRACE = 3_000
        const val BOUNDED = "bounded"
        const val LOWER_BOUND_ONLY = "lower_bound_only"
        const val UPPER_BOUND_ONLY = "upper_bound_only"
        const val UNKNOWN_DURATION = "unknown"
        /** Attempts made against a valid target, whatever the method: the probe denominator. */
        private val TCP_RESULTS = setOf(ProbeResult.CONNECTED, ProbeResult.REFUSED, ProbeResult.REPLIED, ProbeResult.TIMEOUT, ProbeResult.UNREACHABLE)
    }
}
