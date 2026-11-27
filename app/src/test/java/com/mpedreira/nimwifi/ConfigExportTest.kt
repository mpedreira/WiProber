package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun theExportedConfigurationImportsAgainWithoutCredentialValues() {
        val script = HttpScript("portal", steps = listOf(HttpScriptStep("a", url = "https://portal.example.com/", headers = mapOf("Authorization" to "\${secret.token}"))))
        val imported = WiproberConfigStore.Imported(
            tests = listOf(NetworkTest("gw", "ping", host = "\${gateway}", alias = "Gateway")),
            scripts = listOf(script),
            roaming = RoamingSettings(true, "\${gateway}", 443, 300)
        )
        val json = ConfigExport.toJson(imported, ThroughputSettings(iperfEnabled = true, iperfHost = "iperf.example.com"),
            SurveyGuideSettings(profile = "voz"), setOf("token"))
        assertFalse("FAKE" in json)
        assertTrue("_credentialsNeeded" in json)

        val again = WiproberConfigParser.parse(json, "wiprober-config.json")
        assertEquals(imported.tests, again.tests)
        assertEquals(listOf(script), again.scripts)
        assertEquals(imported.roaming, again.roaming)
        assertEquals("voz", again.guide!!.profile)
        assertEquals("iperf.example.com", again.throughput!!.iperfHost)
    }

    @Test
    fun theStoreEditsItemsAndCredentialsByName() {
        val store = WiproberConfigStore(temp.newFolder())
        store.update { it.copy(tests = listOf(NetworkTest("gw", "tcp", host = "10.0.0.1", port = 443))) }
        store.setCredential("token", "FAKE-value")
        assertEquals(setOf("token"), store.credentialNames())
        store.removeCredential("token")
        assertTrue(store.credentialNames().isEmpty())
        assertEquals("gw", store.load().tests!!.single().name)
    }

    @Test
    fun filesAreRecognisedByTheirContent() {
        val json = temp.newFile("a.bin").apply { writeText("  {\"format\": \"wiprober-config\"}") }
        assertEquals(ImportedFiles.Kind.CONFIG_JSON, ImportedFiles.detect(json))
        val other = temp.newFile("b.zip").apply { writeText("not a zip") }
        assertEquals(ImportedFiles.Kind.UNKNOWN, ImportedFiles.detect(other))
        val bundle = temp.newFile("c.zip")
        java.util.zip.ZipOutputStream(bundle.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("Site.esx")); zip.write(byteArrayOf(1)); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(PerformanceExport.FILE_NAME)); zip.write("{}".toByteArray()); zip.closeEntry()
        }
        assertEquals(ImportedFiles.Kind.EXPORT_BUNDLE, ImportedFiles.detect(bundle))
        val esx = temp.newFile("d.esx")
        ImportedFiles.extractBundleEsx(bundle, esx, 1_000)
        assertEquals(1L, esx.length())
    }
}
