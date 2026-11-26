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
    private val control: MeasurementControl = MeasurementControl()
) {
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
        return result
    }

    /**
     * Upload rate, summed over parallel streams. Only whole requests the server acknowledged are
     * counted, so the last one may overrun the duration slightly. The server's answer to each
     * upload is read up to [MAX_UPLOAD_RESPONSE_BYTES].
     */
    fun upload(url: String, durationSeconds: Int): TransferResult {
        val target = URL(url)
        val payload = ByteArray(MAX_UPLOAD_CHUNK_BYTES).also(SecureRandom()::nextBytes)
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
                uploadChunk.updateAndGet { (it * 2).coerceAtMost(MAX_UPLOAD_CHUNK_BYTES) }
            }
        }
    }

    private val uploadChunk = AtomicInteger(MIN_UPLOAD_CHUNK_BYTES)

    /**
     * Runs [worker] repeatedly on [streams] threads until the deadline; one TCP connection
     * alone often cannot fill a fast or high-latency link. Fails only if no bytes got through.
     */
    private fun parallel(durationSeconds: Int, worker: (deadline: Long, counted: AtomicLong) -> Unit): TransferResult {
        uploadChunk.set(MIN_UPLOAD_CHUNK_BYTES)
        val counted = AtomicLong()
        val firstError = AtomicReference<Exception>()
        val start = nanoTime()
        val deadline = start + durationSeconds * NANOS_PER_SECOND
        val workers = (1..streams).map { index ->
            Thread {
                while (nanoTime() < deadline && !control.isCancelled) {
                    try {
                        worker(deadline, counted)
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
            if (code !in 200..299) throw MeasurementException(ErrorCodes.HTTP_STATUS, "HTTP $code from ${url.host}")
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
