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
}
