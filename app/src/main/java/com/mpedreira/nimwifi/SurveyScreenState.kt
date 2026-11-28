package com.mpedreira.nimwifi

data class SurveyCoordinate(val x: Float, val y: Float)

enum class SurveyScanMode { STOP_AND_GO, CONTINUOUS }

/**
 * RC4 · 3: what a Stop-and-Go measurement records. [COVERAGE]: the passive scan only.
 * [COVERAGE_AND_PERFORMANCE]: the same scan, then the active tests of the current profile.
 */
enum class PointMeasure { COVERAGE, COVERAGE_AND_PERFORMANCE }

/** Retained across recreation; a system round trip must finish before another one starts. */
enum class ScanAccessStep {
    CHECKING, PERMISSION_REQUEST, PERMISSION_EXPLANATION, APP_SETTINGS,
    WIFI_SETTINGS, LOCATION_EXPLANATION, LOCATION_SETTINGS
}

enum class ProjectOperation {
    INITIAL_LOAD,
    FLOOR_SWITCH,
    FLOOR_MAP_SELECTION,
    FLOOR_ADD,
    EXPORT_PREFLIGHT,
    EXPORT_DESTINATION,
    EXPORT
}

sealed interface PendingScanRequest {
    val coordinate: SurveyCoordinate

    /** [pointId]: measure again at an existing (local, imported or planned) point; null = new point. */
    data class StopAndGo(
        override val coordinate: SurveyCoordinate,
        val pointId: String? = null,
        val measure: PointMeasure = PointMeasure.COVERAGE
    ) : PendingScanRequest
    /** [resumeSessionId]: continue an interrupted route with a new stretch. */
    data class ContinuousStart(override val coordinate: SurveyCoordinate, val resumeSessionId: String? = null) : PendingScanRequest
}

sealed interface SurveyInteraction {
    data object Idle : SurveyInteraction
    data object NotePlacement : SurveyInteraction
    data class Calibration(val points: List<SurveyCoordinate>) : SurveyInteraction
    data object AwaitingContinuousStart : SurveyInteraction
    data class AwaitingScanAccess(
        val request: PendingScanRequest,
        val step: ScanAccessStep = ScanAccessStep.CHECKING
    ) : SurveyInteraction
    data class StopAndGoScanning(
        val coordinate: SurveyCoordinate,
        val pointId: String? = null,
        val measure: PointMeasure = PointMeasure.COVERAGE
    ) : SurveyInteraction
    data class ContinuousTracking(
        val startedAt: Long,
        val waypoints: List<SurveyCoordinate>,
        val scanCount: Int
    ) : SurveyInteraction
    data class ProjectBusy(val operation: ProjectOperation) : SurveyInteraction
    /** Active tests of [pointId] are running ([runId]); survives Activity recreation. */
    data class ActiveTesting(val runId: String, val pointId: String) : SurveyInteraction
}

sealed interface StartedScanAction {
    data class StopAndGo(val coordinate: SurveyCoordinate) : StartedScanAction
    data object Continuous : StartedScanAction
}

data class SurveyScreenState(
    val mapInfo: MapInfo? = null,
    val metersPerUnit: Double? = null,
    val scanPoints: List<ScanPoint> = emptyList(),
    val continuousScanSessions: List<ContinuousScanSession> = emptyList(),
    val notes: List<AppNote> = emptyList(),
    val actionHistory: List<LastAction> = emptyList(),
    /** Survey-guide cells the user skipped (persisted with the floor). */
    val skippedSuggestions: List<SurveyCoordinate> = emptyList(),
    /** Next point the user moved by hand; replaces the computed suggestion until used or skipped. */
    val movedSuggestion: SurveyCoordinate? = null,
    /** Active test runs of this floor, saved progressively. */
    val activeRuns: List<ActiveRun> = emptyList(),
    /** Template points not measured yet. */
    val plannedPoints: List<PlannedPoint> = emptyList(),
    /** Routes left open by a process death or a reboot, recovered from their drafts. */
    val interruptedRoutes: List<RouteDraft> = emptyList(),
    /** Phase currently running in the active run, for the progress dialog. */
    val activeRunProgress: ThroughputProgress? = null,
    val scanMode: SurveyScanMode = SurveyScanMode.STOP_AND_GO,
    /** Action a tap on the plan measures with (RC4 · 3); kept across recreation. */
    val measureAction: PointMeasure = PointMeasure.COVERAGE,
    val interaction: SurveyInteraction = SurveyInteraction.Idle,
    val lastUndoneAction: LastAction? = null
) {
    val hasMap: Boolean get() = mapInfo != null

    /** Exact position of a physical point: any scan with that pointId (local or imported), else a planned point. */
    fun pointPosition(pointId: String): SurveyCoordinate? =
        scanPoints.firstOrNull { it.pointId == pointId }?.let { SurveyCoordinate(it.x, it.y) }
            ?: plannedPoints.firstOrNull { it.pointId == pointId }?.let { SurveyCoordinate(it.x, it.y) }
    val isTracking: Boolean get() = interaction is SurveyInteraction.ContinuousTracking
    val isBusy: Boolean get() = interaction !is SurveyInteraction.Idle
    val activeTrackPoints: List<SurveyCoordinate>
        get() = (interaction as? SurveyInteraction.ContinuousTracking)?.waypoints.orEmpty()
    val activeContinuousScanCount: Int
        get() = (interaction as? SurveyInteraction.ContinuousTracking)?.scanCount ?: 0
}
