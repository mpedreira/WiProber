package com.example.wiprober

import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * The single Wi-Fi network every test of a run goes through. Names are resolved by its resolver,
 * sockets and HTTP connections are bound to it and link properties and association come from it,
 * so nothing silently uses the default (possibly mobile) network. A run never falls back to
 * another network: when [isAvailable] turns false the remaining phases fail with [ErrorCodes.WIFI_LOST].
 */
interface MeasurementNetwork {
    /** Stable for the run (Android network handle); exported to tell runs on different networks apart. */
    val id: String
    /** Why this network was chosen when several Wi-Fi networks exist (e.g. `primary`, `only`). */
    val selection: String
    val socketFactory: SocketFactory
    /** Resolves on this network only. */
    fun resolve(host: String): List<InetAddress>
    fun openHttp(url: URL): HttpURLConnection
    fun bind(socket: DatagramSocket)
    fun link(): NetworkContext
    /** Association of this network (not whatever Wi-Fi Android reports globally). */
    fun association(): Association
    fun isAvailable(): Boolean
}

/**
 * What is known about the association of the measured network. [known] is false when Android
 * does not let the app attribute SSID/BSSID to that network: the values are then null and must be
 * read as "unknown", never as "not connected".
 */
data class Association(
    val known: Boolean,
    val source: String,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val linkSpeedMbps: Int? = null,
    val rxLinkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val wifiStandard: String? = null
) {
    fun toConnectionInfo(channelWidthMhz: Int? = null, bssLoad: BssLoad? = null) = WifiConnectionInfo(
        ssid = ssid, bssid = bssid, rssi = rssi, linkSpeedMbps = linkSpeedMbps,
        rxLinkSpeedMbps = rxLinkSpeedMbps, frequencyMhz = frequencyMhz,
        channelWidthMhz = channelWidthMhz, wifiStandard = wifiStandard, bssLoad = bssLoad
    )

    companion object {
        const val SOURCE_NETWORK_CALLBACK = "network-capabilities"
        const val SOURCE_WIFI_MANAGER = "wifi-manager"
        const val SOURCE_UNKNOWN = "unknown"
        val UNKNOWN = Association(known = false, source = SOURCE_UNKNOWN)
    }
}

/** How the association changed between the start and the end of a test. */
object AssociationChange {
    /** Both ends observed with the same SSID and BSSID (says nothing about what happened in between). */
    const val SAME = "same"
    const val BSSID_CHANGED = "bssid_changed"
    const val SSID_CHANGED = "ssid_changed"
    const val UNKNOWN = "unknown"

    fun between(start: Association?, end: Association?): String = when {
        start == null || end == null || !start.known || !end.known -> UNKNOWN
        start.ssid == null || end.ssid == null || start.bssid == null || end.bssid == null -> UNKNOWN
        start.ssid != end.ssid -> SSID_CHANGED
        start.bssid != end.bssid -> BSSID_CHANGED
        else -> SAME
    }
}

/**
 * Deterministic choice among the addresses a name resolves to: IPv4 before IPv6 (warehouse
 * networks are mostly IPv4-only), resolver order within a family, at most [MAX_ATTEMPTS] tried.
 */
object AddressPolicy {
    const val MAX_ATTEMPTS = 2

    fun candidates(addresses: List<InetAddress>): List<InetAddress> =
        (addresses.filterIsInstance<Inet4Address>() + addresses.filterNot { it is Inet4Address })
            .distinct()
            .take(MAX_ATTEMPTS)
}

/** Structured error codes shown in the app and exported; details never carry secrets. */
object ErrorCodes {
    const val NOT_ON_WIFI = "not_on_wifi"
    const val WIFI_LOST = "wifi_lost"
    const val NOT_CONFIGURED = "not_configured"
    const val PORT_NOT_CONFIGURED = "port_not_configured"
    const val RESOLUTION_FAILED = "resolution_failed"
    const val CONNECT_FAILED = "connect_failed"
    const val CONNECTION_REFUSED = "connection_refused"
    const val TIMEOUT = "timeout"
    const val IPERF_BUSY = "iperf_busy"
    const val IPERF_PROTOCOL = "iperf_protocol"
    const val HTTP_STATUS = "http_status"
    const val EMPTY_BODY = "empty_body"
    const val RESPONSE_TOO_LARGE = "response_too_large"
    const val PARTIAL_LOSS = "partial_loss"
    const val NO_REPLY = "no_reply"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"
    const val SKIPPED_UNREACHABLE = "skipped_unreachable"
    const val CLEARTEXT_NOT_PERMITTED = "cleartext_not_permitted"
    const val TLS_FAILED = "tls_failed"
    const val HOSTNAME_MISMATCH = "hostname_mismatch"
    const val INVALID_TARGET = "invalid_target"
    const val NO_GATEWAY = "no_gateway"
    const val NO_DNS_SERVER = "no_dns_server"
    const val DNS_ERROR = "dns_error"
    const val EXPECTATION_FAILED = "expectation_failed"
    const val EXTRACT_FAILED = "extract_failed"
    const val UNKNOWN_VARIABLE = "unknown_variable"
    const val WRITE_NOT_ALLOWED = "write_not_allowed"
    const val IO_ERROR = "io_error"
}

/** A failure with a structured [code]; [message] is a safe, secret-free detail. */
class MeasurementException(val code: String, message: String? = null, cause: Throwable? = null) :
    IOException(message ?: code, cause)

/**
 * Maps any exception to a code and a short detail without URLs, query strings or headers
 * (they may hold credentials).
 */
object ErrorClassifier {
    fun code(e: Throwable): String = when (e) {
        is MeasurementException -> e.code
        is UnknownHostException -> ErrorCodes.RESOLUTION_FAILED
        is java.net.SocketTimeoutException -> ErrorCodes.TIMEOUT
        is java.net.ConnectException ->
            if ("refused" in e.message.orEmpty().lowercase()) ErrorCodes.CONNECTION_REFUSED else ErrorCodes.CONNECT_FAILED
        is java.net.NoRouteToHostException, is java.net.PortUnreachableException -> ErrorCodes.CONNECT_FAILED
        is javax.net.ssl.SSLPeerUnverifiedException -> ErrorCodes.HOSTNAME_MISMATCH
        is javax.net.ssl.SSLException -> ErrorCodes.TLS_FAILED
        else -> ErrorCodes.IO_ERROR
    }

    fun detail(e: Throwable): String {
        val base = (e as? MeasurementException)?.message?.takeIf { it != e.code }
            ?: e.javaClass.simpleName.ifEmpty { "error" }
        return sanitize(base)
    }

    /** Drops anything that looks like a URL query, credentials in URLs or header values. */
    fun sanitize(text: String): String = text
        .replace(Regex("""(https?://)[^/\s@]*@"""), "$1")
        .replace(Regex("""\?[^\s"']*"""), "?…")
        .replace(Regex("""(?i)(authorization|cookie|token|password|secret|api[-_]?key)\s*[:=]\s*\S+"""), "$1=…")
        .take(200)
}
