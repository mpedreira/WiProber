package com.mpedreira.nimwifi

import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Local stand-in for speed.cloudflare.com: GET /__down?bytes=N and POST /__up. */
class HttpSpeedTestTest {
    private lateinit var server: ServerSocket
    private val uploaded = AtomicLong()
    private val requestedSizes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before
    fun startServer() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use(::handle) } }
            }
        }
    }

    @After
    fun stopServer() = server.close()

    @Test
    fun downloadsUntilTheDeadlineAndReplacesTheBytesPlaceholder() {
        val result = HttpSpeedTest().download("$base/__down?bytes={bytes}", 1)

        assertTrue("download ${result.bitsPerSecond} bit/s", result.bitsPerSecond > 1_000_000.0)
        assertTrue(result.bytes > 0 && result.seconds > 0.5)
        assertTrue(requestedSizes.isNotEmpty())
        assertTrue(requestedSizes.all { it == "25000000" })
    }

    @Test
    fun uploadsWholeRequestsAndCountsWhatTheServerReceived() {
        val result = HttpSpeedTest().upload("$base/__up", 1)

        assertTrue("upload ${result.bitsPerSecond} bit/s", result.bitsPerSecond > 1_000_000.0)
        assertTrue(uploaded.get() >= 256 * 1024)
    }

    @Test
    fun reportsHttpErrors() {
        try {
            HttpSpeedTest().download("$base/missing", 1)
        } catch (expected: IOException) {
            assertEquals("HTTP 404 from 127.0.0.1", expected.message)
            return
        }
        throw AssertionError("expected IOException")
    }

    /** Minimal HTTP/1.1: one request per connection, then close. */
    private fun handle(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val requestLine = readLine(input)
        var contentLength = 0L
        while (true) {
            val header = readLine(input)
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toLong()
            }
        }
        val path = requestLine.split(' ')[1]
        val output = socket.getOutputStream()
        fun head(status: String, length: Long) = output.write(
            "HTTP/1.1 $status\r\nContent-Length: $length\r\nConnection: close\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII)
        )
        when {
            path.startsWith("/__down") -> {
                val size = path.substringAfter("bytes=")
                requestedSizes += size
                head("200 OK", size.toLong())
                val block = ByteArray(64 * 1024)
                var left = size.toLong()
                while (left > 0) {
                    val n = minOf(left, block.size.toLong()).toInt()
                    output.write(block, 0, n)
                    left -= n
                }
            }
            path.startsWith("/__up") -> {
                var left = contentLength
                val block = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = input.read(block, 0, minOf(left, block.size.toLong()).toInt())
                    if (n < 0) break
                    left -= n
                }
                uploaded.addAndGet(contentLength - left)
                head("200 OK", 0)
            }
            else -> head("404 Not Found", 0)
        }
        output.flush()
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.toString()
    }

    @Test
    fun anEmptyDownloadBodyIsReportedAsSuchNeverAsZero() {
        try {
            HttpSpeedTest().download("$base/__down?bytes=0", 1)
            throw AssertionError("expected EMPTY_BODY")
        } catch (expected: MeasurementException) {
            assertEquals(ErrorCodes.EMPTY_BODY, expected.code)
        }
    }

    @Test
    fun anOversizedUploadResponseIsRejected() {
        MiniHttpServer { MiniHttpServer.Response(200, "x".repeat(100_000)) }.use { big ->
            try {
                HttpSpeedTest().upload("${big.base}/up", 1)
                throw AssertionError("expected RESPONSE_TOO_LARGE")
            } catch (expected: MeasurementException) {
                assertEquals(ErrorCodes.RESPONSE_TOO_LARGE, expected.code)
            }
        }
    }

    @Test
    fun aBlockedUploadStopsWhenTheRunIsCancelled() {
        // Accepts connections but never reads the body nor answers.
        ServerSocket(0).use { silent ->
            thread(isDaemon = true) { while (!silent.isClosed) runCatching { silent.accept() } }
            val control = MeasurementControl()
            thread(isDaemon = true) { Thread.sleep(500); control.cancel() }
            val started = System.nanoTime()
            try {
                HttpSpeedTest(control = control).upload("http://127.0.0.1:${silent.localPort}/up", 20)
                throw AssertionError("expected cancellation")
            } catch (expected: MeasurementCancelled) {
                assertEquals(ErrorCodes.CANCELLED, expected.reason)
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        }
    }
}
