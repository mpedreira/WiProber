package com.example.wiprober

import kotlin.math.max
import kotlin.math.roundToLong

/** Outcome of one roaming probe attempt. */
object ProbeResult {
    /** TCP handshake completed: the target port is accessible. */
    const val CONNECTED = "connected"
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

    fun reachable(result: String) = result == CONNECTED || result == REFUSED
    /** Only these count as observed loss of connectivity towards the target. */
    fun connectivityFailure(result: String) = result == TIMEOUT || result == UNREACHABLE
}

/**
 * One probe attempt during a Continuous track. Times are milliseconds on the session's monotonic
 * time base (the same as waypoints and scans), relative to the session start.
 */
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
    val scanOverlap: Boolean? = null
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
    val samplingGaps: Int? = null
)

/**
 * Collects the probe attempts of one Continuous track and, when the track ends, turns them into
 * association-change and reachability-loss events whose durations are bounded by what was
 * actually observed. Scheduler delays, pauses, isolated timeouts and gaps without samples are
 * never reported as outages by themselves.
 */
class RoamingTracker(private val target: String, private val intervalMillis: Int) {
    private val attempts = mutableListOf<ProbeAttempt>()

    /** Attempts must arrive in order; out-of-order or stale ones are ignored. */
    fun add(attempt: ProbeAttempt) {
        if (attempts.size >= MAX_ATTEMPTS) return
        val last = attempts.lastOrNull()
        if (last != null && attempt.startMs < last.startMs) return
        if (attempt.startMs < 0 || attempt.endMs < attempt.startMs) return
        attempts += attempt
    }

    /** [scanWindows]: start..end of every Wi-Fi scan of the track, on the same time base. */
    fun finish(scanWindows: List<LongRange> = emptyList()): RoamingLog {
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

        val intervals = attempts.zipWithNext { a, b -> b.startMs - a.startMs }.sorted()
        val connectedTimes = attempts.filter { it.result == ProbeResult.CONNECTED }.map { it.endMs - it.startMs }
        return RoamingLog(
            target = target,
            intervalMillis = intervalMillis,
            probes = attempts.size,
            lost = attempts.count { !ProbeResult.reachable(it.result) },
            avgRttMs = connectedTimes.takeIf { it.isNotEmpty() }?.average()?.let { (it * 10).roundToLong() / 10.0 },
            events = events.sortedBy { it.atMillis },
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
            samplingGaps = intervals.count { it > gapThreshold() }
        )
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
        scans: List<LongRange>
    ): RoamingEvent {
        val lastReachable = lastReachableBefore(changeIndex)
        val firstReachable = firstReachableFrom(afterIndex)
        val windowStart = lastReachable?.let { attempts[it].endMs } ?: before?.endMs ?: attempts[changeIndex.coerceAtMost(attempts.lastIndex)].startMs
        val windowEnd = firstReachable?.let { attempts[it].startMs } ?: attempts.last().endMs
        val from = (lastReachable ?: 0)
        val to = firstReachable ?: attempts.size
        val failed = (from until to).filter { i ->
            ProbeResult.connectivityFailure(attempts[i].result) || attempts[i].result == ProbeResult.NO_WIFI
        }
        failed.forEach { covered[it] = true }
        val gap = hasGap(from, to)
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
            scanOverlap = overlaps(windowStart, windowEnd, scans),
            lostProbes = failed.size
        )
    }

    private fun failureEvent(start: Int, endExclusive: Int, scans: List<LongRange>): RoamingEvent {
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
            scanOverlap = overlaps(windowStart, windowEnd, scans),
            lostProbes = endExclusive - start
        )
    }

    private fun lastReachableBefore(index: Int): Int? =
        (index - 1 downTo 0).firstOrNull { ProbeResult.reachable(attempts[it].result) }

    private fun firstReachableFrom(index: Int): Int? =
        (index until attempts.size).firstOrNull { ProbeResult.reachable(attempts[it].result) }

    private fun hasGap(from: Int, toExclusive: Int): Boolean =
        (max(from, 1) until minOf(toExclusive + 1, attempts.size)).any { attempts[it].startMs - attempts[it - 1].startMs > gapThreshold() }

    private fun gapThreshold(): Long = GAP_FACTOR * intervalMillis.toLong()

    /** Whole-interval overlap: any scan that intersects [start, end]. */
    private fun overlaps(start: Long, end: Long, scans: List<LongRange>): Boolean =
        scans.any { scan -> scan.first <= end && scan.last >= start }

    companion object {
        const val TIME_BASE = "monotonic-relative"
        /** Consecutive failed attempts needed for a reachability loss; fewer are isolated failures. */
        const val MIN_FAILURE_RUN = 3
        /** An interval between attempts longer than this many requested intervals is a sampling gap. */
        const val GAP_FACTOR = 3L
        private const val MAX_ATTEMPTS = 100_000
    }
}
