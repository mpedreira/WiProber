package com.mpedreira.nimwifi

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test

/** RC4 · 2: proxy configured, route observed, 407 vs 429, and "Probar servidor". */
class ProxyRouteTest {
    @Before @After
    fun clean() = RateLimits.clear()

    @Test
    fun aManualProxyAppliesExceptToExcludedHostsAndPacDependsOnTheUrl() {
        val manual = ProxyConfig(ProxyKind.MANUAL, "proxy.example.com", 3128, listOf("*.intranet.example.com", "erp.example.com"))
        assertEquals(ProxyKind.MANUAL, manual.forUrl(URL("https://speed.cloudflare.com/__up")))
        assertEquals(ProxyKind.NONE, manual.forUrl(URL("https://app.intranet.example.com/x")))
        assertEquals(ProxyKind.NONE, manual.forUrl(URL("https://erp.example.com/")))
        assertTrue(manual.mayProxy(URL("https://speed.cloudflare.com/")))
        // PAC: Android evaluates it per URL; only the request says what it did.
        val pac = ProxyConfig(ProxyKind.PAC, pacUrl = "http://wpad.example.com/wpad.dat")
        assertEquals(HttpRoute.UNDETERMINED, RouteObservation.observed(pac.forUrl(URL("https://a.example.com/")), usingProxy = true, httpStatus = 200).route)
        assertEquals(HttpRoute.DIRECT, RouteObservation.observed(pac.forUrl(URL("https://b.example.com/")), usingProxy = false, httpStatus = 200).route)
        assertEquals(HttpRoute.PROXY, RouteObservation.observed(ProxyKind.MANUAL, usingProxy = true, httpStatus = 200).route)
    }

    private val body = "x".repeat(32 * 1024)

    @Test
    fun theMeasuringConnectionTellsDirectFromExplicitProxyAndUndetermined() {
        MiniHttpServer { MiniHttpServer.Response(200, body) }.use { origin ->
            val direct = HttpSpeedTest(streams = 1, proxyFor = { ProxyKind.NONE })
            direct.download("${origin.base}/down", 1)
            assertEquals(RouteObservation(ProxyKind.NONE, HttpRoute.DIRECT, 200), direct.observation(HttpSpeedTest.DOWNLOAD))
        }
        // The local server acts as an explicit HTTP proxy: the client sends it the absolute URL.
        MiniHttpServer { r -> if (r.path.startsWith("http://")) MiniHttpServer.Response(200, body) else MiniHttpServer.Response(400, "") }.use { proxy ->
            val viaProxy = HttpSpeedTest(streams = 1, proxyFor = { ProxyKind.MANUAL },
                open = { url -> url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))) as HttpURLConnection })
            viaProxy.download("http://origin.example.com/down", 1)
            assertEquals(RouteObservation(ProxyKind.MANUAL, HttpRoute.PROXY, 200), viaProxy.observation(HttpSpeedTest.DOWNLOAD))
            assertTrue(proxy.requests.first().path.startsWith("http://origin.example.com/"))
        }
        // Nothing listening: it fails before the route is known.
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val failing = HttpSpeedTest(streams = 1, proxyFor = { ProxyKind.MANUAL })
        try { failing.download("http://127.0.0.1:$closed/down", 1); fail() } catch (_: Exception) {}
        assertEquals(HttpRoute.UNDETERMINED, failing.observation(HttpSpeedTest.DOWNLOAD)!!.route)
    }

    @Test
    fun aProxy407IsToldApartFromA429() {
        MiniHttpServer { MiniHttpServer.Response(407, "", listOf("Proxy-Authenticate" to "Basic realm=x")) }.use { proxy ->
            val viaProxy = HttpSpeedTest(streams = 1, proxyFor = { ProxyKind.MANUAL },
                open = { url -> url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))) as HttpURLConnection })
            try { viaProxy.download("http://origin.example.com/down", 1); fail() } catch (e: MeasurementException) {
                assertEquals(ErrorCodes.PROXY_AUTH_REQUIRED, e.code)
            }
        }
        assertEquals(ErrorCodes.PROXY_AUTH_REQUIRED, HttpOutcomePolicy.problem(407, null, false, null)!!.first)
        assertEquals(ErrorCodes.RATE_LIMITED, HttpOutcomePolicy.problem(429, null, false, null)!!.first)
    }

    @Test
    fun withAProxyAFailedDirectTcpCheckDoesNotSkipTheHttpTransfer() {
        MiniHttpServer { r -> if (r.path.startsWith("http://")) MiniHttpServer.Response(200, if (r.method == "GET") body else "ok") else MiniHttpServer.Response(400, "") }.use { proxy ->
            val open: (URL) -> HttpURLConnection = { url -> url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))) as HttpURLConnection }
            // The origin is not reachable directly (TEST-NET-2, nothing answers): only the proxy can carry it.
            val settings = ThroughputSettings(internetEnabled = true, internetServerId = InternetServers.CUSTOM, internetDurationSeconds = 1,
                customDownloadUrl = "http://198.51.100.7/down", customUploadUrl = "http://198.51.100.7/up")
            fun runner(network: FakeMeasurementNetwork) = ThroughputRunner(iperf = Iperf3Client(), http = HttpSpeedTest(streams = 1, open = open,
                proxyFor = { url -> network.proxy().forUrl(url) }), network = network, beacons = emptyMap(), device = DeviceInfo("m", "13"),
                networkTests = null, scriptRunner = null, clock = { 1_000L })
            val withProxy = FakeMeasurementNetwork().apply { proxyConfig = ProxyConfig(ProxyKind.MANUAL, "127.0.0.1", proxy.port) }
            val measured = runner(withProxy).measure(settings).tests.single()
            assertNull("the direct TCP check failed", measured.latencyMs)
            assertEquals(HttpRoute.DIRECT, measured.latencyRoute)
            assertTrue("download went through the proxy", measured.downMbps != null)
            assertEquals(listOf(ProxyKind.MANUAL, HttpRoute.PROXY, 200), listOf(measured.downloadProxyConfig, measured.downloadRoute, measured.downloadHttpStatus))
            assertEquals(HttpRoute.PROXY, measured.uploadRoute)
            // Without a proxy the AGENTS.md rule stays: an unreachable server skips its transfers.
            val noProxy = runner(FakeMeasurementNetwork()).measure(settings).tests.single()
            assertEquals(listOf(RunStatus.SKIPPED, ErrorCodes.SKIPPED_UNREACHABLE), listOf(noProxy.downloadStatus, noProxy.downloadErrorCode))
        }
    }

    @Test
    fun httpTestsAndScriptStepsAreDirectEvenWithAProxyConfigured() {
        MiniHttpServer { MiniHttpServer.Response(200, "ok") }.use { origin ->
            val network = FakeMeasurementNetwork().apply { proxyConfig = ProxyConfig(ProxyKind.MANUAL, "proxy.example.com", 3128) }
            val runner = NetworkTestRunner(TimedHttpClient(resolve = { h -> network.resolve(h).toTypedArray() }), network)
            val result = runner.run(NetworkTest("web", NetworkTest.HTTP, url = "${origin.base}/"))
            assertEquals(listOf(ProxyKind.MANUAL, HttpRoute.DIRECT), listOf(result.proxyConfig, result.route))
            val steps = ScriptRunner(TimedHttpClient(resolve = { h -> network.resolve(h).toTypedArray() }), proxyFor = { url: URL -> network.proxy().forUrl(url) })
                .run(HttpScript("portal", steps = listOf(HttpScriptStep("home", url = "${origin.base}/"))))
            assertEquals(listOf(ProxyKind.MANUAL, HttpRoute.DIRECT), listOf(steps.single().proxyConfig, steps.single().route))
        }
    }

    @Test
    fun probarServidorChecksEachUrlBrieflyAndCanBeCancelled() {
        MiniHttpServer { r -> if (r.path.startsWith("/down")) MiniHttpServer.Response(200, body) else MiniHttpServer.Response(429, "", listOf("Retry-After" to "45")) }.use { server ->
            val checks = ServerCheck.run("${server.base}/down?bytes={bytes}&token=secret", "${server.base}/up", { it.openConnection() as HttpURLConnection },
                { ProxyKind.NONE }, MeasurementControl())
            val (down, up) = checks
            assertTrue(down.ok)
            assertEquals(listOf(HttpRoute.DIRECT, 200), listOf(down.route, down.httpStatus))
            assertTrue("no query shown", "secret" !in down.target && "?" !in down.target)
            assertEquals(listOf(false, 429, ErrorCodes.RATE_LIMITED, 45_000L), listOf(up.ok, up.httpStatus, up.errorCode, up.retryAfterMillis))
            assertEquals(2, server.requests.size)
        }
        MiniHttpServer { MiniHttpServer.Response(200, "z".repeat(4 * 1024 * 1024), chunked = false) }.use { slow ->
            val control = MeasurementControl()
            thread(isDaemon = true) { Thread.sleep(50); control.cancel() }
            try {
                ServerCheck.run("${slow.base}/down", "", { url -> Thread.sleep(200); url.openConnection() as HttpURLConnection }, { ProxyKind.NONE }, control)
                fail("expected cancellation")
            } catch (_: MeasurementCancelled) {}
        }
        assertEquals(ErrorCodes.INVALID_TARGET, ServerCheck.run("not a url", "", { it.openConnection() as HttpURLConnection }, { ProxyKind.NONE }, MeasurementControl()).single().errorCode)
    }
}
