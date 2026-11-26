package com.example.wiprober

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
}
