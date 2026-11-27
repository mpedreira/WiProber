package com.mpedreira.nimwifi

import java.security.MessageDigest

/**
 * Mejora 9: stable identity of a measurement series, separate from its visible alias. Two
 * results are comparable only when they share a series: same kind of test, direction, endpoint
 * (scheme, host, effective port), method and, for scripts, the same definition. TCP and ICMP
 * stay apart. Results saved before this information existed get a `legacy:` identity built from
 * what they have, marked as not comparable.
 */
data class Series(val id: String, val label: String, val comparable: Boolean) {
    companion object {
        const val DOWNLOAD = "download"
        const val UPLOAD = "upload"
        const val LATENCY = "latency"

        fun throughput(test: ThroughputTestResult, direction: String): Series {
            val target = test.targetId ?: test.target
            val endpoint = when (direction) {
                UPLOAD -> test.uploadEndpointId ?: test.uploadEndpoint
                else -> test.downloadEndpointId ?: test.downloadEndpoint
            }
            val method = if (direction == LATENCY) test.latencyMethod ?: "tcp-connect" else test.method
            val known = test.targetId != null && endpoint != null
            val id = if (known) "throughput|$target|$direction|$endpoint|$method" else "legacy:throughput|${test.target}|$direction|$method"
            return Series(id, "${test.target} · $direction · ${endpoint ?: "?"} · $method", known)
        }

        fun networkTest(test: NetworkTestResult): Series {
            // The method keeps ICMP and TCP pings apart; the target carries host, port and path.
            val known = test.method != null && test.target != null
            val id = if (known) "test|${test.type}|${test.name}|${test.target}|${test.method}" else "legacy:test|${test.type}|${test.name}"
            return Series(id, "${test.alias ?: test.name} · ${test.type} · ${test.target ?: "?"} · ${test.method ?: "?"}", known)
        }

        fun script(run: ScriptRun): Series {
            val known = run.definitionHash != null
            val id = if (known) "script|${run.name}|${run.definitionHash}" else "legacy:script|${run.name}"
            return Series(id, "${run.alias ?: run.name} · script${run.definitionHash?.let { " · def $it" } ?: ""}", known)
        }

        /** Short digest of a script's definition (methods, URL templates, expectations; never credential values). */
        fun definitionHash(script: HttpScript): String {
            val text = script.steps.joinToString("\n") { step ->
                listOf(step.method, step.url, step.expectStatus, step.expectContains, step.allowWrite, step.allowRedirect,
                    step.extract.toSortedMap(), step.headers.keys.sorted()).joinToString("|")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
