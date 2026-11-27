package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Optional minimum signal per band in the verdict. */
class BandRequirementTest {
    private fun point(id: String, state: String, bands: List<BandSignal>, planned: Boolean = false) =
        PointCoverage(id, id, 1f, 1f, state, strongestBssid = bands.maxByOrNull { it.rssi }?.bssid, strongestRssi = bands.maxOfOrNull { it.rssi }, bands = bands, planned = planned)

    private val req = Requirements(bands = listOf(BandRequirement(WifiBands.B5, -67.0)))

    private fun evaluate(vararg points: PointCoverage) = VerdictEvaluator.evaluate(req, "Ops", "Baja", points.toList(), emptyList(), emptyList(), -67)

    @Test
    fun fiveGigahertzIsRequiredOnTopOfTheProfileThreshold() {
        val v = evaluate(
            point("ok", PointState.OK, listOf(BandSignal("2.4", "aa:00:00:00:00:24", -50, 2437), BandSignal("5", "aa:00:00:00:00:50", -67, 5180))),
            point("weak5", PointState.OK, listOf(BandSignal("2.4", "aa:00:00:00:00:24", -50, 2437), BandSignal("5", "aa:00:00:00:00:50", -72, 5180))),
            point("only24", PointState.OK, listOf(BandSignal("2.4", "aa:00:00:00:00:24", -50, 2437))),
            point("planned", PointState.NOT_MEASURED, emptyList(), planned = true)
        )
        fun check(id: String) = v.points.single { it.pointId == id }.checks.single { it.requirement == "rssi:5" }
        assertEquals(VerdictState.PASS, check("ok").state) // limit inclusive
        assertEquals(listOf(VerdictState.FAIL, -72.0), listOf(check("weak5").state, check("weak5").value))
        assertEquals(VerdictState.FAIL, check("only24").state)
        assertTrue(check("only24").reason!!.contains("no oído en 5 GHz"))
        assertEquals(null, check("only24").value)
        assertEquals(VerdictState.MISSING, check("planned").state)
        assertEquals(listOf(1, 2, 1), listOf(v.passing, v.failing, v.missing))
        assertTrue(!req.isEmpty)
    }

    @Test
    fun bandRequirementsAreParsedAndValidated() {
        fun parse(section: String) = WiproberConfigParser.parse("""{"format":"wiprober-config","version":1,"requirements":{"bands":$section}}""", "c.json").requirements!!
        assertEquals(listOf(BandRequirement("5", -67.0), BandRequirement("2.4", -70.0)),
            parse("""[{"band":"5","minRssiDbm":-67},{"band":"2,4 GHz","minRssiDbm":-70}]""").bands)
        listOf("""[{"band":"3","minRssiDbm":-67}]""", """[{"band":"5","minRssiDbm":10}]""", """[{"band":"5"}]""",
            """[{"band":"5","minRssiDbm":-67},{"band":"5GHz","minRssiDbm":-70}]""").forEach { section ->
            try { parse(section); fail("accepted $section") } catch (_: ConfigException) {}
        }
        val parsed = parse("""[{"band":"6","minRssiDbm":-65}]""")
        val exported = ConfigExport.toJson(WiproberConfigStore.Imported(requirements = parsed), ThroughputSettings(), SurveyGuideSettings(), emptySet())
        assertEquals(parsed, WiproberConfigParser.parse(exported, "c.json").requirements)
    }
}
