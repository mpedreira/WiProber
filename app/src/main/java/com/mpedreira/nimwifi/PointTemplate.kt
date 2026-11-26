package com.mpedreira.nimwifi

import com.google.gson.JsonParser
import kotlin.math.abs

/**
 * A list of points to measure, imported onto a floor plan: `wiprober-points` JSON or CSV. Used to
 * bring points planned elsewhere (e.g. a point list exported from another survey tool) onto the
 * current plan. Coordinates are pixels of the plan the template was made for; when its size
 * differs from the current plan they are scaled, provided the aspect ratio matches.
 *
 * JSON: `{"format": "wiprober-points", "version": 1, "image": {"width": 2000, "height": 1000},
 * "points": [{"id": "A1", "label": "Dock 1", "x": 120.5, "y": 80}]}`.
 * CSV: `label,x,y` (an `id` column is optional), with an optional first line `# image 2000x1000`.
 */
object PointTemplateParser {
    const val FORMAT = "wiprober-points"
    const val MAX_POINTS = 2_000
    private const val ASPECT_TOLERANCE = 0.01

    data class Preview(
        val points: List<PlannedPoint>,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val scale: Double,
        /** Points outside the plan, not imported. */
        val outside: Int,
        /** Points already on the floor (same id): imported again they are ignored. */
        val duplicates: Int
    )

    private data class Raw(val id: String?, val label: String, val x: Double, val y: Double)

    /**
     * [floorKey] and [batch] make ids deterministic: importing the same template again on the
     * same floor yields the same pointIds, so it never duplicates points.
     */
    fun parse(text: String, mapWidth: Int, mapHeight: Int, floorKey: String, batch: String, existing: Set<String> = emptySet()): Preview {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val (size, raws) = if (trimmed.startsWith("{")) parseJson(trimmed) else parseCsv(trimmed)
        val sourceWidth = size?.first ?: mapWidth
        val sourceHeight = size?.second ?: mapHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) throw ConfigException("Invalid template image size")
        val aspect = sourceWidth.toDouble() / sourceHeight
        val mapAspect = mapWidth.toDouble() / mapHeight
        if (abs(aspect - mapAspect) / mapAspect > ASPECT_TOLERANCE) {
            throw ConfigException("The template was made for a ${sourceWidth}×$sourceHeight plan; this plan is ${mapWidth}×$mapHeight (different proportions)")
        }
        if (raws.isEmpty()) throw ConfigException("The template has no points")
        if (raws.size > MAX_POINTS) throw ConfigException("At most $MAX_POINTS points")
        val scale = mapWidth.toDouble() / sourceWidth
        var outside = 0
        val points = raws.mapIndexedNotNull { index, raw ->
            val x = raw.x * scale
            val y = raw.y * scale
            if (x < 0 || y < 0 || x > mapWidth || y > mapHeight || x.isNaN() || y.isNaN()) {
                outside++
                return@mapIndexedNotNull null
            }
            PlannedPoint(
                pointId = EsxIdFactory.create("planned", "$floorKey|${raw.id ?: "${raw.label}|${raw.x}|${raw.y}"}"),
                label = raw.label.ifBlank { raw.id ?: "T${index + 1}" }.take(40),
                x = x.toFloat(),
                y = y.toFloat(),
                batch = batch
            )
        }.distinctBy(PlannedPoint::pointId)
        val duplicates = points.count { it.pointId in existing }
        return Preview(points.filter { it.pointId !in existing }, sourceWidth, sourceHeight, scale, outside, duplicates)
    }

    private fun parseJson(text: String): Pair<Pair<Int, Int>?, List<Raw>> {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse { throw ConfigException("Not a JSON file") }
        if (root.get("format")?.asString != FORMAT) throw ConfigException("\"format\" must be \"$FORMAT\"")
        if (root.get("version")?.asInt != 1) throw ConfigException("Unsupported version (expected 1)")
        val image = root.getAsJsonObject("image") ?: throw ConfigException("\"image\" (width, height of the plan the points refer to) is required")
        val size = image.get("width").asInt to image.get("height").asInt
        val points = root.getAsJsonArray("points")?.map { element ->
            val p = element.asJsonObject
            Raw(
                id = p.get("id")?.takeUnless { it.isJsonNull }?.asString,
                label = p.get("label")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                x = p.get("x")?.asDouble ?: throw ConfigException("A point has no \"x\""),
                y = p.get("y")?.asDouble ?: throw ConfigException("A point has no \"y\"")
            )
        }.orEmpty()
        return size to points
    }

    private fun parseCsv(text: String): Pair<Pair<Int, Int>?, List<Raw>> {
        var size: Pair<Int, Int>? = null
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty).toMutableList()
        if (lines.firstOrNull()?.startsWith("#") == true) {
            val match = Regex("""image\s+(\d+)\s*[x×]\s*(\d+)""", RegexOption.IGNORE_CASE).find(lines.removeAt(0))
            size = match?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        }
        if (lines.isEmpty()) return size to emptyList()
        val separator = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        val header = lines.first().split(separator).map { it.trim().lowercase() }
        val hasHeader = "x" in header && "y" in header
        val columns = if (hasHeader) header else listOf("label", "x", "y")
        val xi = columns.indexOf("x")
        val yi = columns.indexOf("y")
        val li = columns.indexOfFirst { it == "label" || it == "name" }
        val ii = columns.indexOf("id")
        if (xi < 0 || yi < 0) throw ConfigException("The CSV needs x and y columns")
        return size to lines.drop(if (hasHeader) 1 else 0).mapIndexed { n, line ->
            val cells = line.split(separator).map { it.trim().trim('"') }
            fun number(i: Int) = cells.getOrNull(i)?.toDoubleOrNull() ?: throw ConfigException("Line ${n + 1}: invalid x/y")
            Raw(cells.getOrNull(ii)?.ifEmpty { null }, cells.getOrNull(li).orEmpty(), number(xi), number(yi))
        }
    }
}
