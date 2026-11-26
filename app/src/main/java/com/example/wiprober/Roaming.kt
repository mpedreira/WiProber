package com.example.wiprober

import kotlin.math.roundToLong

/**
 * A roam (the associated BSSID changed) or an outage (probes failing without a roam) during a
 * Continuous track. [atMillis] is relative to the session start, like route waypoints, so the
 * export places it on the route the user marked on the map.
 * [outageMillis]: time without a successful probe around the event beyond one probe interval
 * (0 = seamless); null if connectivity had not come back when the track ended.
 */
@JsonModel
data class RoamingEvent(
    val type: String,
    val atMillis: Long,
    val fromBssid: String? = null,
    val toBssid: String? = null,
    val fromRssi: Int? = null,
    val toRssi: Int? = null,
    val outageMillis: Long? = null,
    val lostProbes: Int = 0,
    /** A Wi-Fi scan ran during the outage: the phone was off channel, so it may be partly the scan. */
    val duringScan: Boolean = false
) {
    companion object {
        const val ROAM = "roam"
        const val OUTAGE = "outage"
    }
}

/** Roaming probe results of one Continuous track. */
@JsonModel
data class RoamingLog(
    val target: String,
    val intervalMillis: Int,
    val probes: Int,
    val lost: Int,
    val avgRttMs: Double?,
    val events: List<RoamingEvent>
)

/**
 * Turns the probe samples of a track (every [intervalMillis]: associated BSSID, signal and
 * whether a TCP connect to the target succeeded) into roam and outage events.
 */
class RoamingTracker(private val target: String, private val intervalMillis: Int) {
    private var probes = 0
    private var lost = 0
    private var rttSum = 0.0
    private var rttCount = 0
    private val events = mutableListOf<RoamingEvent>()

    private var lastBssid: String? = null
    private var lastRssi: Int? = null
    private var lastOkAt: Long? = null
    private var pendingRoam: RoamingEvent? = null
    private var failuresSince: Long? = null
    private var failureCount = 0
    private var scanInWindow = false

    fun add(atMillis: Long, bssid: String?, rssi: Int?, ok: Boolean, rttMs: Double?, scanning: Boolean) {
        probes++
        if (bssid != null && lastBssid != null && bssid != lastBssid) {
            // A roam still waiting for connectivity is closed as is; this is a new one.
            pendingRoam?.let { events += it.copy(outageMillis = null) }
            pendingRoam = RoamingEvent(
                type = RoamingEvent.ROAM, atMillis = atMillis,
                fromBssid = lastBssid, toBssid = bssid, fromRssi = lastRssi, toRssi = rssi,
                lostProbes = failureCount
            )
        }
        if (bssid != null) {
            lastBssid = bssid
            lastRssi = rssi
        }
        if (scanning) scanInWindow = true

        if (!ok) {
            lost++
            if (failuresSince == null) failuresSince = atMillis
            failureCount++
            pendingRoam = pendingRoam?.copy(lostProbes = pendingRoam!!.lostProbes + 1)
            return
        }

        rttMs?.let { rttSum += it; rttCount++ }
        val gapStart = lastOkAt
        val outage = gapStart?.let { (atMillis - it - intervalMillis).coerceAtLeast(0L) }
        val roam = pendingRoam
        when {
            roam != null -> events += roam.copy(outageMillis = outage ?: 0L, duringScan = scanInWindow)
            failureCount >= MIN_OUTAGE_PROBES -> events += RoamingEvent(
                type = RoamingEvent.OUTAGE, atMillis = failuresSince ?: atMillis,
                fromBssid = lastBssid, toBssid = lastBssid, fromRssi = lastRssi, toRssi = rssi,
                outageMillis = outage, lostProbes = failureCount, duringScan = scanInWindow
            )
        }
        pendingRoam = null
        failuresSince = null
        failureCount = 0
        scanInWindow = scanning
        lastOkAt = atMillis
    }

    /** Closes the log; a roam or outage still without connectivity gets a null duration. */
    fun finish(): RoamingLog {
        val open = buildList {
            pendingRoam?.let { add(it.copy(outageMillis = null, duringScan = scanInWindow)) }
            if (pendingRoam == null && failureCount >= MIN_OUTAGE_PROBES) {
                add(
                    RoamingEvent(
                        type = RoamingEvent.OUTAGE, atMillis = failuresSince ?: 0L,
                        fromBssid = lastBssid, toBssid = lastBssid, fromRssi = lastRssi,
                        outageMillis = null, lostProbes = failureCount, duringScan = scanInWindow
                    )
                )
            }
        }
        return RoamingLog(
            target = target,
            intervalMillis = intervalMillis,
            probes = probes,
            lost = lost,
            avgRttMs = if (rttCount > 0) (rttSum / rttCount * 10).roundToLong() / 10.0 else null,
            events = events + open
        )
    }

    companion object {
        /** Consecutive failed probes that count as an outage when the BSSID did not change. */
        const val MIN_OUTAGE_PROBES = 3
    }
}
