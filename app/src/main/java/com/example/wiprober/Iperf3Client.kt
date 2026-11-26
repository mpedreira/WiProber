package com.example.wiprober

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * Minimal iperf3 client: TCP, one stream, one direction per run. It speaks the iperf3 control
 * protocol (cookie, JSON parameter/result exchange, state bytes) so it works against a stock
 * `iperf3 -s`. It never resolves names: the caller passes the address resolved on the measured
 * Wi-Fi network, and both the control and the data connection go to that same address through
 * [socketFactory] (bound to that network on Android).
 */
class Iperf3Client(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMillis: Int = 5_000,
    /** Tracks sockets and threads so cancelling or a deadline closes them. */
    private val control: MeasurementControl = MeasurementControl()
) {
    enum class Direction { DOWNLOAD, UPLOAD }

    /** Rate seen by the receiving side plus the bytes/seconds the client counted, or an exception. */
    fun run(address: InetAddress, port: Int, durationSeconds: Int, direction: Direction): TransferResult {
        val cookie = newCookie()
        val reverse = direction == Direction.DOWNLOAD
        openSocket(address, port, durationSeconds).use { control ->
            val input = DataInputStream(control.getInputStream().buffered())
            val output = DataOutputStream(control.getOutputStream())
            output.write(cookie)
            output.flush()

            var data: Socket? = null
            var transfer: Transfer? = null
            try {
                while (true) {
                    when (val state = input.readByte().toInt()) {
                        PARAM_EXCHANGE -> writeJson(output, parameters(durationSeconds, reverse))
                        CREATE_STREAMS -> {
                            data = openSocket(address, port, durationSeconds).also {
                                it.getOutputStream().apply { write(cookie); flush() }
                            }
                        }
                        TEST_START -> Unit
                        TEST_RUNNING -> {
                            val stream = data ?: throw MeasurementException(ErrorCodes.IPERF_PROTOCOL, "iperf3 server started without a data stream")
                            transfer = if (reverse) {
                                receive(stream, durationSeconds)
                            } else {
                                send(stream, durationSeconds)
                            }
                            output.writeByte(TEST_END)
                            output.flush()
                        }
                        EXCHANGE_RESULTS -> {
                            val done = transfer ?: throw MeasurementException(ErrorCodes.IPERF_PROTOCOL, "iperf3 results requested before the test ran")
                            writeJson(output, clientResults(done))
                            val serverResults = readJson(input)
                            if (!reverse) done.receiverBytesPerSecond = receiverRate(serverResults)
                        }
                        DISPLAY_RESULTS -> {
                            output.writeByte(IPERF_DONE)
                            output.flush()
                            val done = transfer ?: throw MeasurementException(ErrorCodes.IPERF_PROTOCOL, "iperf3 test finished without data")
                            return TransferResult((done.receiverBytesPerSecond ?: done.bytesPerSecond) * 8.0, done.bytes, done.seconds)
                        }
                        ACCESS_DENIED -> throw MeasurementException(ErrorCodes.IPERF_BUSY, "iperf3 server is busy")
                        SERVER_ERROR -> throw MeasurementException(ErrorCodes.IPERF_PROTOCOL, "iperf3 server error ${readServerError(input)}")
                        SERVER_TERMINATE -> throw MeasurementException(ErrorCodes.IPERF_PROTOCOL, "iperf3 server terminated the test")
                        else -> throw MeasurementException(ErrorCodes.IPERF_PROTOCOL, "unexpected iperf3 state $state")
                    }
                }
            } finally {
                transfer?.stop()
                runCatching { data?.close() }
            }
        }
    }

    private fun openSocket(address: InetAddress, port: Int, durationSeconds: Int): Socket =
        control.track(socketFactory.createSocket()).apply {
            tcpNoDelay = true
            soTimeout = (durationSeconds + CONTROL_GRACE_SECONDS) * 1_000
            connect(InetSocketAddress(address, port), connectTimeoutMillis)
        }

    /** Counts bytes until the deadline, then keeps draining so the server sender never blocks. */
    private fun receive(stream: Socket, durationSeconds: Int): Transfer {
        val counted = AtomicLong()
        val start = System.nanoTime()
        val deadline = start + durationSeconds * NANOS_PER_SECOND
        val drain = Thread {
            val buffer = ByteArray(BLOCK_SIZE)
            runCatching {
                val input = stream.getInputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (System.nanoTime() < deadline) counted.addAndGet(read.toLong())
                }
            }
        }.apply { isDaemon = true; name = "iperf3-drain"; control.track(this); start() }
        sleepUntil(deadline)
        return Transfer(counted.get(), (deadline - start) / NANOS_PER_SECOND.toDouble()) {
            runCatching { stream.close() }
            drain.join(1_000)
        }
    }

    private fun send(stream: Socket, durationSeconds: Int): Transfer {
        val block = ByteArray(BLOCK_SIZE).also(random::nextBytes)
        val output = stream.getOutputStream()
        val start = System.nanoTime()
        val deadline = start + durationSeconds * NANOS_PER_SECOND
        var sent = 0L
        while (System.nanoTime() < deadline) {
            control.checkActive()
            output.write(block)
            sent += block.size
        }
        output.flush()
        return Transfer(sent, (System.nanoTime() - start) / NANOS_PER_SECOND.toDouble()) {}
    }

    private fun parameters(durationSeconds: Int, reverse: Boolean) = JsonObject().apply {
        addProperty("tcp", true)
        addProperty("omit", 0)
        addProperty("time", durationSeconds)
        addProperty("num", 0)
        addProperty("blockcount", 0)
        addProperty("parallel", 1)
        if (reverse) addProperty("reverse", true)
        addProperty("len", BLOCK_SIZE)
        addProperty("pacing_timer", 1_000)
        addProperty("client_version", CLIENT_VERSION)
    }

    private fun clientResults(transfer: Transfer) = JsonObject().apply {
        addProperty("cpu_util_total", 0)
        addProperty("cpu_util_user", 0)
        addProperty("cpu_util_system", 0)
        addProperty("sender_has_retransmits", -1)
        add("streams", com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("id", 1)
                addProperty("bytes", transfer.bytes)
                addProperty("retransmits", -1)
                addProperty("jitter", 0)
                addProperty("errors", 0)
                addProperty("packets", 0)
                addProperty("start_time", 0)
                addProperty("end_time", transfer.seconds)
            })
        })
    }

    /** Upload speed as the server received it (what reached the wire), when it reports one. */
    private fun receiverRate(results: JsonObject): Double? {
        val stream = results.getAsJsonArray("streams")?.firstOrNull()?.asJsonObject ?: return null
        val bytes = stream.get("bytes")?.asDouble ?: return null
        val seconds = (stream.get("end_time")?.asDouble ?: 0.0) - (stream.get("start_time")?.asDouble ?: 0.0)
        return if (seconds > 0.0 && bytes > 0.0) bytes / seconds else null
    }

    private fun readServerError(input: DataInputStream): String =
        runCatching { "${input.readInt()}/${input.readInt()}" }.getOrDefault("(no code)")

    private fun writeJson(output: DataOutputStream, json: JsonObject) {
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readJson(input: DataInputStream): JsonObject {
        val length = input.readInt()
        if (length !in 0..MAX_JSON_BYTES) throw MeasurementException(ErrorCodes.IPERF_PROTOCOL, "iperf3 results too large: $length bytes")
        val bytes = ByteArray(length).also(input::readFully)
        return JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
    }

    private fun newCookie(): ByteArray {
        val cookie = ByteArray(COOKIE_SIZE)
        for (i in 0 until COOKIE_SIZE - 1) cookie[i] = COOKIE_CHARS[random.nextInt(COOKIE_CHARS.length)].code.toByte()
        return cookie // last byte stays 0: iperf3 cookies are NUL-terminated
    }

    private fun sleepUntil(deadline: Long) {
        while (true) {
            control.checkActive()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            Thread.sleep((remaining / 1_000_000L).coerceIn(1L, 200L))
        }
    }

    private class Transfer(val bytes: Long, val seconds: Double, val stop: () -> Unit) {
        var receiverBytesPerSecond: Double? = null
        val bytesPerSecond: Double get() = if (seconds > 0.0) bytes / seconds else 0.0
    }

    private companion object {
        val random = SecureRandom()
        const val COOKIE_CHARS = "abcdefghijklmnopqrstuvwxyz234567"
        const val COOKIE_SIZE = 37
        const val BLOCK_SIZE = 128 * 1024
        const val MAX_JSON_BYTES = 1_000_000
        const val CONTROL_GRACE_SECONDS = 10
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val CLIENT_VERSION = "3.12"

        const val TEST_START = 1
        const val TEST_RUNNING = 2
        const val TEST_END = 4
        const val PARAM_EXCHANGE = 9
        const val CREATE_STREAMS = 10
        const val SERVER_TERMINATE = 11
        const val EXCHANGE_RESULTS = 13
        const val DISPLAY_RESULTS = 14
        const val IPERF_DONE = 16
        const val ACCESS_DENIED = -1
        const val SERVER_ERROR = -2
    }
}
