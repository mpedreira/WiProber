package com.mpedreira.nimwifi

import java.net.URL

/** RC4 · 2: the HTTP proxy Android has configured for the measured Wi-Fi network. */
object ProxyKind {
    const val NONE = "none"
    const val MANUAL = "manual"
    const val PAC = "pac"
    const val UNKNOWN = "unknown"
}

/** RC4 · 2: how a request actually went, as the client that made it can tell. */
object HttpRoute {
    /** The client connected through an explicit proxy. */
    const val PROXY = "proxy"
    const val DIRECT = "direct"
    /** Not enough evidence (failed before the route was known, or a PAC resolved by Android). */
    const val UNDETERMINED = "undetermined"

    fun label(route: String?): String = when (route) {
        PROXY -> "proxy explícito"
        DIRECT -> "directa"
        else -> "indeterminada"
    }

    fun proxyLabel(kind: String?): String = when (kind) {
        ProxyKind.NONE -> "ninguno"
        ProxyKind.MANUAL -> "manual"
        ProxyKind.PAC -> "PAC"
        else -> "desconocido"
    }
}

/**
 * The network's proxy settings. [forUrl] says what applies to one URL: a manual proxy does not
 * apply to hosts in its exclusion list; a PAC script is evaluated by Android per URL, so its
 * outcome is only known from the request itself.
 */
data class ProxyConfig(
    val kind: String,
    val host: String? = null,
    val port: Int? = null,
    val exclusions: List<String> = emptyList(),
    val pacUrl: String? = null
) {
    fun forUrl(url: URL): String = when (kind) {
        ProxyKind.MANUAL -> if (excluded(url.host)) ProxyKind.NONE else ProxyKind.MANUAL
        else -> kind
    }

    /** True when the request may go through a proxy (so a failed direct TCP check proves nothing). */
    fun mayProxy(url: URL): Boolean = forUrl(url) == ProxyKind.MANUAL || forUrl(url) == ProxyKind.PAC

    private fun excluded(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return exclusions.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { rule ->
            when {
                rule.startsWith("*.") -> h.endsWith(rule.substring(1)) || h == rule.substring(2)
                rule.startsWith("*") -> h.endsWith(rule.substring(1))
                else -> h == rule
            }
        }
    }

    companion object {
        val NONE = ProxyConfig(ProxyKind.NONE)
        val UNKNOWN = ProxyConfig(ProxyKind.UNKNOWN)
    }
}

/** What one request showed: the proxy configured for its URL, the route and the HTTP status. */
data class RouteObservation(val proxyConfig: String, val route: String, val httpStatus: Int?) {
    companion object {
        /**
         * From HttpURLConnection.usingProxy() after the response. With PAC, Android resolves the
         * script through a local proxy, so "via proxy" says nothing about where PAC sent it.
         */
        fun observed(proxyConfig: String, usingProxy: Boolean, httpStatus: Int?) = RouteObservation(
            proxyConfig,
            when {
                !usingProxy -> HttpRoute.DIRECT
                proxyConfig == ProxyKind.PAC -> HttpRoute.UNDETERMINED
                else -> HttpRoute.PROXY
            },
            httpStatus
        )

        fun undetermined(proxyConfig: String) = RouteObservation(proxyConfig, HttpRoute.UNDETERMINED, null)
    }
}
