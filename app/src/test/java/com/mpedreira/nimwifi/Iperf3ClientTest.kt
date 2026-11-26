package com.mpedreira.nimwifi

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class Iperf3ClientTest {
    private var server: Process? = null

    @After
    fun stopServer() {
        server?.destroy()
        server?.waitFor(5, TimeUnit.SECONDS)
    }

    /** Runs only when IPERF3_BIN points at a real iperf3 binary (protocol compatibility check). */
    @Test
    fun measuresBothDirectionsAgainstRealIperf3Server() {
        val binary = System.getenv("IPERF3_BIN")?.let(::File)
        assumeTrue("IPERF3_BIN not set", binary?.canExecute() == true)
        val port = ServerSocket(0).use { it.localPort }
        server = ProcessBuilder(binary!!.path, "-s", "-1", "-p", port.toString())
            .redirectErrorStream(true)
            .start()
        val client = Iperf3Client()

        val download = retryUntilListening { client.run(java.net.InetAddress.getByName("127.0.0.1"), port, 1, Iperf3Client.Direction.DOWNLOAD).bitsPerSecond }
        assertTrue("download $download bit/s", download > 1_000_000.0)

        // "-1" makes the server exit after one test: start another one for the upload.
        server!!.waitFor(5, TimeUnit.SECONDS)
        server = ProcessBuilder(binary.path, "-s", "-1", "-p", port.toString()).redirectErrorStream(true).start()
        val upload = retryUntilListening { client.run(java.net.InetAddress.getByName("127.0.0.1"), port, 1, Iperf3Client.Direction.UPLOAD).bitsPerSecond }
        assertTrue("upload $upload bit/s", upload > 1_000_000.0)
    }

    @Test
    fun reportsBusyServer() {
        ServerSocket(0).use { socket ->
            thread(isDaemon = true) {
                socket.accept().use { connection ->
                    connection.getInputStream().readNBytes(37)
                    connection.getOutputStream().apply { write(-1); flush() } // ACCESS_DENIED
                    Thread.sleep(200)
                }
            }
            try {
                Iperf3Client().run(java.net.InetAddress.getByName("127.0.0.1"), socket.localPort, 1, Iperf3Client.Direction.DOWNLOAD)
                fail("expected IOException")
            } catch (expected: IOException) {
                assertEquals("iperf3 server is busy", expected.message)
                assertEquals(ErrorCodes.IPERF_BUSY, (expected as MeasurementException).code)
            }
        }
    }

    @Test(expected = IOException::class)
    fun failsWhenNothingListens() {
        val port = ServerSocket(0).use { it.localPort }
        Iperf3Client(connectTimeoutMillis = 1_000).run(java.net.InetAddress.getByName("127.0.0.1"), port, 1, Iperf3Client.Direction.UPLOAD)
    }

    private fun retryUntilListening(block: () -> Double): Double {
        repeat(20) { attempt ->
            try {
                return block()
            } catch (e: java.net.ConnectException) {
                if (attempt == 19) throw e
                Thread.sleep(100)
            }
        }
        error("unreachable")
    }
}
