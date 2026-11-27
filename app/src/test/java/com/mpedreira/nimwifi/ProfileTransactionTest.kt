package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Mejora 16: a profile is activated as a whole or not at all. */
class ProfileTransactionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun config(credential: String, test: String) = WiproberConfigParser.parse(
        """{"format": "wiprober-config", "version": 1, "credentials": {"token": "$credential"},
           "tests": [{"name": "$test", "type": "tcp", "host": "10.0.0.1", "port": 443}],
           "scripts": [{"name": "s", "steps": [{"url": "https://app.example.com/", "headers": {"X-Api-Key": "${'$'}{secret.token}"}}]}]}""",
        "c.json"
    )

    @Test
    fun aFailureAfterPreparingTheNewCredentialsLeavesTheOldProfileWhole() {
        val dir = temp.newFolder()
        WiproberConfigStore(dir).applyImported(config("OLD-token", "old-test"))
        val failing = WiproberConfigStore(dir, beforeActivate = { throw java.io.IOException("power loss") })
        try {
            failing.applyImported(config("NEW-token", "new-test"))
            throw AssertionError("expected failure")
        } catch (e: java.io.IOException) {
            assertEquals("power loss", e.message)
        }
        val store = WiproberConfigStore(dir)
        assertEquals("OLD-token", store.loadCredentials()["token"])
        assertEquals(listOf("old-test"), store.load().tests!!.map { it.name })
        assertEquals(WiproberConfigStore.Status.OK, store.status().state)
        // The half-written revision was removed.
        assertEquals(1, File(dir, "revisions").listFiles()!!.size)
    }

    @Test
    fun successiveHarMergesKeepTheFormatLimitsOfTheProfileInUse() {
        val store = WiproberConfigStore(temp.newFolder())
        val har = { n: Int -> """{"log": {"entries": [{"request": {"method": "GET", "url": "https://app$n.example.com/", "headers": []}}]}}""" }
        repeat(WiproberConfigParser.MAX_SCRIPTS) { n -> store.applyImported(WiproberConfigParser.parse(har(n), "rec$n.har")) }
        assertEquals(WiproberConfigParser.MAX_SCRIPTS, store.load().scripts!!.size)
        val before = store.load()
        try {
            store.applyImported(WiproberConfigParser.parse(har(99), "rec99.har"))
            throw AssertionError("expected the profile limit")
        } catch (e: ConfigException) {
            assertTrue(e.message!!.contains("scripts"))
        }
        assertEquals(before, store.load())
        // Re-importing a recording with the same name replaces it and stays within the limits.
        store.applyImported(WiproberConfigParser.parse(har(5), "rec0.har"))
        assertEquals(WiproberConfigParser.MAX_SCRIPTS, store.load().scripts!!.size)
    }

    @Test
    fun aMissingProfileAndACorruptOneAreToldApart() {
        val dir = temp.newFolder()
        val store = WiproberConfigStore(dir)
        assertEquals(WiproberConfigStore.Status.ABSENT, store.status().state)
        store.applyImported(config("T", "t"))
        val revision = store.status().revision!!
        File(File(File(dir, "revisions"), revision), "imported.json").writeText("{ not json")
        val status = store.status()
        assertEquals(WiproberConfigStore.Status.CORRUPT, status.state)
        // A change never silently replaces a corrupt profile.
        try {
            store.setCredential("x", "y")
            throw AssertionError("expected ConfigException")
        } catch (e: ConfigException) {
            assertTrue(e.message!!.contains("cannot be read"))
        }
        store.clear()
        assertEquals(WiproberConfigStore.Status.ABSENT, store.status().state)
    }

    @Test
    fun aProfileWrittenByAnEarlierVersionIsStillRead() {
        val dir = temp.newFolder()
        File(dir, "imported.json").writeText("""{"tests": [{"name": "legacy", "type": "tcp", "host": "10.0.0.1", "port": 22}]}""")
        File(dir, "credentials.json").writeText("""{"k": "v"}""")
        val store = WiproberConfigStore(dir)
        assertEquals("legacy", store.load().tests!!.single().name)
        assertEquals(mapOf("k" to "v"), store.loadCredentials())
        store.setCredential("k2", "v2") // the first change writes a revision
        assertEquals(setOf("k", "k2"), store.credentialNames())
        assertEquals("legacy", store.load().tests!!.single().name)
    }
}
