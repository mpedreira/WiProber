package com.mpedreira.nimwifi

/** A Wi-Fi network Android offers for a measurement. [ssid] is null when Android does not say. */
data class NetworkCandidate(val id: Long, val isDefault: Boolean, val validated: Boolean, val ssid: String?)

/**
 * Mejora 13: which Wi-Fi network an active test uses. With an expected SSID, the eligible network
 * that reports it wins; otherwise the deterministic rule (only one, the default, a validated one,
 * the lowest handle). Internet access is never required: a local Wi-Fi without Internet is
 * measured even while mobile data is the default network.
 */
object NetworkSelectionPolicy {
    const val ONLY = "only"
    const val DEFAULT = "default"
    const val VALIDATED = "validated"
    const val LOWEST_HANDLE = "lowest-handle"
    const val EXPECTED_SSID = "expected-ssid"

    fun choose(candidates: List<NetworkCandidate>, expectedSsid: String?): Pair<NetworkCandidate, String>? {
        if (candidates.isEmpty()) return null
        val expected = expectedSsid?.trim()?.takeIf { it.isNotEmpty() }
        if (expected != null && candidates.size > 1) {
            candidates.filter { it.ssid == expected }.minByOrNull(NetworkCandidate::id)?.let { return it to EXPECTED_SSID }
        }
        return when {
            candidates.size == 1 -> candidates.single() to ONLY
            else -> candidates.firstOrNull { it.isDefault }?.let { it to DEFAULT }
                ?: candidates.filter { it.validated }.minByOrNull(NetworkCandidate::id)?.let { it to VALIDATED }
                ?: (candidates.minBy(NetworkCandidate::id) to LOWEST_HANDLE)
        }
    }
}

/** Whether the network a test will use is the one the technician declared. */
object NetworkVerification {
    const val MATCHED = "matched"
    const val MISMATCH = "mismatch"
    /** Android did not say which SSID the network has: kept as an explicit state. */
    const val UNKNOWN = "unknown"
    const val NOT_DECLARED = "not_declared"
    const val NO_WIFI = "no_wifi"

    fun of(expectedSsid: String?, network: Boolean, association: Association?): String {
        val expected = expectedSsid?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            !network -> NO_WIFI
            association == null || !association.known || association.ssid == null -> if (expected == null) NOT_DECLARED else UNKNOWN
            expected == null -> NOT_DECLARED
            association.ssid == expected -> MATCHED
            else -> MISMATCH
        }
    }

    /** States that need an informed decision before sending tests. */
    fun needsDecision(state: String): Boolean = state == MISMATCH || state == UNKNOWN
}

/** What the phone sees before a run: shown to the technician and kept with the run. */
data class NetworkPreflight(
    val networkId: String?,
    val selection: String?,
    val observedSsid: String?,
    val observedBssid: String?,
    val expectedSsid: String?,
    val verification: String,
    /** SSIDs of every eligible Wi-Fi network (null entries: not reported by Android). */
    val eligible: List<String?>
)
