package com.mpedreira.nimwifi

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 17: operational configuration versus shareable files; scoped replay cookies. */
class ShareableConfigTest {
    private val secretValues = listOf("FAKE-url-token-111", "FAKE-body-pass-222", "FAKE-header-333", "FAKE-cookie-444", "intranet.corp.example")

    private val config = WiproberConfigParser.parse("""
        {"format": "wiprober-config", "version": 1,
         "throughput": {"internet": {"enabled": true, "downloadUrl": "https://speed.example.com/down?bytes={bytes}&token=FAKE-url-token-111"}},
         "tests": [{"name": "erp", "type": "http", "url": "https://intranet.corp.example/health?apikey=FAKE-url-token-111", "expectContains": "A-01"}],
         "scripts": [{"name": "portal", "variables": {"password": "FAKE-body-pass-222"}, "steps": [
            {"name": "login", "method": "POST", "allowWrite": true, "url": "https://intranet.corp.example/login",
             "headers": {"Authorization": "Bearer FAKE-header-333", "Cookie": "sid=FAKE-cookie-444"}, "body": "p=${'$'}{password}"}]}]}
    """.trimIndent(), "c.json")

    @Test
    fun secretsInTestAndThroughputUrlsBecomeReferences() {
        assertEquals("https://intranet.corp.example/health?apikey=\${secret.erp_apikey}", config.tests!!.single().url)
        assertEquals("https://speed.example.com/down?bytes={bytes}&token=\${secret.internet_download_token}", config.throughput!!.internetDownloadUrl)
        assertEquals("FAKE-url-token-111", config.credentials!!["erp_apikey"])
        assertEquals("https://x.example.com/?k=FAKE", SecretRefs.resolve("https://x.example.com/?k=\${secret.a}", mapOf("a" to "FAKE")))
        try {
            SecretRefs.resolve("\${secret.none}", emptyMap()); throw AssertionError("expected missing_credential")
        } catch (e: MeasurementException) {
            assertEquals(ErrorCodes.MISSING_CREDENTIAL, e.code)
        }
    }

    @Test
    fun theShareableSummaryHasAliasesAndPlaceholdersOnly() {
        val imported = WiproberConfigStore.Imported(config.tests, config.scripts, RoamingSettings(true, "intranet.corp.example", 443, 200, alias = "ERP"))
        val shareable = ConfigExport.toShareable(imported, config.throughput!!, SurveyGuideSettings())
        secretValues.forEach { assertFalse("leaked $it: $shareable", it in shareable) }
        assertFalse("/health" in shareable || "/login" in shareable || "bytes=" in shareable || "Bearer" in shareable)
        assertTrue("host-1" in shareable && "\"alias\": \"erp\"" in shareable && "\"writes\": 1" in shareable)
        // The operational export never has credential values either.
        val operational = ConfigExport.toJson(imported, config.throughput!!, SurveyGuideSettings(), config.credentials!!.keys)
        listOf("FAKE-url-token-111", "FAKE-body-pass-222", "FAKE-header-333").forEach { assertFalse(it in operational) }
    }

    @Test
    fun errorsAreRedactedConsistently() {
        val error = MeasurementException(ErrorCodes.HTTP_STATUS, "GET https://u:p@intranet.corp.example/a?token=FAKE-url-token-111 failed, Authorization: Bearer FAKE-header-333")
        val detail = Redactor(listOf("FAKE-url-token-111", "FAKE-header-333")).redact(ErrorClassifier.detail(error))!!
        listOf("FAKE-url-token-111", "FAKE-header-333", "u:p@").forEach { assertFalse(detail, it in detail) }
    }

    @Test
    fun replayCookiesRespectDomainPathAndSecure() {
        val jar = ReplayCookieJar()
        jar.store(URI("https://app.example.com/a/login"), listOf("scoped=FAKE-cookie-444; Path=/a", "sec=S1; Path=/; Secure", "all=A1; Path=/"))
        assertEquals("all=A1; scoped=FAKE-cookie-444; sec=S1", jar.header(URI("https://app.example.com/a/items"))?.split("; ")?.sorted()?.joinToString("; "))
        // Another path: the /a cookie stays out.
        val other = jar.header(URI("https://app.example.com/b"))!!
        assertFalse("scoped=" in other)
        // Plain HTTP: the Secure cookie is not sent.
        assertFalse("sec=" in (jar.header(URI("http://app.example.com/")) ?: ""))
        // Another host: nothing.
        assertNull(jar.header(URI("https://other.example.com/")))
    }

    @Test
    fun theJarIsBounded() {
        val jar = ReplayCookieJar()
        jar.store(URI("https://app.example.com/"), (1..40).map { "c$it=v; Path=/" })
        assertEquals(ReplayCookieJar.MAX_COOKIES, jar.header(URI("https://app.example.com/"))!!.split("; ").size)
    }
}
