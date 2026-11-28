package com.mpedreira.nimwifi

import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * RC4 · 2: *Probar servidor*. One short, bounded request per URL (download and upload apart,
 * even on the same host) with the same client and Wi-Fi network the measurement uses. Never
 * saved with a point. Android-free: the caller gives the network's connection opener and proxy.
 */
object ServerCheck {
    const val DOWNLOAD_LIMIT_BYTES = 1_000_000
    const val UPLOAD_BYTES = 64 * 1024
    const val DEADLINE_MILLIS = 8_000L

    data class UrlCheck(
        val direction: String,
        /** Host and path, never the query (it may hold secrets). */
        val target: String,
        val scheme: String,
        val proxyConfig: String,
        val route: String,
        val httpStatus: Int?,
        /** The server answered as the client expects (2xx, and a body for downloads). */
        val ok: Boolean,
        val bytes: Long,
        val millis: Long,
        val errorCode: String?,
        val detail: String?,
        val retryAfterMillis: Long? = null
    )

    fun run(
        downloadUrl: String,
        uploadUrl: String,
        open: (URL) -> HttpURLConnection,
        proxyFor: (URL) -> String,
        control: MeasurementControl,
        nowMillis: () -> Long = System::currentTimeMillis
    ): List<UrlCheck> = buildList {
        if (downloadUrl.isNotBlank()) add(check(HttpSpeedTest.DOWNLOAD, downloadUrl.replace("{bytes}", DOWNLOAD_LIMIT_BYTES.toString()), open, proxyFor, control, nowMillis))
        if (uploadUrl.isNotBlank()) add(check(HttpSpeedTest.UPLOAD, uploadUrl, open, proxyFor, control, nowMillis))
    }

    private fun check(direction: String, raw: String, open: (URL) -> HttpURLConnection, proxyFor: (URL) -> String,
                      control: MeasurementControl, nowMillis: () -> Long): UrlCheck {
        val url = runCatching { URL(raw) }.getOrNull()
            ?: return UrlCheck(direction, raw.substringBefore('?'), "?", ProxyKind.UNKNOWN, HttpRoute.UNDETERMINED, null, false, 0, 0,
                ErrorCodes.INVALID_TARGET, "not a valid URL")
        val target = "${url.host}${Sensitive.displayPath(url.path)}"
        val proxy = runCatching { proxyFor(url) }.getOrDefault(ProxyKind.UNKNOWN)
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        var route = HttpRoute.UNDETERMINED
        var status: Int? = null
        return try {
            var bytes = 0L
            control.phase(DEADLINE_MILLIS) {
                val connection = control.track(open(url))
                try {
                    connection.connectTimeout = HttpSpeedTest.CONNECT_TIMEOUT_MILLIS
                    connection.readTimeout = HttpSpeedTest.READ_TIMEOUT_MILLIS
                    connection.useCaches = false
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", HttpSpeedTest.USER_AGENT)
                    if (direction == HttpSpeedTest.UPLOAD) {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(UPLOAD_BYTES)
                        connection.setRequestProperty("Content-Type", "application/octet-stream")
                        connection.outputStream.use { it.write(ByteArray(UPLOAD_BYTES).also(SecureRandom()::nextBytes)) }
                        bytes = UPLOAD_BYTES.toLong()
                    }
                    status = connection.responseCode
                    route = RouteObservation.observed(proxy, connection.usingProxy(), status).route
                    if (status in 200..299) {
                        val buffer = ByteArray(16 * 1024)
                        connection.inputStream.use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (direction == HttpSpeedTest.DOWNLOAD) bytes += n
                                if (bytes >= DOWNLOAD_LIMIT_BYTES && direction == HttpSpeedTest.DOWNLOAD) break
                            }
                        }
                    } else if (status == 429) {
                        throw RateLimitedException(url.host, RetryAfter.parse(connection.getHeaderField("Retry-After"), nowMillis()))
                    }
                } finally {
                    control.untrack(connection)
                    connection.disconnect()
                }
            }
            val code = when (val s = status) {
                null -> ErrorCodes.HTTP_STATUS
                in 200..299 -> if (direction == HttpSpeedTest.DOWNLOAD && bytes == 0L) ErrorCodes.EMPTY_BODY else null
                407 -> ErrorCodes.PROXY_AUTH_REQUIRED
                else -> ErrorCodes.HTTP_STATUS
            }
            UrlCheck(direction, target, url.protocol, proxy, route, status, code == null, bytes, elapsed(), code,
                if (code == null) null else "HTTP $status")
        } catch (e: MeasurementCancelled) {
            throw e
        } catch (e: RateLimitedException) {
            UrlCheck(direction, target, url.protocol, proxy, route, 429, false, 0, elapsed(), ErrorCodes.RATE_LIMITED,
                "HTTP 429: the server is limiting requests", e.retryAfterMillis)
        } catch (e: Exception) {
            UrlCheck(direction, target, url.protocol, proxy, route, status, false, 0, elapsed(), ErrorClassifier.code(e), ErrorClassifier.detail(e))
        }
    }
}
