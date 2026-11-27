package com.mpedreira.nimwifi

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fictitious credentials must never reach definitions, preview, results or export. */
class ConfigSecurityTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val bearer = "Bearer FAKE-bearer-0123456789"
    private val queryToken = "FAKEqueryToken0123456789"
    private val password = "FAKE-password-42"
    private val cookie = "FAKEcookie0123456789"
    private val allSecrets = listOf(bearer, "FAKE-bearer-0123456789", queryToken, password, cookie, "hunter-pass")

    private fun assertNoSecret(text: String) = allSecrets.forEach { secret ->
        assertFalse("secret leaked: $text", secret in text)
    }

    private val har = """
        {"log": {"entries": [
          {"_resourceType": "document", "request": {"method": "GET",
            "url": "https://user:hunter-pass@app.example.com/home?lang=en&access_token=$queryToken#top",
            "headers": [{"name": "Authorization", "value": "$bearer"},
                        {"name": "Cookie", "value": "sid=$cookie"},
                        {"name": "X-CSRF-Token", "value": "$cookie"},
                        {"name": "Accept", "value": "text/html"}],
            "cookies": [{"name": "sid", "value": "$cookie"}]}},
          {"_resourceType": "xhr", "request": {"method": "POST", "url": "https://app.example.com/api/save",
            "headers": [], "postData": {"text": "password=$password"}}}
        ]}}
    """.trimIndent()

    @Test
    fun harCredentialsAreStrippedByDefaultAndShownOnlyByName() {
        val config = WiproberConfigParser.parse(har, "login.har")
        val script = config.scripts!!.single()
        assertNoSecret(Gson().toJson(config))
        assertNoSecret(config.toString())

        val step = script.steps.single() // the POST is never imported
        assertEquals("https://app.example.com/home?lang=en&access_token=\${secret.har_access_token}", step.url)
        assertEquals("\${secret.har_Authorization}", step.headers["Authorization"])
        assertEquals("text/html", step.headers["Accept"])
        assertFalse(step.headers.keys.any { it.equals("Cookie", true) || it.equals("X-CSRF-Token", true) })
        assertTrue(config.stripped.any { "Authorization" in it })
        assertTrue(config.stripped.any { "access_token" in it })
        assertTrue(config.stripped.any { "URL credentials" in it })

        val preview = WiproberConfigStore(temp.newFolder()).preview(config)
        assertNoSecret(preview.toString())
        assertEquals(listOf("har_Authorization", "har_access_token"), preview.credentialsMissing)
        assertEquals("app.example.com", preview.destinations.single().host)
        assertEquals(setOf("GET"), preview.destinations.single().methods)
        assertFalse(preview.requiresWriteConfirmation)
    }

    @Test
    fun literalCredentialsInAConfigAreStoredApartFromTheDefinition() {
        val text = """
            {"format": "wiprober-config", "version": 1,
             "credentials": {"api_key": "FAKE-api-key-0001"},
             "scripts": [{"name": "Portal", "alias": "Employee portal", "variables": {"password": "$password", "user": "demo"},
               "steps": [
                 {"name": "login", "method": "POST", "allowWrite": true, "url": "https://portal.example.com/login",
                  "headers": {"Authorization": "$bearer", "X-Api-Key": "${'$'}{secret.api_key}"},
                  "body": "user=${'$'}{user}&pass=${'$'}{password}"}]}]}
        """.trimIndent()
        val config = WiproberConfigParser.parse(text, "c.json")
        val step = config.scripts!!.single().steps.single()
        assertNoSecret(Gson().toJson(config.scripts))
        assertEquals("user=\${user}&pass=\${secret.password}", step.body)
        assertEquals("\${secret.Portal_Authorization}", step.headers["Authorization"])
        assertEquals(mapOf("user" to "demo"), config.scripts!!.single().variables)
        assertEquals(password, config.credentials!!["password"])
        assertEquals(bearer, config.credentials!!["Portal_Authorization"])

        val store = WiproberConfigStore(temp.newFolder("store"))
        val preview = store.preview(config)
        assertTrue(preview.requiresWriteConfirmation)
        assertEquals(listOf("Employee portal · login"), preview.writeSteps)
        assertTrue(preview.credentialsMissing.isEmpty())
        assertNoSecret(preview.toString())

        store.applyImported(config)
        // The active revision's definitions (mejora 16 keeps credentials in their own file).
        val definitions = File(temp.root, "store").walk().first { it.name == "imported.json" }.readText()
        assertNoSecret(definitions)
        assertEquals(bearer, store.loadCredentials()["Portal_Authorization"])
        store.clear()
        assertTrue(store.loadCredentials().isEmpty())
    }

    @Test
    fun aFailedImportKeepsThePreviousProfile() {
        val store = WiproberConfigStore(temp.newFolder())
        store.applyImported(
            WiproberConfigParser.parse(
                """{"format": "wiprober-config", "version": 1, "tests": [{"name": "gw", "type": "tcp", "host": "gw.example.com", "port": 443}]}""",
                "a.json"
            )
        )
        val before = store.load()
        listOf(
            """{"format": "wiprober-config", "version": 1, "tests": [{"name": "bad", "type": "tcp", "host": "x"}]}""",
            """{"format": "wiprober-config", "version": 1, "scripts": [{"name": "s", "steps": [{"url": "https://u:p@x.example.com/"}]}]}""",
            """{"format": "wiprober-config", "version": 1, "credentials": {"bad name": "$password"}}""",
            "not json"
        ).forEach { text ->
            try {
                store.applyImported(WiproberConfigParser.parse(text, "b.json"))
                throw AssertionError("expected ConfigException")
            } catch (expected: ConfigException) {
                assertNoSecret(expected.message.orEmpty())
            }
        }
        assertEquals(before, store.load())
    }

    @Test
    fun secretsReachTheServerButNeverTheResults() {
        MiniHttpServer { request ->
            when (request.path.substringBefore('?')) {
                "/login" -> MiniHttpServer.Response(200, """{"token": "FAKE-session-token-777"}""", listOf("Set-Cookie" to "sid=$cookie; Path=/"))
                else -> MiniHttpServer.Response(404)
            }
        }.use { server ->
            val script = HttpScript(
                name = "s",
                steps = listOf(
                    HttpScriptStep("login", url = "${server.base}/login?key=\${secret.k}", headers = mapOf("Authorization" to "\${secret.auth}"),
                        extract = mapOf("token" to "$.token")),
                    HttpScriptStep("next", url = "${server.base}/items/\${token}", expectContains = "never"),
                    HttpScriptStep("missing", url = "${server.base}/x", headers = mapOf("X-Api-Key" to "\${secret.nope}"))
                )
            )
            val results = ScriptRunner(TimedHttpClient(), mapOf("k" to queryToken, "auth" to bearer)).run(script)

            val login = server.requests.first()
            assertEquals(bearer, login.headers.entries.first { it.key.equals("Authorization", true) }.value)
            assertTrue(queryToken in login.path)
            assertTrue(server.requests[1].headers.entries.any { it.key.equals("Cookie", true) && cookie in it.value })

            assertEquals("/login", results[0].path)
            assertEquals("/items/x", results[1].path) // from the definition, never the extracted value
            assertEquals(ErrorCodes.HTTP_STATUS, results[1].errorCode)
            assertEquals(ErrorCodes.MISSING_CREDENTIAL, results[2].errorCode)
            assertEquals(0, server.requests.count { it.path == "/x" })
            val json = Gson().toJson(results)
            assertNoSecret(json)
            assertFalse("FAKE-session-token-777" in json)
        }
    }

    @Test
    fun resolvedValuesCannotInjectHeadersOrChangeTheTarget() {
        MiniHttpServer { MiniHttpServer.Response(200, """{"evil": "x\r\nX-Injected: 1", "host": "user:pw@other.example.com"}""") }.use { server ->
            val script = HttpScript(
                name = "s",
                steps = listOf(
                    HttpScriptStep("a", url = "${server.base}/a", extract = mapOf("evil" to "$.evil", "host" to "$.host")),
                    HttpScriptStep("b", url = "${server.base}/b", headers = mapOf("X-Value" to "\${evil}")),
                    HttpScriptStep("c", url = "https://\${host}/c")
                )
            )
            val results = ScriptRunner(TimedHttpClient()).run(script)
            assertEquals(ErrorCodes.INVALID_TARGET, results[1].errorCode)
            assertEquals(ErrorCodes.INVALID_TARGET, results[2].errorCode)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun cleartextFollowsTheExplicitPolicy() {
        MiniHttpServer { MiniHttpServer.Response(200, "ok") }.use { server ->
            val step = HttpScriptStep("a", url = "${server.base}/a")
            val blocked = ScriptRunner(TimedHttpClient(cleartextPermitted = { false })).run(HttpScript("s", steps = listOf(step))).single()
            assertEquals(ErrorCodes.CLEARTEXT_NOT_PERMITTED, blocked.errorCode)
            assertNull(blocked.connectMs)
            assertEquals(0, server.requests.size)

            val allowed = ScriptRunner(TimedHttpClient(cleartextPermitted = { it == "127.0.0.1" })).run(HttpScript("s", steps = listOf(step))).single()
            assertTrue(allowed.ok)
        }
    }

    @Test
    fun responsesAndCookiesAreBounded() {
        MiniHttpServer { MiniHttpServer.Response(200, "x".repeat(10_000), (1..40).map { "Set-Cookie" to "c$it=v" }) }.use { server ->
            val big = ScriptRunner(TimedHttpClient(maxBodyBytes = 1_000)).run(HttpScript("s", steps = listOf(HttpScriptStep("a", url = "${server.base}/a")))).single()
            assertEquals(ErrorCodes.RESPONSE_TOO_LARGE, big.errorCode)

            val steps = listOf(HttpScriptStep("a", url = "${server.base}/a"), HttpScriptStep("b", url = "${server.base}/b"))
            ScriptRunner(TimedHttpClient()).run(HttpScript("s", steps = steps))
            val sent = server.requests.last().headers.entries.first { it.key.equals("Cookie", true) }.value
            assertEquals(30, sent.split("; ").size)
        }
    }

    @Test
    fun aScriptWithoutWifiIsExportedAsSkipped() {
        val runner = ThroughputRunner(
            iperf = Iperf3Client(), http = HttpSpeedTest(), network = null, beacons = emptyMap(),
            device = DeviceInfo("m", "13"), networkTests = null, scriptRunner = ScriptRunner(TimedHttpClient()), clock = { 1_000L }
        )
        val script = HttpScript("Portal", steps = listOf(HttpScriptStep("a", url = "https://portal.example.com/")), alias = "Employee portal")
        val result = runner.measure(ThroughputSettings(iperfEnabled = false, internetEnabled = false), scripts = listOf(script))
        val run = result.scripts!!.single()
        assertEquals(ErrorCodes.NOT_ON_WIFI, run.errorCode)
        assertEquals(1, run.stepsTotal)
        assertTrue(run.steps.isEmpty())

        val point = ScanPoint(1L, 10f, 20f, emptyList(), pointId = "p", scanId = "s")
        val map = MapInfo("g.png", 100, 80)
        val report = EkahauReportBuilder().build("Site", listOf(EkahauFloorInput("G", map, 0.1, listOf(point), emptyList(), emptyList())))
        val snapshot = ProjectExportSnapshot("p", "Site", null, listOf(
            FloorExportSnapshot("f", "G", File("g.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null,
                ProjectSnapshot(map, 0.1, listOf(point), emptyList(), emptyList(),
                    activeRuns = listOf(ActiveRun("r", "p", "s", 1L, 2L, RunStatus.COMPLETED, results = result))))
        ))
        val json = PerformanceExport.build(report, "Site.esx", 0L, snapshot)
        val row = json.getAsJsonArray("scriptRuns").single().asJsonObject
        assertEquals(RunStatus.SKIPPED, row["status"].asString)
        assertEquals("Employee portal", row["scriptAlias"].asString)
        assertEquals(ErrorCodes.NOT_ON_WIFI, row["errorCode"].asString)
        assertEquals(0, row["stepsRun"].asInt)
        assertEquals(0, json.getAsJsonArray("scriptSteps").size())
    }

    @Test
    fun exportedPathsHideTokenLikeSegments() {
        assertEquals("/reset/…/done", Sensitive.displayPath("/reset/a1b2c3d4e5f6a7b8c9d0/done?x=1"))
        assertEquals("/api/v1/items", Sensitive.displayPath("/api/v1/items"))
        assertNull(Redactor(listOf("abc")).redact(null))
        assertEquals("token=…", Redactor(listOf("FAKE-secret")).redact("token=FAKE-secret"))
        assertEquals("x … y", Redactor(listOf("FAKE-secret")).redact("x FAKE-secret y"))
    }
}
