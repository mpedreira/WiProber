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

    /** How the template's plan was matched to this one (mejora 20). */
    object PlanMatch {
        /** The template names this plan (floor-plan ID or image SHA-256). */
        const val MATCHED = "matched"
        /** No identity in the template: only size/proportion were checked; the user confirms on the preview. */
        const val UNVERIFIED = "unverified"
    }

    /** Identity of the current plan: the ESX floor-plan ID and the SHA-256 of its image, when known. */
    data class PlanIdentity(val floorPlanId: String? = null, val imageSha256: String? = null)

    data class Preview(
        val points: List<PlannedPoint>,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val scale: Double,
        /** Points outside the plan, not imported. */
        val outside: Int,
        /** Points already on the floor (same id): imported again they are ignored. */
        val duplicates: Int,
        /** Ids repeated inside the template: only the first point with each is imported. */
        val repeatedIds: List<String> = emptyList(),
        /** Labels (or ids) of the points outside the plan, to show them. */
        val outsideLabels: List<String> = emptyList(),
        val planMatch: String = PlanMatch.UNVERIFIED
    ) {
        /** Without identity, adding the points needs an explicit "this is the right plan". */
        val needsConfirmation: Boolean get() = planMatch != PlanMatch.MATCHED
    }

    private data class Raw(val id: String?, val label: String, val x: Double, val y: Double)

    private data class Source(val width: Int?, val height: Int?, val floorPlanId: String?, val sha256: String?)

    /**
     * [floorKey] and [batch] make ids deterministic: importing the same template again on the
     * same floor yields the same pointIds, so it never duplicates points.
     */
    fun parse(
        text: String,
        mapWidth: Int,
        mapHeight: Int,
        floorKey: String,
        batch: String,
        existing: Set<String> = emptySet(),
        plan: PlanIdentity = PlanIdentity()
    ): Preview {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val (source, raws) = if (trimmed.startsWith("{")) parseJson(trimmed) else parseCsv(trimmed)
        val sourceWidth = source.width ?: mapWidth
        val sourceHeight = source.height ?: mapHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) throw ConfigException("Invalid template image size")
        val planMatch = matchPlan(source, plan)
        val aspect = sourceWidth.toDouble() / sourceHeight
        val mapAspect = mapWidth.toDouble() / mapHeight
        if (abs(aspect - mapAspect) / mapAspect > ASPECT_TOLERANCE) {
            throw ConfigException("The template was made for a ${sourceWidth}×$sourceHeight plan; this plan is ${mapWidth}×$mapHeight (different proportions)")
        }
        if (raws.isEmpty()) throw ConfigException("The template has no points")
        if (raws.size > MAX_POINTS) throw ConfigException("At most $MAX_POINTS points")
        val repeated = raws.mapNotNull { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
        val scale = mapWidth.toDouble() / sourceWidth
        val outsideLabels = mutableListOf<String>()
        val seenIds = HashSet<String>()
        val points = raws.mapIndexedNotNull { index, raw ->
            if (raw.id != null && !seenIds.add(raw.id)) return@mapIndexedNotNull null
            val x = raw.x * scale
            val y = raw.y * scale
            val label = raw.label.ifBlank { raw.id ?: "T${index + 1}" }.take(40)
            if (x < 0 || y < 0 || x > mapWidth || y > mapHeight || x.isNaN() || y.isNaN()) {
                outsideLabels += label
                return@mapIndexedNotNull null
            }
            PlannedPoint(
                pointId = EsxIdFactory.create("planned", "$floorKey|${raw.id ?: "${raw.label}|${raw.x}|${raw.y}"}"),
                label = label,
                x = x.toFloat(),
                y = y.toFloat(),
                batch = batch
            )
        }.distinctBy(PlannedPoint::pointId)
        val duplicates = points.count { it.pointId in existing }
        return Preview(points.filter { it.pointId !in existing }, sourceWidth, sourceHeight, scale, outsideLabels.size, duplicates,
            repeated, outsideLabels, planMatch)
    }

    /** A template that names another plan is refused; one that names this plan is matched. */
    private fun matchPlan(source: Source, plan: PlanIdentity): String {
        val sha = source.sha256?.lowercase()
        if (sha != null && plan.imageSha256 != null) {
            if (sha != plan.imageSha256.lowercase()) throw ConfigException("The template was made for another plan image (SHA-256 differs)")
            return PlanMatch.MATCHED
        }
        if (source.floorPlanId != null && plan.floorPlanId != null) {
            if (!source.floorPlanId.equals(plan.floorPlanId, ignoreCase = true)) {
                throw ConfigException("The template was made for another floor plan (${source.floorPlanId})")
            }
            return PlanMatch.MATCHED
        }
        return PlanMatch.UNVERIFIED
    }

    private fun parseJson(text: String): Pair<Source, List<Raw>> {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse { throw ConfigException("Not a JSON file") }
        if (root.get("format")?.asString != FORMAT) throw ConfigException("\"format\" must be \"$FORMAT\"")
        if (root.get("version")?.asInt != 1) throw ConfigException("Unsupported version (expected 1)")
        val image = root.getAsJsonObject("image") ?: throw ConfigException("\"image\" (width, height of the plan the points refer to) is required")
        fun optional(name: String) = image.get(name)?.takeUnless { it.isJsonNull }?.asString?.trim()?.ifEmpty { null }
        val source = Source(image.get("width").asInt, image.get("height").asInt, optional("floorPlanId"), optional("sha256"))
        val points = root.getAsJsonArray("points")?.map { element ->
            val p = element.asJsonObject
            Raw(
                id = p.get("id")?.takeUnless { it.isJsonNull }?.asString?.trim()?.ifEmpty { null },
                label = p.get("label")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                x = p.get("x")?.asDouble ?: throw ConfigException("A point has no \"x\""),
                y = p.get("y")?.asDouble ?: throw ConfigException("A point has no \"y\"")
            )
        }.orEmpty()
        return source to points
    }

    private val IMAGE_LINE = Regex("""image\s+(\d+)\s*[x×]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val KEY_VALUE = Regex("""(floorPlanId|sha256)\s*=\s*(\S+)""", RegexOption.IGNORE_CASE)

    private fun parseCsv(text: String): Pair<Source, List<Raw>> {
        var body = text
        var source = Source(null, null, null, null)
        val firstLine = body.substringBefore('\n').trim()
        if (firstLine.startsWith("#")) {
            val size = IMAGE_LINE.find(firstLine)
            val keys = KEY_VALUE.findAll(firstLine).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            source = Source(size?.groupValues?.get(1)?.toInt(), size?.groupValues?.get(2)?.toInt(), keys["floorplanid"], keys["sha256"])
            body = body.substringAfter('\n', "")
        }
        val separator = Csv.detectSeparator(body)
        val records = Csv.parse(body, separator).filter { record -> record.any { it.isNotBlank() } }
        if (records.isEmpty()) return source to emptyList()
        val header = records.first().map { it.trim().lowercase() }
        val hasHeader = "x" in header && "y" in header
        val columns = if (hasHeader) header else listOf("label", "x", "y")
        val xi = columns.indexOf("x")
        val yi = columns.indexOf("y")
        val li = columns.indexOfFirst { it == "label" || it == "name" }
        val ii = columns.indexOf("id")
        if (xi < 0 || yi < 0) throw ConfigException("The CSV needs x and y columns")
        return source to records.drop(if (hasHeader) 1 else 0).mapIndexed { n, cells ->
            fun number(i: Int) = cells.getOrNull(i)?.trim()?.toDoubleOrNull() ?: throw ConfigException("Line ${n + 1}: invalid x/y")
            Raw(cells.getOrNull(ii)?.trim()?.ifEmpty { null }, cells.getOrNull(li)?.trim().orEmpty(), number(xi), number(yi))
        }
    }
}

/**
 * Small RFC 4180 reader (mejora 20): quoted fields with `""` for a quote, separators and line
 * breaks inside quotes, and outside quotes a backslash escaping the next character (`\,`, `\;`).
 */
internal object Csv {
    fun detectSeparator(text: String): Char {
        var commas = 0
        var semicolons = 0
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> quoted = !quoted
                quoted -> Unit
                c == '\n' -> break
                c == ',' -> commas++
                c == ';' -> semicolons++
            }
        }
        return if (semicolons > commas) ';' else ','
    }

    fun parse(text: String, separator: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    '\\' -> if (i + 1 < text.length) { field.append(text[i + 1]); i++ }
                    separator -> { record.add(field.toString()); field.clear() }
                    '\r' -> Unit
                    '\n' -> { record.add(field.toString()); field.clear(); records.add(record); record = mutableListOf() }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (quoted) throw ConfigException("The CSV has an unterminated quoted field")
        if (field.isNotEmpty() || record.isNotEmpty()) { record.add(field.toString()); records.add(record) }
        return records
    }
}
