package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerTest {
    @Test
    fun loginExtractsATokenCookiesFlowAndEveryStepIsTimed() {
        MiniHttpServer { request ->
            when (request.path) {
                "/login" -> MiniHttpServer.Response(
                    200, """{"data":{"token":"t-42"}}""",
                    headers = listOf("Set-Cookie" to "session=abc; Path=/; HttpOnly")
                )
                "/items/A-01" -> if (request.headers["authorization"] == "Bearer t-42" &&
                    request.headers["cookie"] == "session=abc"
                ) {
                    MiniHttpServer.Response(200, """{"location":"A-01"}""", chunked = true)
                } else {
                    MiniHttpServer.Response(401, "no")
                }
                else -> MiniHttpServer.Response(404)
            }
        }.use { server ->
            val script = HttpScript(
                name = "lookup",
                variables = mapOf("user" to "op1"),
                steps = listOf(
                    HttpScriptStep(
                        name = "login", method = "POST", url = "${server.base}/login", allowWrite = true,
                        body = """{"u":"${'$'}{user}"}""", extract = mapOf("token" to "\$.data.token")
                    ),
                    HttpScriptStep(
                        name = "item", url = "${server.base}/items/A-01",
                        headers = mapOf("Authorization" to "Bearer \${token}"), expectContains = "A-01"
                    )
                )
            )

            val results = ScriptRunner(TimedHttpClient()).run(script)

            assertEquals("""{"u":"op1"}""", server.requests.first().body)
            assertEquals(listOf(true, true), results.map { it.ok })
            assertEquals(listOf(200, 200), results.map { it.status })
            assertEquals("/items/A-01", results[1].path)
            results.forEach { step ->
                assertTrue(step.dnsMs != null && step.connectMs != null && step.ttfbMs != null &&
                    step.downloadMs != null && step.totalMs != null)
            }
            assertEquals("""{"location":"A-01"}""".length.toLong(), results[1].bytes) // chunked body decoded
        }
    }

    @Test
    fun failedStepsSayWhyAndDependentStepsFailToo() {
        MiniHttpServer { MiniHttpServer.Response(500, "oops") }.use { server ->
            val script = HttpScript(
                name = "s",
                steps = listOf(
                    HttpScriptStep(name = "login", url = "${server.base}/login", extract = mapOf("token" to "\$.token")),
                    HttpScriptStep(name = "next", url = "${server.base}/x?t=\${token}"),
                    HttpScriptStep(name = "expected", url = "${server.base}/y", expectStatus = 500)
                )
            )

            val results = ScriptRunner(TimedHttpClient()).run(script)

            assertEquals("status 500; cannot extract token from \$.token", results[0].error)
            assertEquals("unknown variable token", results[1].error)
            assertTrue(results[2].ok)
        }
    }

    @Test
    fun writesAreRefusedWithoutAllowWrite() {
        val result = ScriptRunner(TimedHttpClient()).run(
            HttpScript("s", steps = listOf(HttpScriptStep(name = "w", method = "DELETE", url = "http://127.0.0.1:9/x")))
        ).single()
        assertEquals("DELETE not allowed (allowWrite is false)", result.error)
    }
}
