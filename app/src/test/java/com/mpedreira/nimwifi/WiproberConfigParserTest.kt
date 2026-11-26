package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WiproberConfigParserTest {
    private val full = """
        {
          "format": "wiprober-config", "version": 1,
          "throughput": { "iperf": { "enabled": true, "label": "site", "host": "iperf.example.com", "durationSeconds": 4 },
                          "internet": { "enabled": false } },
          "guide": { "spacingMeters": 8, "coverageRadiusMeters": 4, "ssid": "Corp" },
          "roaming": { "enabled": true, "host": "${'$'}{gateway}", "port": 53, "intervalMs": 250 },
          "tests": [
            { "name": "dns-app", "type": "dns", "host": "app.example.com" },
            { "name": "gw", "type": "ping", "host": "${'$'}{gateway}", "count": 10 },
            { "name": "db", "type": "tcp", "host": "10.0.0.5", "port": 1521 },
            { "name": "web", "type": "http", "url": "https://app.example.com/health", "expectStatus": 200 }
          ],
          "scripts": [
            { "name": "lookup", "variables": { "user": "u" }, "steps": [
              { "name": "login", "method": "POST", "url": "https://app.example.com/login", "allowWrite": true,
                "body": "{\"u\":\"${'$'}{user}\"}", "extract": { "token": "${'$'}.data.token" } },
              { "name": "item", "url": "https://app.example.com/items/1",
                "headers": { "Authorization": "Bearer ${'$'}{token}" }, "expectContains": "A-01" }
            ] }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesEverySection() {
        val config = WiproberConfigParser.parse(full, "wiprober-config.json")

        assertEquals("site", config.throughput!!.iperfLabel)
        assertEquals("iperf.example.com", config.throughput!!.iperfHost)
        assertEquals(4, config.throughput!!.iperfDurationSeconds)
        assertEquals(8.0, config.guide!!.spacingMeters, 0.0)
        assertEquals("Corp", config.guide!!.ssidFilter)
        assertEquals(RoamingSettings(true, "\${gateway}", 53, 250), config.roaming)
        assertEquals(listOf("dns", "ping", "tcp", "http"), config.tests!!.map { it.type })
        assertEquals(10, config.tests!![1].count)
        val script = config.scripts!!.single()
        assertEquals(listOf("login", "item"), script.steps.map { it.name })
        assertEquals(mapOf("token" to "\$.data.token"), script.steps[0].extract)
        assertTrue(!config.mergeScripts)
    }

    @Test
    fun missingSectionsAreNullSoTheDeviceKeepsThem() {
        val config = WiproberConfigParser.parse("""{"format":"wiprober-config","version":1}""", "c.json")
        assertNull(config.throughput)
        assertNull(config.tests)
        assertNull(config.scripts)
        assertNull(config.roaming)
    }

    @Test
    fun rejectsWritesWithoutAllowWrite() {
        expectError("allowWrite") {
            """{"format":"wiprober-config","version":1,"scripts":[{"name":"s","steps":[{"method":"POST","url":"https://a.example/x"}]}]}"""
        }
    }

    @Test
    fun rejectsInvalidInput() {
        expectError("format") { """{"version":1}""" }
        expectError("version") { """{"format":"wiprober-config","version":2}""" }
        expectError("type") { """{"format":"wiprober-config","version":1,"tests":[{"name":"t","type":"ftp","host":"a"}]}""" }
        expectError("port") { """{"format":"wiprober-config","version":1,"tests":[{"name":"t","type":"tcp","host":"a"}]}""" }
        expectError("unique") {
            """{"format":"wiprober-config","version":1,"tests":[{"name":"t","type":"dns","host":"a"},{"name":"t","type":"dns","host":"b"}]}"""
        }
        expectError("http://") { """{"format":"wiprober-config","version":1,"tests":[{"name":"t","type":"http","url":"ftp://a/x"}]}""" }
        expectError("extract") {
            """{"format":"wiprober-config","version":1,"scripts":[{"name":"s","steps":[{"url":"https://a.example/x","extract":{"t":"data.token"}}]}]}"""
        }
        expectError("JSON") { "not json" }
    }

    @Test
    fun harKeepsOnlyTheAppsOwnReadsAndDropsBrowserHeaders() {
        val har = """
            {"log":{"entries":[
              {"_resourceType":"document","request":{"method":"GET","url":"https://app.example.com/home",
                "headers":[{"name":"Accept","value":"text/html"},{"name":"Cookie","value":"s=1"},{"name":":authority","value":"x"}]}},
              {"_resourceType":"image","request":{"method":"GET","url":"https://app.example.com/logo.png","headers":[]}},
              {"_resourceType":"xhr","request":{"method":"POST","url":"https://app.example.com/api/save","headers":[]}},
              {"_resourceType":"fetch","request":{"method":"GET","url":"https://app.example.com/api/items?page=1","headers":[]}}
            ]}}
        """.trimIndent()

        val config = WiproberConfigParser.parse(har, "picking flow.har")
        val script = config.scripts!!.single()

        assertTrue(config.mergeScripts)
        assertEquals("picking flow", script.name)
        assertEquals(listOf("https://app.example.com/home", "https://app.example.com/api/items?page=1"), script.steps.map { it.url })
        assertEquals(mapOf("Accept" to "text/html"), script.steps[0].headers)
    }

    @Test
    fun jsonPathReadsNestedValuesAndArrays() {
        val json = """{"data":{"token":"abc","items":[{"id":7},{"id":8}]}}"""
        assertEquals("abc", JsonPathLite.read(json, "\$.data.token"))
        assertEquals("8", JsonPathLite.read(json, "\$.data.items[1].id"))
        assertNull(JsonPathLite.read(json, "\$.data.missing"))
        assertNull(JsonPathLite.read(json, "\$.data.items[5].id"))
        assertTrue(!JsonPathLite.isValid("data.token"))
    }

    private fun expectError(fragment: String, text: () -> String) {
        try {
            WiproberConfigParser.parse(text(), "c.json")
            fail("expected ConfigException containing \"$fragment\"")
        } catch (e: ConfigException) {
            assertTrue("${e.message}", e.message.orEmpty().contains(fragment))
        }
    }
}
