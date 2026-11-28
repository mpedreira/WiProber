package com.mpedreira.nimwifi

import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** RC4 · 1: HTTP 429 is "the server is limiting requests", not a network failure nor a rate. */
class RateLimitTest {
    private val now = 1_700_000_000_000L

    @Before
    fun clean() = RateLimits.clear()

    @org.junit.After
    fun cleanAfter() = RateLimits.clear()

    private val body = "x".repeat(64 * 1024)

    @Test
    fun retryAfterInSecondsOrAsAnHttpDate() {
        assertEquals(120_000L, RetryAfter.parse("120", now))
        // Tue, 14 Nov 2023 22:15:20 GMT is 2 minutes after `now` (22:13:20).
        assertEquals(120_000L, RetryAfter.parse("Tue, 14 Nov 2023 22:15:20 GMT", now))
        assertEquals(0L, RetryAfter.parse("Tue, 14 Nov 2023 22:00:00 GMT", now))
        assertNull(RetryAfter.parse("soon", now))
        assertNull(RetryAfter.parse(null, now))
    }

    @Test
    fun aGoodServerMeasuresAndAFullyLimitedOneFailsWithNoValue() {
        MiniHttpServer { MiniHttpServer.Response(200, body) }.use { ok ->
            val result = HttpSpeedTest(streams = 2).download("${ok.base}/down", 1)
            assertTrue(result.bytes > 0)
        }
        MiniHttpServer { MiniHttpServer.Response(429, "", listOf("Retry-After" to "30")) }.use { limited ->
            try {
                HttpSpeedTest(streams = 2).download("${limited.base}/down", 1)
                fail("expected 429")
            } catch (e: RateLimitedException) {
                assertEquals(ErrorCodes.RATE_LIMITED, e.code)
                assertEquals(30_000L, e.retryAfterMillis)
                assertEquals(0L, e.bytes)
                assertNull("zero bytes: no partial rate", e.partialBitsPerSecond)
            }
            // A 429 stops new requests: the two connections asked once each.
            assertTrue(limited.requests.size <= 2)
        }
    }

    @Test
    fun aMixed429KeepsThePartialBytesForDiagnosisOnly() {
        val served = AtomicInteger()
        // The first requests transfer data; then the server starts limiting.
        MiniHttpServer { if (served.incrementAndGet() <= 2) MiniHttpServer.Response(200, body) else MiniHttpServer.Response(429, "") }.use { server ->
            try {
                HttpSpeedTest(streams = 2).download("${server.base}/down", 2)
                fail("expected 429")
            } catch (e: RateLimitedException) {
                assertTrue("bytes moved before the 429 are kept", e.bytes > 0)
                assertTrue(e.partialBitsPerSecond!! > 0)
                assertNull(e.retryAfterMillis)
            }
        }
        // The same on upload.
        val posts = AtomicInteger()
        MiniHttpServer { if (posts.incrementAndGet() <= 1) MiniHttpServer.Response(200, "ok") else MiniHttpServer.Response(429, "") }.use { server ->
            try { HttpSpeedTest(streams = 1).upload("${server.base}/up", 2); fail() } catch (e: RateLimitedException) { assertTrue(e.bytes > 0) }
        }
    }

    @Test
    fun aProxyAsking407IsNotA429() {
        MiniHttpServer { MiniHttpServer.Response(407, "") }.use { proxy ->
            try { HttpSpeedTest(streams = 1).download("${proxy.base}/down", 1); fail() } catch (e: MeasurementException) {
                assertEquals(ErrorCodes.PROXY_AUTH_REQUIRED, e.code)
            }
        }
    }

    @Test
    fun cancellingStopsALimitedOrSlowTransfer() {
        MiniHttpServer { MiniHttpServer.Response(200, "y".repeat(8 * 1024 * 1024)) }.use { server ->
            val control = MeasurementControl()
            thread(isDaemon = true) { Thread.sleep(200); control.cancel() }
            val started = System.nanoTime()
            try { HttpSpeedTest(streams = 2, control = control).download("${server.base}/down", 20); fail() } catch (_: MeasurementCancelled) {}
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        }
    }

    @Test
    fun theRunnerExcludesTheValueSkipsTheSameHostUntilRetryAfterAndGoesOn() {
        MiniHttpServer { r -> if (r.path.startsWith("/down")) MiniHttpServer.Response(429, "", listOf("Retry-After" to "90")) else MiniHttpServer.Response(200, "ok") }.use { server ->
            val network = FakeMeasurementNetwork()
            val runner = ThroughputRunner(iperf = Iperf3Client(), http = HttpSpeedTest(streams = 1), network = network, beacons = emptyMap(),
                device = DeviceInfo("m", "13"), networkTests = NetworkTestRunner(TimedHttpClient(resolve = { h -> network.resolve(h).toTypedArray() }), network),
                scriptRunner = null, clock = { now })
            val settings = ThroughputSettings(internetEnabled = true, internetServerId = InternetServers.CUSTOM, internetDurationSeconds = 1,
                customDownloadUrl = "${server.base}/down", customUploadUrl = "${server.base}/up")
            val tcp = NetworkTest("svc", NetworkTest.TCP, host = "127.0.0.1", port = server.port)
            val result = runner.measure(settings, listOf(tcp))
            val internet = result.tests.single()
            assertNull("never a rate", internet.downMbps)
            assertEquals(listOf(RunStatus.FAILED, ErrorCodes.RATE_LIMITED), listOf(internet.downloadStatus, internet.downloadErrorCode))
            assertEquals(now + 90_000, internet.downloadRetryAt)
            // Same host: the upload is not sent before the server allows it.
            assertEquals(listOf(RunStatus.SKIPPED, ErrorCodes.RATE_LIMITED, now + 90_000), listOf(internet.uploadStatus, internet.uploadErrorCode, internet.uploadRetryAt))
            assertEquals(InternetServers.CUSTOM, internet.internetServerId)
            // Other destinations go on.
            assertTrue(result.networkTests!!.single().ok)
            // A later run within the window does not ask that server at all.
            val before = server.requests.count { it.path.startsWith("/down") }
            runner.measure(settings)
            assertEquals(before, server.requests.count { it.path.startsWith("/down") })
        }
    }
}
