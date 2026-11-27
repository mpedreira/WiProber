package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The verdict: cumple / no cumple / faltan datos, never null as 0, each SSID on its own. */
class VerdictEvaluatorTest {
    private val t0 = 1_700_000_000_000L
    private val weak = -67

    private fun point(id: String, state: String, rssi: Int? = -60, planned: Boolean = false, imported: Boolean = false) =
        PointCoverage(id, id.uppercase(), 1f, 1f, state, strongestBssid = "aa:00:00:00:00:01".takeIf { rssi != null }, strongestRssi = rssi,
            planned = planned, imported = imported)

    private fun iperf(ssid: String, down: Double?, up: Double? = 20.0, latency: Double? = 5.0, samples: Int? = 10, failures: Int? = 0,
                      change: String? = AssociationChange.SAME) =
        ThroughputTestResult("corporate", ThroughputTestResult.METHOD_IPERF3_TCP, 5, t0, targetId = TargetIds.IPERF, category = TargetCategory.PRIVATE_IPERF,
            ssid = ssid, downMbps = down, upMbps = up, latencyMs = latency, latencySamples = samples, latencyFailures = failures, associationChange = change)

    private fun run(id: String, point: String, at: Long, tests: List<ThroughputTestResult> = emptyList(), network: List<NetworkTestResult> = emptyList(),
                    status: String = RunStatus.COMPLETED, reason: String? = null) =
        ActiveRun(id, point, null, at, at + 10_000, status, reason, results = PointThroughput(at, tests, network))

    private val reqs = Requirements(
        throughput = listOf(ThroughputRequirement("corporate", minDownMbps = 50.0, minUpMbps = 10.0, maxLatencyMedianMs = 20.0, maxLatencyP95Ms = 30.0, maxLatencyLossPct = 10.0)),
        networkTests = listOf(ServiceRequirement("ERP", maxMedianMs = 100.0, maxLossPct = 5.0)),
        roaming = RoamingRequirement(maxOutageMs = 500, maxLossesPerFloor = 1)
    )

    private fun evaluate(coverage: List<PointCoverage>, runs: List<ActiveRun>, events: List<SurveyReport.FloorEvent> = emptyList(),
                         requirements: Requirements = reqs, ssid: String = "Ops") =
        VerdictEvaluator.evaluate(requirements, ssid, "Baja", coverage, runs, events, weak)

    private fun erp(ssid: String, ms: Double?, ok: Boolean = ms != null, loss: Double? = 0.0) =
        NetworkTestResult("erp", NetworkTest.PING, "erp.example.com", "icmp", t0, ok = ok, alias = "ERP", ssid = ssid, rttAvgMs = ms, lossPct = loss,
            errorCode = if (ok) null else ErrorCodes.TIMEOUT)

    @Test
    fun passFailAndMissingPerPoint() {
        val v = evaluate(
            listOf(point("a", PointState.OK), point("b", PointState.OK), point("c", PointState.NOT_MEASURED, rssi = null, planned = true)),
            listOf(run("r1", "a", t0, listOf(iperf("Ops", 80.0)), listOf(erp("Ops", 30.0))),
                run("r2", "b", t0 + 20_000, listOf(iperf("Ops", 40.0)), listOf(erp("Ops", 30.0))))
        )
        assertEquals(listOf(VerdictState.PASS, VerdictState.FAIL, VerdictState.MISSING), v.points.map { it.state })
        val failed = v.points[1].failed.single()
        assertEquals(listOf("iperf3:corporate:download", 40.0, 50.0, "r2"), listOf(failed.requirement, failed.value, failed.threshold, failed.runId))
        assertEquals("punto planificado sin medir", v.points[2].checks.first { it.requirement == "rssi" }.reason)
        assertEquals(1, v.passing); assertEquals(1, v.failing); assertEquals(1, v.missing)
        assertEquals(2, v.withData); assertEquals(50.0, v.percentPassing!!, 0.0)
        assertEquals(VerdictState.FAIL, v.state)
    }

    @Test
    fun nullIsNeverZeroNorANetworkFailure() {
        // Download not measured (server unreachable → null + error), the run was skipped later.
        val v = evaluate(listOf(point("a", PointState.OK)),
            listOf(run("r1", "a", t0, listOf(iperf("Ops", down = null, up = null, latency = null, samples = null, failures = null)), listOf(erp("Ops", null)))))
        val checks = v.points.single().checks.associateBy { it.requirement }
        listOf("iperf3:corporate:download", "iperf3:corporate:upload", "iperf3:corporate:latency", "iperf3:corporate:latency-loss", "service:ERP:time").forEach {
            assertEquals(it, VerdictState.MISSING, checks.getValue(it).state)
            assertNull(it, checks.getValue(it).value)
        }
        assertEquals(VerdictState.MISSING, v.points.single().state)
        // A skipped or cancelled run says why.
        val skipped = evaluate(listOf(point("a", PointState.OK)), listOf(run("r1", "a", t0, status = RunStatus.SKIPPED, reason = ErrorCodes.NETWORK_CHANGED)))
        assertTrue(skipped.points.single().checks.first { it.requirement == "iperf3:corporate:download" }.reason!!.contains("omitida"))
        val cancelled = evaluate(listOf(point("a", PointState.OK)), listOf(run("r1", "a", t0, status = RunStatus.CANCELLED)))
        assertEquals("ejecución cancelada", cancelled.points.single().checks.first { it.requirement == "service:ERP:time" }.reason)
    }

    @Test
    fun exactLimitsPass() {
        val atLimit = evaluate(listOf(point("a", PointState.OK, rssi = -67)),
            listOf(run("r1", "a", t0, listOf(iperf("Ops", 50.0, up = 10.0, latency = 20.0, samples = 10, failures = 1)), listOf(erp("Ops", 100.0, loss = 5.0)))))
        assertEquals(atLimit.points.single().checks.toString(), VerdictState.PASS, atLimit.points.single().state)
        val over = evaluate(listOf(point("a", PointState.OK)),
            listOf(run("r1", "a", t0, listOf(iperf("Ops", 49.9, up = 10.0, latency = 20.1)), listOf(erp("Ops", 100.1, loss = 5.1)))))
        assertEquals(setOf("iperf3:corporate:download", "iperf3:corporate:latency", "service:ERP:time", "service:ERP:loss"),
            over.points.single().failed.map { it.requirement }.toSet())
        // RSSI uses the profile threshold: a weak point and a point where the SSID is missing fail.
        assertEquals(VerdictState.FAIL, evaluate(listOf(point("w", PointState.WEAK, rssi = -70)), emptyList()).points.single().checks.first().state)
        assertEquals(VerdictState.FAIL, evaluate(listOf(point("n", PointState.SSID_NOT_DETECTED, rssi = null)), emptyList()).points.single().checks.first().state)
        // A ping with 100 % loss is a measured loss.
        val lost = evaluate(listOf(point("a", PointState.OK)), listOf(run("r1", "a", t0, listOf(iperf("Ops", 80.0)), listOf(erp("Ops", null, ok = false, loss = 100.0)))))
        assertEquals(VerdictState.FAIL, lost.points.single().checks.first { it.requirement == "service:ERP:loss" }.state)
    }

    @Test
    fun eachSsidIsJudgedOnItsOwn() {
        // Great results on the guest network never satisfy the operational one.
        val v = evaluate(listOf(point("a", PointState.OK)), listOf(run("r1", "a", t0, listOf(iperf("Invitados", 500.0)), listOf(erp("Invitados", 1.0)))))
        val download = v.points.single().checks.first { it.requirement == "iperf3:corporate:download" }
        assertEquals(VerdictState.MISSING, download.state)
        assertEquals(VerdictState.PASS, evaluate(listOf(point("a", PointState.OK)),
            listOf(run("r1", "a", t0, listOf(iperf("Invitados", 500.0)), listOf(erp("Invitados", 1.0)))), ssid = "Invitados").points.single().state)
    }

    private fun outage(key: String, at: Long, upper: Long?, lower: Long? = upper, open: Boolean = false, ssid: String = "Ops") =
        SurveyReport.FloorEvent(SurveyReport.Event(RoamingEvent.REACHABILITY_LOSS, at, "aa:00:00:00:00:01", "aa:00:00:00:00:02", "corte",
            fromSsid = ssid, toSsid = ssid, source = "roaming", key = key, outageLowerMs = lower, outageUpperMs = upper, open = open), true, setOf(ssid), false)

    @Test
    fun aGoodMedianDoesNotHideACutDuringTheMeasurement() {
        val good = listOf(run("r1", "a", t0, listOf(iperf("Ops", 300.0)), listOf(erp("Ops", 10.0))))
        assertEquals(VerdictState.PASS, evaluate(listOf(point("a", PointState.OK)), good).points.single().state)
        val cut = evaluate(listOf(point("a", PointState.OK)), good, listOf(outage("roaming:s:0:0", t0 + 4_000, 300)))
        val check = cut.points.single().failed.single()
        assertEquals("outage-during-measurement", check.requirement)
        assertEquals("r1", check.runId)
        assertEquals(VerdictState.FAIL, cut.points.single().state)
        // Outside the measurement window it is judged on the floor, not at the point.
        val outside = evaluate(listOf(point("a", PointState.OK)), good, listOf(outage("roaming:s:0:0", t0 + 60_000, 300)))
        assertEquals(VerdictState.PASS, outside.points.single().state)
        // An AP change without loss is explained, not failed.
        val roamed = evaluate(listOf(point("a", PointState.OK)), listOf(run("r1", "a", t0, listOf(iperf("Ops", 300.0, change = AssociationChange.BSSID_CHANGED)), listOf(erp("Ops", 10.0)))))
        assertEquals(VerdictState.PASS, roamed.points.single().state)
        assertTrue(roamed.points.single().checks.any { it.requirement == "ap-change-during-measurement" })
    }

    @Test
    fun floorChecksP95AndRoaming() {
        assertEquals(30.0, VerdictEvaluator.percentile(listOf(5.0, 10.0, 30.0), 95.0), 0.0)
        assertEquals(19.0, VerdictEvaluator.percentile((1..20).map { it.toDouble() }, 95.0), 0.0)
        val runs = (1..3).map { i -> run("r$i", "p$i", t0 + i * 100_000L, listOf(iperf("Ops", 80.0, latency = listOf(5.0, 10.0, 31.0)[i - 1])), listOf(erp("Ops", 10.0))) }
        val points = (1..3).map { point("p$it", PointState.OK) }
        val p95 = evaluate(points, runs).floorChecks.single { it.requirement == "iperf3:corporate:latency-p95" }
        assertEquals(listOf(VerdictState.FAIL, 31.0, 30.0), listOf(p95.state, p95.value, p95.threshold))

        val roamingOnly = Requirements(roaming = reqs.roaming)
        val ok = listOf(outage("roaming:s:0:0", 1, upper = 500))
        assertEquals(VerdictState.PASS, evaluate(emptyList(), emptyList(), ok, roamingOnly).state)
        val v = evaluate(emptyList(), emptyList(), requirements = roamingOnly, events = listOf(outage("roaming:s:0:0", 1, upper = 501), outage("roaming:s:0:1", 2, upper = null, lower = null),
            outage("roaming:s:0:2", 3, upper = null, lower = 200, open = true)))
        assertEquals(listOf(VerdictState.FAIL, VerdictState.MISSING, VerdictState.FAIL), v.floorChecks.filter { it.requirement == "roaming:outage" }.map { it.state })
        val losses = v.floorChecks.single { it.requirement == "roaming:losses" }
        assertEquals(listOf(VerdictState.FAIL, 3.0, 1.0), listOf(losses.state, losses.value, losses.threshold))
        // Another SSID's outages never count here.
        assertEquals(VerdictState.PASS, evaluate(emptyList(), emptyList(), ok + outage("roaming:s:0:9", 9, 9_000, ssid = "Invitados"), roamingOnly).state)
    }

    @Test
    fun nothingToJudgeIsMissingData() {
        val v = evaluate(emptyList(), emptyList(), requirements = Requirements(throughput = reqs.throughput))
        assertEquals(VerdictState.MISSING, v.state)
        assertNull(v.percentPassing)
        assertTrue(Requirements().isEmpty)
    }

    @Test
    fun requirementsAreParsedValidatedAndExportedBack() {
        val json = """{"format":"wiprober-config","version":1,"requirements":{
            "_comment":"thresholds of the verdict",
            "throughput":[{"alias":"corporate","minDownMbps":50,"minUpMbps":10,"maxLatencyMedianMs":20,"maxLatencyP95Ms":30,"maxLatencyLossPct":10}],
            "networkTests":[{"alias":"ERP","maxMedianMs":100,"maxLossPct":5}],
            "roaming":{"maxOutageMs":500,"maxLossesPerFloor":1}}}"""
        val parsed = WiproberConfigParser.parse(json, "c.json").requirements!!
        assertEquals(reqs, parsed)
        assertNull(WiproberConfigParser.parse("""{"format":"wiprober-config","version":1}""", "c.json").requirements)
        val exported = ConfigExport.toJson(WiproberConfigStore.Imported(requirements = parsed), ThroughputSettings(), SurveyGuideSettings(), emptySet())
        assertEquals(parsed, WiproberConfigParser.parse(exported, "c.json").requirements)
        assertEquals(3, ImportPreview.of(WiproberConfigParser.parse(json, "c.json"), emptySet()).requirements)
        listOf(
            """{"throughput":[{"alias":"x","minDownMbps":-1}]}""",
            """{"throughput":[{"minDownMbps":1}]}""",
            """{"networkTests":[{"alias":"x","maxLossPct":120}]}""",
            """{"networkTests":[{"alias":"ERP"},{"alias":"erp"}]}""",
            """{"roaming":{"maxLossesPerFloor":1.5}}""",
            """{"throughput":[{"alias":"x","minDownMbps":"fast"}]}"""
        ).forEach { section ->
            try {
                WiproberConfigParser.parse("""{"format":"wiprober-config","version":1,"requirements":$section}""", "c.json")
                fail("accepted $section")
            } catch (_: ConfigException) {}
        }
    }
}
