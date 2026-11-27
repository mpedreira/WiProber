package com.mpedreira.nimwifi

import com.google.gson.Gson
import java.io.File

/**
 * One stretch of a Continuous route recorded on one monotonic clock. Times are milliseconds
 * relative to the stretch start on that clock; [startUtc] anchors it to wall-clock time. A route
 * resumed after a process death or a reboot (the monotonic clock restarts) gets a new stretch.
 */
@JsonModel
data class RouteSegment(
    val startUtc: Long,
    val timeBase: String = RoamingTracker.TIME_BASE,
    val waypoints: List<RoutePointWrapper> = emptyList(),
    val scanResults: List<ScanResultWrapper> = emptyList(),
    val probeAttempts: List<ProbeAttempt> = emptyList(),
    val scanWindows: List<ScanWindow> = emptyList(),
    /** Last observation (waypoint, scan or probe) relative to [startUtc]'s stretch start. */
    val lastObservedMs: Long = 0L
)

/**
 * Durable checkpoint of a Continuous route that has not been closed yet, saved incrementally per
 * project, floor and session. After a process death it is offered as an interrupted route: save
 * it as it is, or continue it with a new [RouteSegment].
 */
@JsonModel
data class RouteDraft(
    val sessionId: String,
    val floorId: String,
    /** UTC anchor of the route: start of the first stretch. */
    val startTime: Long,
    val segments: List<RouteSegment>,
    val roamingTarget: String? = null,
    val roamingIntervalMillis: Int? = null,
    val savedAt: Long = 0L,
    val schema: Int = SCHEMA
) {
    val scanCount: Int get() = segments.sumOf { it.scanResults.size }
    val lastWaypoint: RoutePointWrapper? get() = segments.lastOrNull { it.waypoints.isNotEmpty() }?.waypoints?.last()

    companion object {
        const val SCHEMA = 1
    }
}

/** A gap between two stretches of a resumed route: nothing was observed in it. */
@JsonModel
data class RouteInterruption(val fromMs: Long, val toMs: Long)

object RouteDrafts {
    /**
     * Joins the stretches into one session on the route's relative time base. Each stretch keeps
     * its own clock: it is placed by its UTC anchor (never before the previous stretch ended,
     * so a monotonic reset or a wall-clock jump cannot reorder data) and the gap between two
     * stretches is recorded as an interruption. [end], if given, closes the route there.
     */
    fun finalize(draft: RouteDraft, end: RoutePointWrapper? = null, recovered: Boolean): ContinuousScanSession {
        val waypoints = mutableListOf<RoutePointWrapper>()
        val scans = mutableListOf<ScanResultWrapper>()
        val attempts = mutableListOf<ProbeAttempt>()
        val interruptions = mutableListOf<RouteInterruption>()
        val windows = mutableListOf<ScanWindow>()
        var previousEnd = -1L
        draft.segments.forEach { segment ->
            val anchored = segment.startUtc - draft.startTime
            val offset = if (previousEnd < 0) 0L else maxOf(anchored, previousEnd + 1)
            if (previousEnd >= 0) interruptions += RouteInterruption(previousEnd, offset)
            segment.waypoints.forEach { waypoints += it.copy(timestamp = it.timestamp + offset) }
            segment.scanResults.forEach { scans += it.copy(timestamp = it.timestamp + offset) }
            segment.probeAttempts.forEach { attempts += it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
            segment.scanWindows.orEmpty().forEach { windows += it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
            val last = maxOf(segment.lastObservedMs, segment.waypoints.maxOfOrNull { it.timestamp } ?: 0L,
                segment.scanResults.maxOfOrNull { it.timestamp } ?: 0L, segment.probeAttempts.maxOfOrNull { it.endMs } ?: 0L)
            previousEnd = offset + last
        }
        end?.let { waypoints += it.copy(timestamp = maxOf(it.timestamp, previousEnd)) }
        val elapsed = maxOf(previousEnd, waypoints.maxOfOrNull { it.timestamp } ?: 0L)
        val roaming = draft.roamingTarget?.let { target ->
            RoamingTracker(target, draft.roamingIntervalMillis ?: RoamingSettings.DEFAULT_INTERVAL_MILLIS).apply {
                attempts.sortedBy(ProbeAttempt::startMs).forEach(::add)
            }.finish(scans.map { (it.timestamp - it.duration)..it.timestamp })
        }
        val allWindows = windows.ifEmpty { scans.map { ScanWindow(it.timestamp - it.duration, it.timestamp, ScanWindow.SUCCESS) } }
        return ContinuousScanSession(
            id = draft.sessionId,
            startTime = draft.startTime,
            endTime = draft.startTime + elapsed,
            waypoints = waypoints,
            scanResults = scans,
            roaming = roaming,
            timeBase = RoamingTracker.TIME_BASE,
            segments = draft.segments.size,
            interruptions = interruptions,
            recoveredAfterInterruption = recovered,
            scanWindows = allWindows,
            rfGaps = RfGaps.compute(allWindows, elapsed, interruptions)
        )
    }
}

/**
 * Route drafts of one floor: `drafts/<sessionId>.json` next to the floor's survey file. Each
 * write replaces the file atomically, so a failed write keeps the previous checkpoint.
 */
class RouteDraftStore(private val directory: File, private val gson: Gson = Gson()) {
    private fun file(sessionId: String): File {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid route session id" }
        return File(directory, "$sessionId.json")
    }

    fun save(draft: RouteDraft) {
        directory.mkdirs()
        val target = file(draft.sessionId)
        val temp = File(directory, "${draft.sessionId}.json.tmp")
        temp.writeText(gson.toJson(draft))
        if (!temp.renameTo(target)) {
            // Some file systems refuse to rename over an existing file: keep the old checkpoint
            // until the new one is in place.
            val backup = File(directory, "${draft.sessionId}.json.bak")
            if (target.exists() && !target.renameTo(backup)) { temp.delete(); error("Cannot replace the route draft") }
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                temp.delete()
                error("Cannot save the route draft")
            }
            backup.delete()
        }
    }

    /** Readable drafts; an unreadable one is skipped (and kept on disk). */
    fun list(): List<RouteDraft> = directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        .sortedBy { it.name }
        .mapNotNull { f -> runCatching { gson.fromJson(f.readText(), RouteDraft::class.java) }.getOrNull()?.takeIf { it.segments != null } }

    fun delete(sessionId: String) {
        file(sessionId).delete()
    }

    /** Drafts whose route is already in the saved survey are done: removes them. */
    fun deleteFinalized(sessionIds: Set<String>) {
        list().filter { it.sessionId in sessionIds }.forEach { delete(it.sessionId) }
    }
}


/**
 * Spans of a capture without a successful RF scan: the start before the first one, pauses
 * (screen off without the service, throttling, failures) and the end after the last one. A span
 * counts when it is longer than [factor] times the median interval between successful scans, and
 * at least [minMillis] (the median only counts with 4 or more intervals). Interruptions between
 * stretches are always gaps.
 */
object RfGaps {
    fun compute(
        windows: List<ScanWindow>,
        sessionEndMs: Long,
        interruptions: List<RouteInterruption> = emptyList(),
        factor: Double = 3.0,
        minMillis: Long = 5_000L
    ): List<RouteInterruption> {
        val ends = windows.filter { it.outcome == ScanWindow.SUCCESS }.map { it.endMs }.sorted()
        val intervals = ends.zipWithNext { a, b -> b - a }.sorted()
        // With few scans the median would be the gap itself: then only the fixed minimum applies.
        val median = if (intervals.size >= 4) intervals[intervals.size / 2] else 0L
        val threshold = maxOf(minMillis, (median * factor).toLong())
        val marks = listOf(0L) + ends + sessionEndMs
        val gaps = marks.zipWithNext().filter { (a, b) -> b - a > threshold }.map { (a, b) -> RouteInterruption(a, b) }
        return (gaps + interruptions).sortedBy { it.fromMs }.fold(mutableListOf<RouteInterruption>()) { merged, gap ->
            val last = merged.lastOrNull()
            if (last != null && gap.fromMs <= last.toMs) merged[merged.lastIndex] = RouteInterruption(last.fromMs, maxOf(last.toMs, gap.toMs))
            else merged += gap
            merged
        }
    }
}
