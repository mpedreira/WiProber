package com.mpedreira.nimwifi

import com.google.gson.Gson
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 4: every phase is saved with its value; a cancel keeps what had finished. */
class PhaseResultsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ids = 0

    private fun viewModel() = MainViewModel({ 1_000L }, { "id-${ids++}" }, scope).apply {
        loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList())
        requestStopAndGoScan(10f, 20f)
        grantPendingScanAccess()
        completeStopAndGoScan(emptyList())
    }

    /** Accepts connections and never answers: a phase that would block until cancelled. */
    /**
     * Accepts and never answers. The accepted sockets are kept referenced: a discarded Socket is
     * closed when garbage-collected, which reset the upload mid-test under the full suite.
     */
    private fun silentServer(): ServerSocket = ServerSocket(0).also { server ->
        val accepted = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        thread(isDaemon = true) { while (!server.isClosed) runCatching { accepted += server.accept() } }
    }

    private fun awaitFinished(vm: MainViewModel, runId: String): ActiveRun {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            val run = vm.screenState.value.activeRuns.single { it.runId == runId }
            if (run.isFinished) return run
            check(System.nanoTime() < deadline) { "run did not finish: $run" }
            Thread.sleep(10)
        }
    }

    private fun reopened(run: ActiveRun): ActiveRun = Gson().fromJson(Gson().toJson(run), ActiveRun::class.java)

    @Test
    fun cancellingDuringTheUploadKeepsTheFinishedDownload() {
        MiniHttpServer { MiniHttpServer.Response(200, "x".repeat(200_000)) }.use { downloads ->
            silentServer().use { uploads ->
                val network = FakeMeasurementNetwork()
                val runner = ThroughputRunner(
                    iperf = Iperf3Client(), http = HttpSpeedTest(), network = network, beacons = emptyMap(),
                    device = DeviceInfo("m", "13"), networkTests = null, scriptRunner = null, clock = { 1_000L }
                )
                val settings = ThroughputSettings(
                    iperfEnabled = false, internetEnabled = true, internetDurationSeconds = 1,
                    internetDownloadUrl = "${downloads.base}/down?bytes={bytes}",
                    internetUploadUrl = "http://127.0.0.1:${uploads.localPort}/up"
                )
                val vm = viewModel()
                val point = vm.screenState.value.scanPoints.single()
                val uploading = CountDownLatch(1)
                val runId = vm.startActiveRun(point.pointId!!, point.scanId, ThroughputRunner.plan(settings, emptyList(), emptyList()), null) { control, listener ->
                    runCancellable(control, "test") {
                        runner.measure(settings, listener = object : RunListener by listener {
                            override fun onProgress(progress: ThroughputProgress) {
                                listener.onProgress(progress)
                                if (progress.phase == ThroughputPhase.UPLOAD) uploading.countDown()
                            }
                        })
                    }
                }!!
                assertTrue(uploading.await(15, TimeUnit.SECONDS))
                Thread.sleep(200)
                vm.cancelActiveRun()
                val run = reopened(awaitFinished(vm, runId))

                assertEquals(RunStatus.CANCELLED, run.status)
                val result = run.results!!.tests.single()
                assertNotNull("download kept", result.downMbps)
                assertEquals(RunStatus.COMPLETED, result.downloadStatus)
                assertNull(result.upMbps)
                assertEquals(RunStatus.CANCELLED, result.uploadStatus)
                assertEquals(ErrorCodes.CANCELLED, result.uploadErrorCode)
                assertEquals(RunStatus.COMPLETED, run.phases.single { it.key.endsWith(":download") }.status)
                assertEquals(RunStatus.CANCELLED, run.phases.single { it.key.endsWith(":upload") }.status)
                assertEquals(mapOf(RunStatus.COMPLETED to 2, RunStatus.CANCELLED to 1), run.phaseSummary)

                // The point sheet and the JSON keep the finished value and the reason.
                val line = PointDetails.resultLines(run).single()
                assertTrue(line, "↓ " in line && "cancelled" in line)
            }
        }
    }

    @Test
    fun cancellingTheSecondScriptStepKeepsTheFirst() {
        MiniHttpServer { MiniHttpServer.Response(200, "ok") }.use { ok ->
            silentServer().use { silent ->
                val network = FakeMeasurementNetwork()
                val http = TimedHttpClient(resolve = { host -> network.resolve(host).toTypedArray() }, readTimeoutMillis = 30_000)
                val runner = ThroughputRunner(
                    iperf = Iperf3Client(), http = HttpSpeedTest(), network = network, beacons = emptyMap(),
                    device = DeviceInfo("m", "13"), networkTests = null, scriptRunner = ScriptRunner(http), clock = { 1_000L }
                )
                val script = HttpScript("portal", steps = listOf(
                    HttpScriptStep("home", url = "${ok.base}/"),
                    HttpScriptStep("items", url = "http://127.0.0.1:${silent.localPort}/items")
                ))
                val settings = ThroughputSettings(iperfEnabled = false, internetEnabled = false)
                val vm = viewModel()
                val point = vm.screenState.value.scanPoints.single()
                val second = CountDownLatch(1)
                val runId = vm.startActiveRun(point.pointId!!, point.scanId, ThroughputRunner.plan(settings, emptyList(), listOf(script)), null) { control, listener ->
                    runCancellable(control, "test") {
                        runner.measure(settings, scripts = listOf(script), listener = object : RunListener by listener {
                            override fun onProgress(progress: ThroughputProgress) {
                                listener.onProgress(progress)
                                if (progress.detail == "items") second.countDown()
                            }
                        })
                    }
                }!!
                assertTrue(second.await(10, TimeUnit.SECONDS))
                Thread.sleep(200)
                vm.cancelActiveRun()
                val run = reopened(awaitFinished(vm, runId))
                val scriptRun = run.results!!.scripts!!.single()
                assertEquals(1, scriptRun.steps.size)
                assertTrue(scriptRun.steps.single().ok)
                assertEquals(2, scriptRun.stepsTotal)
                assertEquals(ErrorCodes.CANCELLED, scriptRun.errorCode)
                assertEquals(listOf(RunStatus.COMPLETED, RunStatus.CANCELLED), run.phases.map { it.status })
            }
        }
    }
}
