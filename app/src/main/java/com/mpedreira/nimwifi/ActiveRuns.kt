package com.mpedreira.nimwifi

/** Status of a run or of one of its phases. */
object RunStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"

    val UNFINISHED = setOf(PENDING, RUNNING)
}

/** One planned phase of an active run: a target direction, a network test or a script step. */
@JsonModel
data class PhaseRecord(
    /** Stable within the run: `target:<targetId>:latency|download|upload`, `test:<name>`, `script:<name>:<step>`. */
    val key: String,
    val label: String,
    val status: String = RunStatus.PENDING,
    val errorCode: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null
)

/**
 * Active tests run at a physical point ([pointId]) after a passive scan ([scanId]) or on their
 * own. Saved progressively: the point exists before the run starts, finished phases are kept
 * when a later phase fails, the run is cancelled or the process dies.
 */
@JsonModel
data class ActiveRun(
    val runId: String,
    val pointId: String,
    /** Passive scan this run followed, or null for "active tests only". */
    val scanId: String?,
    /** Wall clock (UTC ms) of the start; [endedAt] once finished. */
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String = RunStatus.RUNNING,
    /** Why it ended this way (error code), e.g. `cancelled`, `timeout`, `interrupted`. */
    val statusReason: String? = null,
    /** Name of the configuration used (device settings or imported profile). */
    val profile: String? = null,
    val phases: List<PhaseRecord> = emptyList(),
    /** Results finished so far. */
    val results: PointThroughput? = null,
    /** Position of [pointId] in original floor-plan pixels, so runs on imported or planned points export too. */
    val x: Float? = null,
    val y: Float? = null,
    /** Mejora 7: the point's finite time budget, shown before starting. */
    val budgetMillis: Long? = null,
    /** Mejora 13: network check before the run (see [NetworkVerification]). */
    val expectedSsid: String? = null,
    val observedSsid: String? = null,
    val networkVerification: String? = null,
    /** `confirmed`: the technician chose to test although the network did not match or was unknown. */
    val networkDecision: String? = null
) {
    val isFinished: Boolean get() = status !in RunStatus.UNFINISHED

    /**
     * Marks every unfinished phase and the run with [status] (cancelled, interrupted, failed).
     * Finished values stay; the phases of the results that had not finished get the same status.
     */
    fun end(status: String, reason: String?, at: Long): ActiveRun = copy(
        status = status,
        statusReason = reason,
        endedAt = endedAt ?: at,
        phases = phases.map { phase ->
            if (phase.status in RunStatus.UNFINISHED) phase.copy(status = status, errorCode = phase.errorCode ?: reason, endedAt = at)
            else phase
        },
        results = results?.endUnfinished(status, reason)
    )

    /**
     * Mejora 4: the run's own status is separate from what its phases produced. Counts of the
     * planned phases by final status.
     */
    val phaseSummary: Map<String, Int> get() = phases.groupingBy { it.status }.eachCount()
}

/** Stable identifiers and repeatable migration of points and runs saved by earlier versions. */
object SurveyIds {
    /** Same inputs always give the same id, so migrating twice is harmless. */
    fun pointId(floorId: String, point: ScanPoint): String =
        EsxIdFactory.create("point", "$floorId|${point.timestamp}|${point.x}|${point.y}")

    fun scanId(floorId: String, point: ScanPoint): String =
        EsxIdFactory.create("scan", "$floorId|${point.timestamp}|${point.x}|${point.y}")

    fun legacyRunId(pointId: String, measuredAt: Long): String =
        EsxIdFactory.create("run", "$pointId|$measuredAt")

    /**
     * Gives every point a pointId/scanId and turns the old per-point results into completed
     * runs. Existing ids and runs are kept as they are.
     */
    fun migrate(floorId: String, points: List<ScanPoint>, runs: List<ActiveRun>): Pair<List<ScanPoint>, List<ActiveRun>> {
        val known = runs.map(ActiveRun::runId).toMutableSet()
        val migratedRuns = runs.toMutableList()
        val migratedPoints = points.map { original ->
            val pointId = original.pointId ?: pointId(floorId, original)
            val scanId = original.scanId ?: scanId(floorId, original)
            val legacy = original.throughput
            if (legacy != null && !legacy.isEmpty) {
                val runId = legacyRunId(pointId, legacy.measuredAt)
                if (known.add(runId)) {
                    migratedRuns += ActiveRun(
                        runId = runId, pointId = pointId, scanId = scanId,
                        startedAt = legacy.measuredAt, endedAt = legacy.measuredAt,
                        status = RunStatus.COMPLETED, profile = "legacy", results = legacy
                    )
                }
            }
            original.copy(pointId = pointId, scanId = scanId, throughput = null)
        }
        return migratedPoints to migratedRuns
    }

    /** After a process death, runs that never finished are marked interrupted (never restarted). */
    fun reconcile(runs: List<ActiveRun>, now: Long): List<ActiveRun> = runs.map { run ->
        if (run.isFinished) run else run.end(RunStatus.INTERRUPTED, ErrorCodes.INTERRUPTED, now)
    }
}


/** Phases of results still pending/running when the run ended get the run's final status. */
fun PointThroughput.endUnfinished(status: String, reason: String?): PointThroughput {
    fun close(phaseStatus: String?, code: String?): Pair<String?, String?> =
        if (phaseStatus in RunStatus.UNFINISHED) status to (code ?: reason) else phaseStatus to code
    return copy(
        tests = tests.map { t ->
            val (ls, lc) = close(t.latencyStatus, t.latencyErrorCode)
            val (ds, dc) = close(t.downloadStatus, t.downloadErrorCode)
            val (us, uc) = close(t.uploadStatus, t.uploadErrorCode)
            t.copy(latencyStatus = ls, latencyErrorCode = lc, downloadStatus = ds, downloadErrorCode = dc, uploadStatus = us, uploadErrorCode = uc)
        },
        scripts = scripts?.map { run ->
            val total = run.stepsTotal ?: run.steps.size
            if (run.steps.size < total && run.errorCode == null) run.copy(errorCode = reason ?: status) else run
        }
    )
}
