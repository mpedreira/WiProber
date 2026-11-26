package com.mpedreira.nimwifi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compiles the placeholder regexes with Android's ICU engine. An unescaped `}` passes on the JVM
 * but made ScriptRunner's static initializer throw on the device, crashing the first active test.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRegexInstrumentedTest {
    @Test
    fun placeholderRegexesCompileOnAndroid() {
        val loader = javaClass.classLoader
        Class.forName("com.mpedreira.nimwifi.ScriptRunner", true, loader)
        Class.forName("com.mpedreira.nimwifi.JsonPathLite", true, loader)
        val names = HttpScript.SECRET_REFERENCE.findAll("a=\${secret.TOKEN}&b=\${secret.PASS_2}")
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("TOKEN", "PASS_2"), names)
    }
}
