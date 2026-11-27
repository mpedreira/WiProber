package com.mpedreira.nimwifi

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class PointsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Свойства для точек Stop-and-Go ---
    private val pointsToDraw = mutableListOf<Pair<Float, Float>>()
    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val strokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // --- Свойства для Continuous (Треки) ---
    private val activeTrackPoints = mutableListOf<PointF>() // То, что рисуем прямо сейчас
    private val completedTracks = mutableListOf<List<PointF>>() // Старые треки

    private val trackPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val activeTrackPaint = Paint().apply {
        color = Color.MAGENTA // Активный трек рисуем другим цветом
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val waypointPaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.FILL
        isAntiAlias = true
    }


    // --- Свойства для линии калибровки ---
    private val calibrationLinePoints = mutableListOf<PointF>()
    private val calibrationPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // --- Общие свойства ---
    private var imageMatrix: Matrix? = null
    private var coordinateMapper: ImageCoordinateMapper? = null
    private val radius = 15f
    private val noteMarkers = mutableListOf<PointF>()
    private val notePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // --- Survey guide: coverage gradient (pre-rendered in floor-plan space) and next point ---
    private var coverageBitmap: Bitmap? = null
    private var coverageKey: Any? = null
    private var suggestion: PointF? = null
    private var suggestionMoved = false
    private val density = resources.displayMetrics.density
    private val coveragePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val coverageMatrix = Matrix()
    private val suggestionPaint = Paint().apply {
        color = SUGGESTION_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
        isAntiAlias = true
    }
    private val suggestionCrossPaint = Paint().apply {
        color = SUGGESTION_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }
    private val suggestionHaloPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        isAntiAlias = true
    }

    private var guideGrid: GuideGrid? = null
    private val gridPaint = Paint().apply {
        color = GRID_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        isAntiAlias = true
    }

    /** Survey-guide grid drawn over the plan; null hides it. */
    fun setGuideGrid(grid: GuideGrid?) {
        if (grid == guideGrid) return
        guideGrid = grid
        invalidate()
    }

    /** Screen position (this view's coordinates) of a floor-plan point, or null before layout. */
    fun screenPosition(x: Float, y: Float): PointF? {
        val matrix = imageMatrix ?: return null
        val t = transformPoint(x, y, matrix)
        return PointF(t[0], t[1])
    }

    /**
     * Coverage of each measurement: a radial gradient of [radiusPx] floor-plan pixels coloured by
     * its strongest signal. Rendered once into a down-scaled bitmap so panning stays cheap.
     */
    fun setCoverage(samples: List<CoverageSample>, radiusPx: Float?, sourceWidth: Int, sourceHeight: Int) {
        val key = listOf(samples, radiusPx, sourceWidth, sourceHeight)
        if (key == coverageKey) return
        coverageKey = key
        coverageBitmap = null
        if (radiusPx != null && radiusPx > 0f && samples.isNotEmpty() && sourceWidth > 0 && sourceHeight > 0) {
            val scale = minOf(1f, MAX_COVERAGE_SIDE.toFloat() / maxOf(sourceWidth, sourceHeight))
            val bitmap = createBitmap(
                (sourceWidth * scale).toInt().coerceAtLeast(1),
                (sourceHeight * scale).toInt().coerceAtLeast(1)
            )
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = (radiusPx * scale).coerceAtLeast(1f)
            // Weakest first, so the strongest reading at a spot is the one on top.
            samples.sortedBy { it.bestRssi ?: Int.MIN_VALUE }.forEach { sample ->
                val color = SurveyGuide.rssiColor(sample.bestRssi)
                paint.shader = RadialGradient(
                    sample.x * scale, sample.y * scale, radius,
                    intArrayOf(withAlpha(color, 170), withAlpha(color, 120), withAlpha(color, 0)),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(sample.x * scale, sample.y * scale, radius, paint)
            }
            coverageBitmap = bitmap
        }
        invalidate()
    }

    /** Next suggested point (floor-plan pixels); [moved] when the user placed it by hand. */
    fun setSuggestion(point: PointF?, moved: Boolean) {
        if (point == suggestion && moved == suggestionMoved) return
        suggestion = point
        suggestionMoved = moved
        suggestionPaint.pathEffect = if (moved) null else DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
        invalidate()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    internal fun updateTransform(matrix: Matrix, coordinateMapper: ImageCoordinateMapper) {
        this.imageMatrix = Matrix(matrix)
        this.coordinateMapper = coordinateMapper
        invalidate()
    }

    // ================== DATA SETTERS ==================

    /** Point marker coloured by its [PointState] for the selected SSID; null state = no SSID selected. */
    /** [redundant]: a second AP of the same SSID above the secondary threshold (drawn as an outer ring). */
    data class PointMarker(val x: Float, val y: Float, val state: String?, val redundant: Boolean = false)

    private val pointMarkers = mutableListOf<PointMarker>()
    private val okPaint = Paint().apply { color = Color.rgb(27, 110, 32); style = Paint.Style.FILL; isAntiAlias = true }
    private val okRingPaint = Paint().apply { color = Color.rgb(27, 110, 32); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val weakPaint = Paint().apply { color = Color.rgb(230, 126, 0); style = Paint.Style.FILL; isAntiAlias = true }
    private val unmeasuredPaint = Paint().apply {
        color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }

    fun setPointMarkers(markers: List<PointMarker>) {
        pointMarkers.clear()
        pointMarkers.addAll(markers)
        invalidate()
    }

    fun setScanPoints(points: List<Pair<Float, Float>>) {
        pointsToDraw.clear()
        pointsToDraw.addAll(points)
        invalidate()
    }

    fun setNoteMarkers(points: List<PointF>) {
        noteMarkers.clear()
        noteMarkers.addAll(points)
        invalidate()
    }

    // НОВОЕ: Установка активного трека
    fun setActiveTrack(points: List<PointF>) {
        activeTrackPoints.clear()
        activeTrackPoints.addAll(points)
        invalidate()
    }

    // НОВОЕ: Установка завершенных треков
    fun setCompletedTracks(sessions: List<ContinuousScanSession>) {
        // Same sessions (a progress-only update): nothing to rebuild or repaint.
        if (sessions === completedSource) return
        completedSource = sessions
        completedTracks.clear()
        sessions.forEach { session ->
            val track = session.waypoints.map { wp -> PointF(wp.x, wp.y) }
            completedTracks.add(track)
        }
        invalidate()
    }

    fun setCalibrationLine(points: List<PointF>) {
        calibrationLinePoints.clear()
        calibrationLinePoints.addAll(points)
        invalidate()
    }

    fun clearCalibrationLine() {
        calibrationLinePoints.clear()
        invalidate()
    }

    fun clearAll() {
        coverageBitmap = null
        guideGrid = null
        coverageKey = null
        suggestion = null
        pointsToDraw.clear()
        pointMarkers.clear()
        noteMarkers.clear()
        calibrationLinePoints.clear()
        activeTrackPoints.clear()
        completedTracks.clear()
        completedSource = null
        invalidate()
    }

    private var completedSource: List<ContinuousScanSession>? = null

    // ================== DRAWING ==================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentMatrix = imageMatrix ?: return

        // 0. Coverage gradient under everything else
        coverageBitmap?.let { bitmap ->
            val mapper = coordinateMapper
            val drawableWidth = mapper?.drawableWidth?.toFloat() ?: bitmap.width.toFloat()
            val drawableHeight = mapper?.drawableHeight?.toFloat() ?: bitmap.height.toFloat()
            coverageMatrix.setScale(drawableWidth / bitmap.width, drawableHeight / bitmap.height)
            coverageMatrix.postConcat(currentMatrix)
            canvas.drawBitmap(bitmap, coverageMatrix, coveragePaint)
        }

        // 0b. Survey-guide grid over the coverage, under points and routes
        guideGrid?.let { drawGuideGrid(canvas, it, currentMatrix) }

        // 1. Рисуем ЗАВЕРШЕННЫЕ треки (Зеленые)
        for (track in completedTracks) {
            drawPath(canvas, track, trackPaint, currentMatrix)
        }

        // 2. Рисуем АКТИВНЫЙ трек (Маджента)
        if (activeTrackPoints.isNotEmpty()) {
            drawPath(canvas, activeTrackPoints, activeTrackPaint, currentMatrix)
            drawPointsFromList(canvas, activeTrackPoints, waypointPaint, currentMatrix, 12f)
        }

        // 3. Рисуем точки Stop-and-Go (Красные)
        for (point in pointsToDraw) {
            val t = transformPoint(point.first, point.second, currentMatrix)
            canvas.drawCircle(t[0], t[1], radius, strokePaint)
            canvas.drawCircle(t[0], t[1], radius, pointPaint)
        }
        // 3b. Points by state for the selected SSID: filled green (ok), orange (weak), red with a
        // cross (SSID not detected), hollow ring (no local measurement: planned or imported).
        for (marker in pointMarkers) {
            val t = transformPoint(marker.x, marker.y, currentMatrix)
            if (marker.redundant) canvas.drawCircle(t[0], t[1], radius + 7f, okRingPaint)
            when (marker.state) {
                PointState.NOT_MEASURED -> {
                    canvas.drawCircle(t[0], t[1], radius, strokePaint)
                    canvas.drawCircle(t[0], t[1], radius, unmeasuredPaint)
                }
                PointState.SSID_NOT_DETECTED -> {
                    canvas.drawCircle(t[0], t[1], radius, strokePaint)
                    canvas.drawCircle(t[0], t[1], radius, pointPaint)
                    canvas.drawLine(t[0] - radius / 2, t[1] - radius / 2, t[0] + radius / 2, t[1] + radius / 2, strokePaint)
                    canvas.drawLine(t[0] - radius / 2, t[1] + radius / 2, t[0] + radius / 2, t[1] - radius / 2, strokePaint)
                }
                else -> {
                    canvas.drawCircle(t[0], t[1], radius, strokePaint)
                    canvas.drawCircle(
                        t[0], t[1], radius,
                        when (marker.state) {
                            PointState.OK -> okPaint
                            PointState.WEAK -> weakPaint
                            else -> pointPaint
                        }
                    )
                }
            }
        }

        // 4. Рисуем маркеры заметок (Синие)
        for (marker in noteMarkers) {
            val t = transformPoint(marker.x, marker.y, currentMatrix)
            canvas.drawCircle(t[0], t[1], radius, strokePaint)
            canvas.drawCircle(t[0], t[1], radius, notePaint)
        }

        // 5. Линия калибровки
        if (calibrationLinePoints.isNotEmpty()) {
            val transformedPoints = calibrationLinePoints.map {
                val t = transformPoint(it.x, it.y, currentMatrix)
                PointF(t[0], t[1])
            }

            transformedPoints.forEach { p ->
                canvas.drawCircle(p.x, p.y, radius + 5f, calibrationPaint)
            }

            if (transformedPoints.size == 2) {
                canvas.drawLine(transformedPoints[0].x, transformedPoints[0].y, transformedPoints[1].x, transformedPoints[1].y, calibrationPaint)
            }
        }

        // 6. Next suggested point: ring with a cross (dashed when computed, solid when moved by hand)
        suggestion?.let { point ->
            val t = transformPoint(point.x, point.y, currentMatrix)
            val ring = SUGGESTION_RADIUS_DP * density
            val arm = ring * 0.55f
            canvas.drawCircle(t[0], t[1], ring, suggestionHaloPaint)
            canvas.drawCircle(t[0], t[1], ring, suggestionPaint)
            canvas.drawLine(t[0] - arm, t[1], t[0] + arm, t[1], suggestionCrossPaint)
            canvas.drawLine(t[0], t[1] - arm, t[0], t[1] + arm, suggestionCrossPaint)
        }
    }

    /**
     * Grid lines in floor-plan pixels through the PhotoView matrix. When zoomed out so far that
     * cells would be closer than [MIN_GRID_SPACING_DP], only every n-th line is drawn.
     */
    private fun drawGuideGrid(canvas: Canvas, grid: GuideGrid, matrix: Matrix) {
        val origin = transformPoint(0f, 0f, matrix)
        val step = transformPoint(grid.cellPx, 0f, matrix, reusableSecond)
        val onScreen = kotlin.math.hypot(step[0] - origin[0], step[1] - origin[1])
        if (!(onScreen > 0f)) return
        val stride = kotlin.math.ceil(MIN_GRID_SPACING_DP * density / onScreen).toInt().coerceAtLeast(1)
        val width = grid.mapWidth.toFloat()
        val height = grid.mapHeight.toFloat()
        var x = 0f
        var column = 0
        while (x <= width) {
            if (column % stride == 0) drawSourceLine(canvas, x, 0f, x, height, matrix)
            column++
            x = column * grid.cellPx
        }
        var y = 0f
        var row = 0
        while (y <= height) {
            if (row % stride == 0) drawSourceLine(canvas, 0f, y, width, y, matrix)
            row++
            y = row * grid.cellPx
        }
    }

    private fun drawSourceLine(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, matrix: Matrix) {
        val a = transformPoint(x0, y0, matrix)
        val b = transformPoint(x1, y1, matrix, reusableSecond)
        canvas.drawLine(a[0], a[1], b[0], b[1], gridPaint)
    }

    // Helper: Рисует линию по списку точек
    private fun drawPath(canvas: Canvas, points: List<PointF>, paint: Paint, matrix: Matrix) {
        if (points.size < 2) return

        // Reused across frames (mejora 18): no Path or array allocated per point while drawing.
        val path = reusablePath.apply { rewind() }
        val start = transformPoint(points[0].x, points[0].y, matrix)
        path.moveTo(start[0], start[1])

        for (i in 1 until points.size) {
            val p = transformPoint(points[i].x, points[i].y, matrix)
            path.lineTo(p[0], p[1])
        }
        canvas.drawPath(path, paint)
    }

    // Helper: Рисует просто точки (узлы)
    private fun drawPointsFromList(canvas: Canvas, points: List<PointF>, paint: Paint, matrix: Matrix, r: Float) {
        points.forEach { p ->
            val t = transformPoint(p.x, p.y, matrix)
            canvas.drawCircle(t[0], t[1], r, paint)
        }
    }

    private val reusablePath = Path()
    private val reusablePoint = FloatArray(2)
    private val reusableSecond = FloatArray(2)

    /** Writes into [out] (reused): read it before the next call with the same array. */
    private fun transformPoint(x: Float, y: Float, matrix: Matrix, out: FloatArray = reusablePoint): FloatArray {
        val mapper = coordinateMapper
        if (mapper != null) mapper.sourceToDrawableInto(x, y, out) else { out[0] = x; out[1] = y }
        matrix.mapPoints(out)
        return out
    }

    private companion object {
        const val MAX_COVERAGE_SIDE = 1_024
        const val SUGGESTION_RADIUS_DP = 18f
        val SUGGESTION_COLOR = Color.rgb(21, 101, 192)
        const val MIN_GRID_SPACING_DP = 12f
        val GRID_COLOR = Color.argb(120, 40, 40, 40)
    }
}
