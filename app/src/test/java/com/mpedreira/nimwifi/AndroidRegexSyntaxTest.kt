package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles `Regex` with ICU, which rejects an unescaped `}`/`{` that is not part of a
 * `{n,m}` quantifier and an unescaped `]` outside a character class. The JVM accepts both, so
 * JVM tests never notice; a `${secret.NAME}` placeholder pattern crashed the app on its first
 * active test. This scans every regex literal in the main sources for those constructs.
 */
class AndroidRegexSyntaxTest {
    @Test
    fun regexLiteralsUseIcuCompatibleSyntax() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("main sources not found", sources.isNotEmpty())
        val problems = sources.flatMap { file ->
            regexLiterals(file.readText()).mapNotNull { pattern ->
                icuProblem(pattern)?.let { "${file.name}: $pattern ($it)" }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun checkerFlagsTheConstructsIcuRejects() {
        assertTrue(icuProblem("""\$\{(secret\.)?([A-Za-z0-9_]+)}""") != null)
        assertTrue(icuProblem("""\[(\d+)]""") != null)
        assertTrue(icuProblem("""a{b""") != null)
        assertTrue(icuProblem("""\$\{(secret\.)?([A-Za-z0-9_]+)\}""") == null)
        assertTrue(icuProblem("""[A-Za-z0-9_-]{1,100}""") == null)
        assertTrue(icuProblem("""\d{3}[}\]]""") == null)
    }

    private fun regexLiterals(source: String): List<String> {
        val raw = Regex("Regex\\(\"\"\"(.*?)\"\"\"").findAll(source).map { it.groupValues[1] }
        val quoted = Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(source)
            .map { it.groupValues[1].replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\") }
        return (raw + quoted).toList()
    }

    /** Null when ICU accepts these constructs; otherwise what is wrong. */
    private fun icuProblem(pattern: String): String? {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> {
                    inClass = true
                    if (pattern.getOrNull(i + 1) == '^') i++
                    if (pattern.getOrNull(i + 1) == ']') i++ // a leading ] is literal
                }
                c == '{' -> {
                    val quantifier = Regex("""\{\d+(,\d*)?\}""").matchAt(pattern, i)
                        ?: return "unescaped { at $i"
                    i += quantifier.value.length - 1
                }
                c == '}' -> return "unescaped } at $i"
                c == ']' -> return "unescaped ] at $i"
            }
            i++
        }
        return null
    }
}
