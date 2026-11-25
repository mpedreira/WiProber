package com.example.wiprober

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanPointPersistenceTest {
    @Test
    fun projectsSavedBeforeThroughputTestsStillLoad() {
        val legacy = """{"timestamp":1,"x":2.0,"y":3.0,"wifiNetworks":[],"importedFromEsx":false}"""

        val point = Gson().fromJson(legacy, ScanPoint::class.java)

        assertEquals(2f, point.x)
        assertNull(point.throughput)
    }

    @Test
    fun throughputSurvivesASaveAndLoad() {
        val point = ScanPoint(
            1L, 2f, 3f, emptyList(),
            throughput = PointThroughput(
                5L,
                listOf(ThroughputTestResult("internet", "http", 5, 5L, downMbps = 10.5, error = "upload: x"))
            )
        )

        assertEquals(point, Gson().fromJson(Gson().toJson(point), ScanPoint::class.java))
    }
}
