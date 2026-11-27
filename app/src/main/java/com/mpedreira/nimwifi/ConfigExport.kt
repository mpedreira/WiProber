package com.mpedreira.nimwifi

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The device's current configuration as an importable `wiprober-config.json`: throughput, guide,
 * roaming, network tests and scripts. Credential values are never written; the names the
 * scripts need are listed under `_credentialsNeeded` so they can be provided again.
 */
object ConfigExport {
    fun toJson(
        imported: WiproberConfigStore.Imported,
        throughput: ThroughputSettings,
        guide: SurveyGuideSettings,
        credentialNames: Set<String>
    ): String {
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val root = JsonObject().apply {
            addProperty("format", WiproberConfigParser.FORMAT)
            addProperty("version", WiproberConfigParser.VERSION)
            addProperty("_comment", "Exported by nimWifi. Credential values are not included: add them under \"credentials\".")
            add("throughput", JsonObject().apply {
                addProperty("expectedSsid", throughput.expectedSsid)
                add("iperf", JsonObject().apply {
                    addProperty("enabled", throughput.iperfEnabled)
                    addProperty("label", throughput.iperfLabel)
                    addProperty("host", throughput.iperfHost)
                    addProperty("port", throughput.iperfPort)
                    addProperty("durationSeconds", throughput.iperfDurationSeconds)
                })
                add("internet", JsonObject().apply {
                    addProperty("enabled", throughput.internetEnabled)
                    addProperty("downloadUrl", throughput.internetDownloadUrl)
                    addProperty("uploadUrl", throughput.internetUploadUrl)
                    addProperty("durationSeconds", throughput.internetDurationSeconds)
                })
            })
            add("guide", JsonObject().apply {
                addProperty("enabled", guide.enabled)
                addProperty("spacingMeters", guide.spacingMeters)
                addProperty("coverageRadiusMeters", guide.coverageRadiusMeters)
                addProperty("ssid", guide.ssidFilter)
                addProperty("profile", guide.profile)
            })
            imported.roaming?.let { roaming ->
                add("roaming", JsonObject().apply {
                    addProperty("enabled", roaming.enabled)
                    addProperty("host", roaming.host)
                    addProperty("port", roaming.port)
                    addProperty("intervalMs", roaming.intervalMillis)
                    addProperty("method", roaming.method)
                    roaming.alias?.let { addProperty("alias", it) }
                    addProperty("trace", roaming.trace)
                })
            }
            imported.requirements?.let { add("requirements", gson.toJsonTree(it)) }
            imported.apNames?.let { add("apNames", gson.toJsonTree(it)) }
            add("tests", gson.toJsonTree(imported.tests.orEmpty()))
            add("scripts", gson.toJsonTree(imported.scripts.orEmpty()))
            val needed = imported.scripts.orEmpty().flatMap { it.credentialReferences() }.toSortedSet()
            if (needed.isNotEmpty()) {
                add("_credentialsNeeded", JsonArray().apply {
                    needed.forEach { add(it + if (it in credentialNames) " (set on the exporting device)" else " (missing)") }
                })
            }
        }
        return gson.toJson(root)
    }

    /**
     * Mejora 17: a summary to share (e.g. with a colleague or in a ticket), not to import: aliases,
     * test types, methods and counts only. Hosts and paths become `host-N`/`path-N` placeholders
     * (consistent within the file); query strings, header values, bodies, cookies, expected texts
     * and credential values are left out.
     */
    fun toShareable(imported: WiproberConfigStore.Imported, throughput: ThroughputSettings, guide: SurveyGuideSettings): String {
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val hosts = linkedMapOf<String, String>()
        fun host(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let { h ->
            if (h.startsWith("\${")) h else hosts.getOrPut(h.lowercase()) { "host-${hosts.size + 1}" }
        }
        fun endpoint(url: String?): String? = url?.takeIf { it.isNotBlank() }?.let { u ->
            val uri = runCatching { java.net.URI(u.replace(Regex("""\$\{[^}]*\}"""), "x")) }.getOrNull() ?: return@let "invalid"
            "${uri.scheme ?: "?"}://${host(uri.host) ?: "?"}${if (uri.port > 0) ":${uri.port}" else ""}"
        }
        val root = JsonObject().apply {
            addProperty("format", "nimwifi-config-summary")
            addProperty("version", 1)
            addProperty("_note", "Shareable summary: hosts are placeholders; no paths, queries, headers, bodies, cookies or credentials. Not importable.")
            add("throughput", JsonObject().apply {
                addProperty("iperf", throughput.iperfEnabled)
                addProperty("iperfLabel", throughput.iperfLabel)
                addProperty("iperfServer", host(throughput.iperfHost))
                addProperty("internet", throughput.internetEnabled)
                addProperty("internetDownload", endpoint(throughput.internetDownloadUrl))
                addProperty("internetUpload", endpoint(throughput.internetUploadUrl))
                addProperty("expectedSsid", throughput.expectedSsid.ifEmpty { null })
            })
            add("guide", JsonObject().apply { addProperty("profile", guide.profile); addProperty("ssid", guide.ssidFilter.ifEmpty { null }) })
            imported.roaming?.let { r ->
                add("roaming", JsonObject().apply {
                    addProperty("enabled", r.enabled); addProperty("method", r.method); addProperty("target", host(r.host))
                    addProperty("alias", r.alias); addProperty("intervalMs", r.intervalMillis)
                })
            }
            add("tests", JsonArray().apply {
                imported.tests.orEmpty().forEach { t ->
                    add(JsonObject().apply {
                        addProperty("alias", t.displayAlias); addProperty("type", t.type); addProperty("enabled", t.enabled)
                        addProperty("target", t.url?.let(::endpoint) ?: host(t.host)); t.port?.let { addProperty("port", it) }
                    })
                }
            })
            add("scripts", JsonArray().apply {
                imported.scripts.orEmpty().forEach { sc ->
                    add(JsonObject().apply {
                        addProperty("alias", sc.displayAlias); addProperty("enabled", sc.enabled); addProperty("steps", sc.steps.size)
                        addProperty("writes", sc.steps.count { it.isWrite }); addProperty("credentials", sc.credentialReferences().size)
                        add("endpoints", JsonArray().apply { sc.steps.mapNotNull { endpoint(it.url) }.distinct().forEach(::add) })
                    })
                }
            })
        }
        return gson.toJson(root)
    }
}
