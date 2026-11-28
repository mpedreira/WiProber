package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RC4 · 1: one Internet destination drop-down with stable ids; custom URLs are never lost. */
class InternetServersTest {
    private val ams = InternetServers.byId("librespeed-ams-clouvider")!!

    @Test
    fun theCatalogueHasStableIdsAndModerateLoad() {
        assertEquals(listOf("cloudflare-auto", "librespeed-ams-clouvider", "librespeed-fra-clouvider", "librespeed-lon-clouvider", "librespeed-rom-garr"),
            InternetServers.all.map { it.id })
        assertTrue(InternetServers.all.all { it.downloadUrl.startsWith("https://") && it.uploadUrl.startsWith("https://") })
        assertEquals("https://ams.speedtest.clouvider.net/backend/garbage.php?ckSize=10", ams.downloadUrl)
        assertEquals("https://st-be-rm2.infra.garr.it/empty.php", InternetServers.byId("librespeed-rom-garr")!!.uploadUrl)
        assertTrue(InternetServers.all.filter { it.provider == "LibreSpeed" }.all { it.streams <= 2 && it.maxUploadChunkBytes <= 4 * 1024 * 1024 })
        assertEquals("LibreSpeed · Ámsterdam · Clouvider", ams.label)
    }

    @Test
    fun oldSettingsAndImportedProfilesKeepTheirUrlsAndSelectAnExactMatch() {
        // Defaults (Cloudflare URLs, no id): Cloudflare is selected.
        assertEquals(InternetServers.CLOUDFLARE, ThroughputSettings().sanitized().internetServerId)
        // Exactly a catalogue entry: that entry, enabled as it was.
        val known = ThroughputSettings(internetEnabled = true, internetDownloadUrl = ams.downloadUrl, internetUploadUrl = ams.uploadUrl).sanitized()
        assertEquals(listOf(ams.id, true), listOf(known.internetServerId, known.internetEnabled))
        // Anything else: custom, with its URLs.
        val custom = ThroughputSettings(internetDownloadUrl = "https://speed.example.com/down?bytes={bytes}", internetUploadUrl = "https://speed.example.com/up").sanitized()
        assertEquals(InternetServers.CUSTOM, custom.internetServerId)
        assertEquals("https://speed.example.com/down?bytes={bytes}", custom.internetDownloadUrl)
        assertEquals("https://speed.example.com/down?bytes={bytes}", custom.customDownloadUrl)
        // A URL that differs in one character is not the catalogue entry.
        assertEquals(InternetServers.CUSTOM, InternetServers.match(ams.downloadUrl + "0", ams.uploadUrl))
    }

    @Test
    fun choosingAnotherServerKeepsTheCustomUrlsForLater() {
        val custom = ThroughputSettings(internetServerId = InternetServers.CUSTOM, customDownloadUrl = "https://speed.example.com/d", customUploadUrl = "https://speed.example.com/u").sanitized()
        val onAms = custom.copy(internetServerId = ams.id).sanitized()
        assertEquals(ams.downloadUrl, onAms.internetDownloadUrl)
        assertEquals("https://speed.example.com/d", onAms.customDownloadUrl)
        val back = onAms.copy(internetServerId = InternetServers.CUSTOM).sanitized()
        assertEquals(listOf("https://speed.example.com/d", "https://speed.example.com/u"), listOf(back.internetDownloadUrl, back.internetUploadUrl))
        assertEquals(listOf(2, 4), listOf(onAms.internetStreams, back.internetStreams))
    }

    @Test
    fun importAndExportCarryTheSelection() {
        fun parse(internet: String) = WiproberConfigParser.parse("""{"format":"wiprober-config","version":1,"throughput":{"internet":$internet}}""", "c.json").throughput!!
        assertEquals(ams.id, parse("""{"enabled":true,"downloadUrl":"${ams.downloadUrl}","uploadUrl":"${ams.uploadUrl}"}""").internetServerId)
        assertEquals("librespeed-lon-clouvider", parse("""{"server":"librespeed-lon-clouvider"}""").internetServerId)
        val custom = parse("""{"enabled":false,"downloadUrl":"https://speed.example.com/d","uploadUrl":"https://speed.example.com/u"}""")
        assertEquals(listOf(InternetServers.CUSTOM, false, "https://speed.example.com/d"), listOf(custom.internetServerId, custom.internetEnabled, custom.customDownloadUrl))
        val exported = ConfigExport.toJson(WiproberConfigStore.Imported(), custom, SurveyGuideSettings(), emptySet())
        assertEquals(custom, WiproberConfigParser.parse(exported, "c.json").throughput)
        // A literal secret in a custom URL moves to the credentials, in both copies of the URL.
        val config = WiproberConfigParser.parse("""{"format":"wiprober-config","version":1,"throughput":{"internet":{"downloadUrl":"https://speed.example.com/d?token=abcdefgh12345678","uploadUrl":"https://speed.example.com/u"}}}""", "c.json")
        assertFalse(config.throughput!!.customDownloadUrl.contains("abcdefgh12345678"))
        assertFalse(config.throughput!!.internetDownloadUrl.contains("abcdefgh12345678"))
    }
}
