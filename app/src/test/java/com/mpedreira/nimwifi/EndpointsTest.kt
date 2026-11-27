package com.mpedreira.nimwifi

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 14: each HTTP endpoint is pre-checked and measured on its own; redirects follow a policy. */
class EndpointsTest {
    private fun runner(network: MeasurementNetwork) = ThroughputRunner(
        iperf = Iperf3Client(), http = HttpSpeedTest(), network = network, beacons = emptyMap(),
        device = DeviceInfo("m", "13"), networkTests = NetworkTestRunner(TimedHttpClient(resolve = { network.resolve(it).toTypedArray() }), network),
        scriptRunner = null, clock = { 1_000L }
    )

    @Test
    fun anUploadOnAnotherPortOfTheSameHostIsMeasuredWhenTheDownloadFails() {
        val closedPort = ServerSocket(0).use { it.localPort }
        MiniHttpServer { MiniHttpServer.Response(200, "ok") }.use { upload ->
            // The name resolves to two IPs; the first one never answers, the pre-check moves on.
            val network = FakeMeasurementNetwork(hosts = mapOf("localhost" to listOf(InetAddress.getByName("192.0.2.1"), FakeMeasurementNetwork.LOOPBACK)))
            val settings = ThroughputSettings(
                iperfEnabled = false, internetEnabled = true, internetDurationSeconds = 1,
                internetDownloadUrl = "http://localhost:$closedPort/down?bytes={bytes}",
                internetUploadUrl = "http://localhost:${upload.port}/up"
            )
            val result = runner(network).measure(settings).tests.single()
            assertEquals("http://localhost:$closedPort", result.downloadEndpointId)
            assertEquals("http://localhost:${upload.port}", result.uploadEndpointId)
            assertEquals(RunStatus.SKIPPED, result.downloadStatus)
            assertEquals(ErrorCodes.SKIPPED_UNREACHABLE, result.downloadErrorCode)
            assertNull(result.downloadPrecheckAddress)
            // The upload endpoint had its own pre-check, with the IP that answered.
            assertEquals("127.0.0.1", result.uploadPrecheckAddress)
            assertEquals("upload: ${result.uploadErrorCode} ${result.error}", RunStatus.COMPLETED, result.uploadStatus)
            assertNotNull(result.upMbps)
        }
    }

    @Test
    fun aRedirectToALoginPageIsVisibleAndOnlySucceedsWhenExpected() {
        MiniHttpServer { MiniHttpServer.Response(302, "", listOf("Location" to "/login?next=%2Fhealth&token=FAKE-123")) }.use { server ->
            val network = FakeMeasurementNetwork()
            val tests = NetworkTestRunner(TimedHttpClient(resolve = { network.resolve(it).toTypedArray() }), network)
            val plain = tests.run(NetworkTest("portal", NetworkTest.HTTP, url = "${server.base}/health"))
            assertEquals(false, plain.ok)
            assertEquals(ErrorCodes.REDIRECT, plain.errorCode)
            assertEquals("127.0.0.1/login", plain.redirectLocation)
            assertTrue("FAKE" !in plain.toString())
            val expected = tests.run(NetworkTest("portal", NetworkTest.HTTP, url = "${server.base}/health", expectStatus = 302))
            assertTrue(expected.ok)
            val allowed = tests.run(NetworkTest("portal", NetworkTest.HTTP, url = "${server.base}/health", allowRedirect = true))
            assertTrue(allowed.ok)
            assertEquals("127.0.0.1/login", allowed.redirectLocation)

            // Same policy for script steps, and the point sheet shows where it went.
            val step = ScriptRunner(TimedHttpClient()).run(HttpScript("s", steps = listOf(HttpScriptStep("a", url = "${server.base}/health")))).single()
            assertEquals(ErrorCodes.REDIRECT, step.errorCode)
            assertEquals("127.0.0.1/login", step.redirectLocation)
            val run = ActiveRun("r", "p", null, 1L, status = RunStatus.COMPLETED, results = PointThroughput(1L, emptyList(), listOf(plain)))
            assertTrue(PointDetails.resultLines(run).single().endsWith("→ 127.0.0.1/login"))
        }
    }
}
