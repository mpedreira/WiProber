package com.mpedreira.nimwifi

enum class LastAction { SCAN, NOTE, SCAN_SESSION, SUGGESTION_SKIP, ACTIVE_RUN, PLANNED_POINTS }

/** A survey-guide grid cell the user marked "do not measure" (any point inside it, floor pixels). */
@JsonModel
data class SkippedSuggestion(val x: Float, val y: Float)

// Этот класс будет представлять одну точку сканирования на карте
@JsonModel
data class ScanPoint(
    val timestamp: Long, // Время сканирования, чтобы отличать одно от другого
    val x: Float,        // Координата X на карте
    val y: Float,        // Координата Y на карте
    val wifiNetworks: List<WifiNetworkInfo>, // Список сетей, найденных в этой точке
    val importedFromEsx: Boolean = false,
    /** Legacy (fork releases 1–4): results of the tests at this point; migrated to [ActiveRun] on load. */
    val throughput: PointThroughput? = null,
    /** Physical point: the same for every scan and active run at these exact coordinates. */
    val pointId: String? = null,
    /** This passive scan. */
    val scanId: String? = null
)

/**
 * A point planned from an imported template (or kept for measuring later): position in
 * original floor-plan pixels, no measurement yet. Measuring it reuses [pointId].
 */
@JsonModel
data class PlannedPoint(
    val pointId: String,
    val label: String,
    val x: Float,
    val y: Float,
    /** Import batch, so Undo removes one template import at a time. */
    val batch: String? = null
)

// Этот класс описывает информацию об одной Wi-Fi сети
@JsonModel
data class WifiNetworkInfo(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val security: String,
    val technologies: List<String>,
    val informationElements: String,
    /**
     * What the beacon says about the AP (vendor elements, WPS, advertised name), extracted when
     * scanning; null in scans saved before 4.0.0-rc.5 and when the beacon says nothing. App and
     * export only: the ESX keeps [informationElements] as before.
     */
    val beacon: BeaconIdentity? = null
)

data class MapInfo(val fileName: String, val width: Int, val height: Int)

data class AppNote(
    val id: String,
    val text: String,
    /** String form of the source URI. Android URI parsing stays at storage/UI boundaries. */
    val photoUri: String?,
    val photoWidth: Int?,
    val photoHeight: Int?,
    val photoId: String?,
    val x: Float, // Координата на оригинальной карте
    val y: Float,
    val pictureNoteId: String,
    val photoFormat: String? = null,
    val importedFromEsx: Boolean = false
)

// Новый класс для непрерывного сканирования
@JsonModel
data class ContinuousScanSession(
    val id: String,
    val startTime: Long,
    val endTime: Long = 0,
    val waypoints: List<RoutePointWrapper>, // Точки нажатий (повороты)
    val scanResults: List<ScanResultWrapper>, // Все результаты сканирований в процессе ходьбы
    val importedFromEsx: Boolean = false,
    /** Roaming probe of this track, if it was enabled; app/export only, never in the ESX. */
    val roaming: RoamingLog? = null,
    /**
     * rc.2 · 4: a resumed route whose stretches used different probes (method, target, alias or
     * interval) keeps one log per probe here, each with its span; [roaming] is then null.
     */
    val roamingStretches: List<RoamingLog>? = null,
    /**
     * `monotonic-relative`: waypoint, scan and probe times are monotonic milliseconds since the
     * session start and [startTime] is the UTC anchor. Null for tracks recorded before (wall clock).
     */
    val timeBase: String? = null,
    /** Stretches the route was recorded in (1 unless it was resumed after an interruption). */
    val segments: Int? = null,
    /** Gaps between stretches, relative to [startTime]: nothing was observed in them. */
    val interruptions: List<RouteInterruption>? = null,
    /** The route was recovered from a draft after the app was closed or killed. */
    val recoveredAfterInterruption: Boolean? = null,
    /** Every RF scan attempt (successful, failed, cancelled), relative to [startTime]. */
    val scanWindows: List<ScanWindow>? = null,
    /** Spans without a successful RF scan (pauses, throttling, failures), relative to [startTime]. */
    val rfGaps: List<RouteInterruption>? = null
) {
    /** Every roaming log of the route: one, or one per probe of a resumed route (rc.2 · 4). */
    val roamingLogs: List<RoamingLog> get() = roamingStretches ?: listOfNotNull(roaming)
}

/** One RF scan attempt of a Continuous capture (times relative to the session start). */
@JsonModel
data class ScanWindow(val startMs: Long, val endMs: Long, val outcome: String, val reason: String? = null) {
    companion object {
        const val SUCCESS = "success"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}

@JsonModel
data class RoutePointWrapper(
    val timestamp: Long, // Время нажатия относительно начала (startTime)
    val x: Float,
    val y: Float
)

@JsonModel
data class ScanResultWrapper(
    val timestamp: Long, // Время получения результатов относительно начала
    val duration: Long,  // Сколько длился этот конкретный скан (разница между startScan и onResults)
    val wifiNetworks: List<WifiNetworkInfo>
)
