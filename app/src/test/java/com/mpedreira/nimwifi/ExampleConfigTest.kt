package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The documented example must always import. */
class ExampleConfigTest {
    @Test
    fun documentedExampleParses() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/wiprober-config.example.json") }
            .first { it.isFile }
        val config = WiproberConfigParser.parse(file.readText(), file.name)
        assertEquals(4, config.tests!!.size)
        assertEquals(listOf("login", "location"), config.scripts!!.single().steps.map { it.name })
        assertEquals(true, config.roaming!!.enabled)
        assertEquals("Item lookup", config.scripts!!.single().displayAlias)
        assertEquals(setOf("password"), config.scripts!!.single().credentialReferences())
        assertEquals(setOf("password"), config.credentials!!.keys)
    }

    @Test
    fun exampleConfigurationZipImportsWithItsCommentsAndHar() {
        val zip = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/examples/nimwifi-config-example.zip") }
            .first { it.isFile }
        assertEquals(ImportedFiles.Kind.CONFIG_ZIP, ImportedFiles.detect(zip))
        val config = WiproberConfigParser.parseZip(ImportedFiles.readConfigZip(zip))
        assertEquals(listOf("portal-lookup", "stock-check"), config.scripts!!.map { it.name })
        assertEquals(4, config.tests!!.size)
        assertEquals(setOf("portal_password"), config.credentials!!.keys) // "_comment" keys are not credentials
        assertEquals("pda", config.guide!!.profile)
        val har = config.scripts!!.last()
        assertEquals(2, har.steps.size) // the image is not imported
        assertEquals(setOf("har_Authorization", "har_access_token"), har.credentialReferences())
        assertEquals(false, har.steps.any { step -> step.headers.keys.any { it.startsWith("_") || it.equals("Cookie", true) } })
        assertEquals(false, com.google.gson.Gson().toJson(config.scripts).contains("FAKE-"))
    }
}
