package com.mpedreira.nimwifi

/**
 * One row of the user's BSSID → physical AP table (`apNames` in `wiprober-config.json`, or
 * `ap-names.csv` in a configuration ZIP). Display only: every BSSID is still a separate measured
 * AP. Several BSSIDs with the same [name] (radios of one AP) is the user's explicit decision;
 * nothing is ever grouped by prefix, OUI or similarity.
 */
@JsonModel
data class ApName(
    /** Normalized: lowercase, `aa:bb:cc:dd:ee:ff`. */
    val bssid: String,
    val name: String,
    val location: String? = null,
    val floor: String? = null
)

object Bssids {
    private val HEX12 = Regex("[0-9a-fA-F]{12}")
    private val SEPARATED = Regex("[0-9a-fA-F]{2}([:-])[0-9a-fA-F]{2}(\\1[0-9a-fA-F]{2}){4}")

    /** `AA-BB-CC-DD-EE-FF`, `aa:bb:…` or `aabbccddeeff` → `aa:bb:cc:dd:ee:ff`; null when malformed. */
    fun normalize(raw: String?): String? {
        val text = raw?.trim() ?: return null
        val hex = when {
            SEPARATED.matches(text) -> text.replace(":", "").replace("-", "")
            HEX12.matches(text) -> text
            else -> return null
        }.lowercase()
        return hex.chunked(2).joinToString(":")
    }
}

/** Lookup of [ApName]s by normalized BSSID. An unmapped BSSID is shown as it is. */
class ApDirectory(entries: List<ApName>) {
    private val byBssid: Map<String, ApName> = entries.associateBy { it.bssid }

    val isEmpty: Boolean get() = byBssid.isEmpty()
    val size: Int get() = byBssid.size

    fun of(bssid: String?): ApName? = Bssids.normalize(bssid)?.let(byBssid::get)

    fun entries(): List<ApName> = byBssid.values.toList()

    fun name(bssid: String?): String? = of(bssid)?.name

    /** `AP-P3-04 (aa:bb:…)`, or the BSSID alone when it is not in the table. */
    fun label(bssid: String?): String? {
        bssid ?: return null
        return of(bssid)?.let { "${it.name} ($bssid)" } ?: bssid
    }

    /** `entre AP-P3-04 y AP-P3-05` (names when known, else BSSIDs). */
    fun between(from: String?, to: String?): String = when {
        from == null && to == null -> ""
        from == null -> "hacia ${name(to) ?: to}"
        to == null -> "desde ${name(from) ?: from}"
        else -> "entre ${name(from) ?: from} y ${name(to) ?: to}"
    }

    companion object {
        val EMPTY = ApDirectory(emptyList())
        const val MAX_ROWS = 5_000
        const val CSV_FILE_NAME = "ap-names.csv"

        /** Validated table: every BSSID well formed and present once; names required. */
        fun validated(rows: List<Triple<Int, Map<String, String?>, String>>): List<ApName> {
            if (rows.size > MAX_ROWS) throw ConfigException("At most $MAX_ROWS AP names")
            val seen = HashMap<String, Int>()
            return rows.map { (index, fields, where) ->
                val raw = fields["bssid"]
                val bssid = Bssids.normalize(raw) ?: throw ConfigException("$where: \"${raw.orEmpty()}\" is not a BSSID (aa:bb:cc:dd:ee:ff)")
                seen.put(bssid, index)?.let { first -> throw ConfigException("$where: BSSID $bssid is already in row $first") }
                val name = fields["name"]?.trim().orEmpty()
                if (name.isEmpty() || name.length > 60) throw ConfigException("$where: \"name\" is required (max 60)")
                ApName(bssid, name, fields["location"]?.trim()?.take(80)?.ifEmpty { null }, fields["floor"]?.trim()?.take(60)?.ifEmpty { null })
            }
        }

        /** `bssid,name,location,floor` (header required; location and floor optional), RFC 4180. */
        fun parseCsv(text: String): List<ApName> {
            val body = text.trimStart('\uFEFF')
            val records = Csv.parse(body, Csv.detectSeparator(body)).filter { r -> r.any { it.isNotBlank() } }
            if (records.isEmpty()) return emptyList()
            val header = records.first().map { it.trim().lowercase() }
            if ("bssid" !in header || "name" !in header) throw ConfigException("$CSV_FILE_NAME needs a header with bssid and name")
            return validated(records.drop(1).mapIndexed { i, cells ->
                Triple(i + 2, header.indices.associate { header[it] to cells.getOrNull(it) }, "$CSV_FILE_NAME line ${i + 2}")
            })
        }
    }
}

/**
 * How the report names one radio of a floor: the user's table name (if any), the BSSID, and its
 * band and channel when the floor's local scans or tests saw it. [radios]: normalized BSSID →
 * frequency (MHz). Display only.
 */
class ApLabels(
    val directory: ApDirectory = ApDirectory.EMPTY,
    private val radios: Map<String, Int> = emptyMap(),
    /** Inferred AP identity by normalized BSSID ([ApIdentity]); shown marked as inferred. */
    private val identities: Map<String, ApIdentityInfo> = emptyMap()
) {
    fun identity(bssid: String?): ApIdentityInfo? = Bssids.normalize(bssid)?.let(identities::get)

    fun frequency(bssid: String?): Int? = Bssids.normalize(bssid)?.let(radios::get)

    fun band(bssid: String?): String? = WifiBands.of(frequency(bssid))

    /** `AP-P3-04 (aa:bb:… · 5 GHz c36)`, `aa:bb:… (2,4 GHz c6)` or the BSSID alone. */
    fun label(bssid: String?): String? {
        bssid ?: return null
        val details = listOfNotNull(WifiBands.radio(frequency(bssid)), identity(bssid)?.shortVendor?.let { "$it (inferido)" })
        val name = directory.name(bssid)
        return when {
            name != null -> "$name (" + (listOf(bssid) + details).joinToString(" · ") + ")"
            details.isNotEmpty() -> "$bssid (" + details.joinToString(" · ") + ")"
            else -> bssid
        }
    }

    /** `entre AP-P3-04 y AP-P3-05`, plus `· cambio de banda 5 GHz → 2,4 GHz` when the bands differ. */
    fun between(from: String?, to: String?): String {
        val base = directory.between(from, to)
        return bandChange(from, to)?.let { if (base.isEmpty()) it else "$base · $it" } ?: base
    }

    /** `cambio de banda 5 GHz → 2,4 GHz` when both bands are known and differ, else null. */
    fun bandChange(from: String?, to: String?): String? {
        val a = band(from) ?: return null
        val b = band(to) ?: return null
        return if (a == b) null else "cambio de banda ${WifiBands.label(a)} → ${WifiBands.label(b)}"
    }

    companion object {
        val EMPTY = ApLabels()

        /** Frequencies of the radios a floor's local scans and active tests saw (latest wins). */
        fun radios(survey: ProjectSnapshot): Map<String, Int> {
            val result = linkedMapOf<String, Int>()
            fun put(bssid: String?, frequency: Int?) {
                val key = Bssids.normalize(bssid) ?: return
                if (frequency != null && WifiBands.of(frequency) != null) result[key] = frequency
            }
            survey.scanPoints.filterNot { it.importedFromEsx }.sortedBy { it.timestamp }.forEach { p -> p.wifiNetworks.forEach { put(it.bssid, it.frequency) } }
            survey.continuousScanSessions.filterNot { it.importedFromEsx }.forEach { s -> s.scanResults.forEach { r -> r.wifiNetworks.forEach { put(it.bssid, it.frequency) } } }
            survey.activeRuns.sortedBy { it.startedAt }.forEach { run -> run.results?.tests.orEmpty().forEach { put(it.bssidStart, it.frequencyMhz) } }
            return result
        }
    }
}
