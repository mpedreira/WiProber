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

/**
 * rc.2 · 3: the run measures on the network the technician approved in the preflight, not on a
 * fresh selection. Just before any traffic the approved network is looked up by its handle and
 * its identity checked again; anything but [SAME] stops the start (no socket is opened) and asks
 * for a new decision. A BSSID change inside the same SSID and the same network is [SAME]: it is an
 * AP change, recorded as such by the run.
 */
object NetworkRevalidation {
    const val SAME = "same"
    /** The approved network is no longer available. */
    const val GONE = "gone"
    /** Another network now has the approved one's place (different handle). */
    const val REPLACED = "replaced"
    /** Same handle, but it now reports another SSID than the one approved. */
    const val SSID_CHANGED = "ssid_changed"

    fun check(approved: NetworkPreflight, current: MeasurementNetwork?, association: Association?): String = when {
        current == null -> GONE
        approved.networkId != null && current.id != approved.networkId -> REPLACED
        approved.observedSsid != null && association?.known == true && association.ssid != null &&
            association.ssid != approved.observedSsid -> SSID_CHANGED
        else -> SAME
    }

    /**
     * Opens the run's network: the approved one by handle ([byId]), or the fresh choice ([select])
     * when there was no usable preflight. Throws [NetworkChangedException] (closing what it
     * looked up) when the approved network changed. Nothing is sent here.
     */
    fun <N : MeasurementNetwork> open(approved: NetworkPreflight?, byId: (String) -> N?, select: () -> N?, close: (N) -> Unit): N? {
        val approvedId = approved?.networkId ?: return select()
        val current = byId(approvedId)
        val state = check(approved, current, current?.let { runCatching { it.association() }.getOrNull() })
        if (state != SAME) {
            current?.let(close)
            throw NetworkChangedException(state, approved)
        }
        return current
    }
}

/** The approved Wi-Fi network changed before the run started; nothing was sent. */
class NetworkChangedException(val state: String, val approved: NetworkPreflight) :
    MeasurementException(ErrorCodes.NETWORK_CHANGED, "approved Wi-Fi network ${approved.observedSsid ?: approved.networkId}: $state")
