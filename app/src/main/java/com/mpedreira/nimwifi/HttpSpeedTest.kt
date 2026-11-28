package com.mpedreira.nimwifi

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Time-bounded HTTP throughput test. The defaults target Cloudflare's speed test, but any
 * server works: the download URL is fetched repeatedly over several parallel connections ({bytes} is replaced with the chunk
 * size when present) and the upload URL receives POSTed random bytes, e.g. a LibreSpeed backend.
 * [open] lets Android bind the connections to the Wi-Fi network.
 */
class HttpSpeedTest(
    private val open: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
    private val streams: Int = DEFAULT_STREAMS,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Tracks connections and workers so cancelling or a deadline closes them. */
    private val control: MeasurementControl = MeasurementControl(),
    /** Largest upload body per request (the provider's limit; see [InternetServer]). */
    private val maxUploadChunkBytes: Int = MAX_UPLOAD_CHUNK_BYTES,
    /** Wall clock, to turn a `Retry-After` HTTP date into a delay. */
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    /** Last data centre / location the provider reported (Cloudflare: CF-RAY suffix). */
    private val location = AtomicReference<String?>()
    /**
     * Download rate over [durationSeconds], summed over parallel streams. A server that answers
     * with empty bodies is reported as [ErrorCodes.EMPTY_BODY], never as 0 Mbit/s.
     */
    fun download(urlTemplate: String, durationSeconds: Int): TransferResult {
        val url = URL(urlTemplate.replace(BYTES_PLACEHOLDER, DOWNLOAD_CHUNK_BYTES.toString()))
        val result = parallel(durationSeconds) { deadline, counted ->
            val buffer = ByteArray(BUFFER_SIZE)
            request(url, "GET") { input ->
                while (nanoTime() < deadline && !control.isCancelled) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    counted.addAndGet(read.toLong())
                }
            }
        }
        if (result.bytes == 0L) throw MeasurementException(ErrorCodes.EMPTY_BODY, "download returned an empty body")
        return result.copy(serverLocation = location.get())
    }

    /**
     * Upload rate, summed over parallel streams. Only whole requests the server acknowledged are
     * counted, so the last one may overrun the duration slightly. The server's answer to each
     * upload is read up to [MAX_UPLOAD_RESPONSE_BYTES].
     */
    fun upload(url: String, durationSeconds: Int): TransferResult {
        val target = URL(url)
        val payload = ByteArray(maxUploadChunkBytes).also(SecureRandom()::nextBytes)
        return parallel(durationSeconds) { _, counted ->
            val requestStart = nanoTime()
            val size = uploadChunk.get()
            request(target, "POST", send = { connection ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(size)
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.outputStream.use { it.write(payload, 0, size) }
            }) { input -> readBounded(input, MAX_UPLOAD_RESPONSE_BYTES) }
            counted.addAndGet(size.toLong())
            // Grow requests while they are short, so per-request overhead does not dominate.
            if (nanoTime() - requestStart < NANOS_PER_SECOND / 2) {
                uploadChunk.updateAndGet { (it * 2).coerceAtMost(maxUploadChunkBytes) }
            }
        }
    }

    private val uploadChunk = AtomicInteger(MIN_UPLOAD_CHUNK_BYTES)

    /**
     * Runs [worker] repeatedly on [streams] threads until the deadline; one TCP connection
     * alone often cannot fill a fast or high-latency link. Fails only if no bytes got through.
     */
    private fun parallel(durationSeconds: Int, worker: (deadline: Long, counted: AtomicLong) -> Unit): TransferResult {
        uploadChunk.set(MIN_UPLOAD_CHUNK_BYTES.coerceAtMost(maxUploadChunkBytes))
        val counted = AtomicLong()
        val firstError = AtomicReference<Exception>()
        // RC4 · 1: a 429 on one connection stops every new request to that server.
        val limited = AtomicReference<RateLimitedException?>()
        val start = nanoTime()
        val deadline = start + durationSeconds * NANOS_PER_SECOND
        val workers = (1..streams).map { index ->
            Thread {
                while (nanoTime() < deadline && !control.isCancelled && limited.get() == null) {
                    try {
                        worker(deadline, counted)
                    } catch (e: RateLimitedException) {
                        limited.compareAndSet(null, e)
                        break
                    } catch (e: Exception) {
                        firstError.compareAndSet(null, e)
                        break
                    }
                }
            }.apply { isDaemon = true; name = "speedtest-$index"; control.track(this); start() }
        }
        workers.forEach { it.join() }
        control.checkActive()
        val elapsed = nanoTime() - start
        // Bytes other connections moved before the 429 are kept for diagnosis, never as a rate.
        limited.get()?.let { throw RateLimitedException(it.host, it.retryAfterMillis, counted.get(), elapsed / NANOS_PER_SECOND.toDouble()) }
        if (counted.get() == 0L) firstError.get()?.let { throw it }
        return TransferResult(bitsPerSecond(counted.get(), elapsed), counted.get(), elapsed / NANOS_PER_SECOND.toDouble())
    }

    private fun request(
        url: URL,
        method: String,
        send: ((HttpURLConnection) -> Unit)? = null,
        receive: (InputStream) -> Unit
    ) {
        val connection = control.track(open(url))
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            send?.invoke(connection)
            val code = connection.responseCode
            connection.getHeaderField("cf-ray")?.substringAfterLast('-', "")?.takeIf { it.length in 2..5 && it.all(Char::isLetter) }?.let { location.set(it.uppercase()) }
            when {
                code == 429 -> throw RateLimitedException(RateLimits.endpoint(url.host, url.port.takeIf { it > 0 } ?: url.defaultPort),
                    RetryAfter.parse(connection.getHeaderField("Retry-After"), wallClock()))
                code == 407 -> throw MeasurementException(ErrorCodes.PROXY_AUTH_REQUIRED, "HTTP 407 from the proxy for ${url.host}: proxy authentication required")
                code !in 200..299 -> throw MeasurementException(ErrorCodes.HTTP_STATUS, "HTTP $code from ${url.host}")
            }
            connection.inputStream.use(receive)
        } finally {
            control.untrack(connection)
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, limit: Int) {
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > limit) throw MeasurementException(ErrorCodes.RESPONSE_TOO_LARGE, "upload response larger than $limit bytes")
        }
    }

    private fun bitsPerSecond(bytes: Long, nanos: Long): Double =
        if (nanos > 0L) bytes * 8.0 * NANOS_PER_SECOND / nanos else 0.0

    private companion object {
        const val BYTES_PLACEHOLDER = "{bytes}"
        const val DEFAULT_STREAMS = 4
        const val DOWNLOAD_CHUNK_BYTES = 25_000_000
        const val MIN_UPLOAD_CHUNK_BYTES = 256 * 1024
        const val MAX_UPLOAD_CHUNK_BYTES = 16 * 1024 * 1024
        const val BUFFER_SIZE = 64 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 10_000
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val USER_AGENT = "nimWifi"
        const val MAX_UPLOAD_RESPONSE_BYTES = 64 * 1024
    }
}

/** RC4 · 1: `Retry-After` as delta-seconds or as an HTTP date, bounded to a day. */
object RetryAfter {
    private const val MAX_MILLIS = 24L * 60 * 60 * 1000

    fun parse(header: String?, nowMillis: Long): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { seconds -> return (seconds * 1000).coerceIn(0, MAX_MILLIS) }
        val at = runCatching {
            java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (at - nowMillis).coerceIn(0, MAX_MILLIS)
    }
}

/**
 * RC4 · 1: servers that answered 429, process-wide: no new request goes to them before the time
 * they allow (their Retry-After, else [DEFAULT_HOLD_MILLIS]). Other destinations go on.
 */
object RateLimits {
    const val DEFAULT_HOLD_MILLIS = 60_000L
    private val until = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun record(host: String, retryAfterMillis: Long?, nowMillis: Long): Long {
        val at = nowMillis + (retryAfterMillis ?: DEFAULT_HOLD_MILLIS)
        until.merge(host.lowercase(), at, ::maxOf)
        return at
    }

    /** When [host] may be tried again, or null when it is not limited now. */
    fun limitedUntil(host: String?, nowMillis: Long): Long? {
        host ?: return null
        val at = until[host.lowercase()] ?: return null
        if (at <= nowMillis) {
            until.remove(host.lowercase(), at)
            return null
        }
        return at
    }

    fun clear() = until.clear()

    /** The key of a destination: host and effective port (two services on one host are apart). */
    fun endpoint(host: String, port: Int): String = "${host.lowercase()}:$port"
}
