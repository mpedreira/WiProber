package com.example.wiprober

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * Configuration imported into the app as a file (`wiprober-config.json`) or a browser `.har`
 * recording. Servers, credentials and scripts live only in that file on the device, never in
 * this open-source code. Every section is optional.
 */
data class WiproberConfig(
    val throughput: ThroughputSettings? = null,
    val guide: SurveyGuideSettings? = null,
    /** Null = the file has no such section, so the device keeps what it had. */
    val tests: List<NetworkTest>? = null,
    val scripts: List<HttpScript>? = null,
    val roaming: RoamingSettings? = null,
    /** A HAR adds (or replaces by name) one script instead of replacing them all. */
    val mergeScripts: Boolean = false
)

/**
 * A network service test run at every Stop-and-Go point, like the AutoTest of commercial survey
 * tools. `${gateway}` and `${dns}` in host/url are the Wi-Fi network's default gateway and first
 * DNS server.
 */
@JsonModel
data class NetworkTest(
    val name: String,
    /** `dns`, `ping`, `tcp` or `http`. */
    val type: String,
    val host: String? = null,
    val port: Int? = null,
    val url: String? = null,
    /** Probes for ping/tcp. */
    val count: Int? = null,
    val expectStatus: Int? = null,
    val expectContains: String? = null,
    val enabled: Boolean = true,
    /** Exported alias; the stable identifier is [name]. */
    val alias: String? = null
) {
    val displayAlias: String get() = alias?.takeIf { it.isNotBlank() } ?: name

    companion object {
        const val DNS = "dns"
        const val PING = "ping"
        const val TCP = "tcp"
        const val HTTP = "http"
        val TYPES = setOf(DNS, PING, TCP, HTTP)
    }
}

/** Continuous-mode roaming probe: a TCP connect to [host]:[port] every [intervalMillis]. */
@JsonModel
data class RoamingSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 443,
    val intervalMillis: Int = DEFAULT_INTERVAL_MILLIS
) {
    fun sanitized(): RoamingSettings = copy(
        host = host.trim(),
        port = port.coerceIn(1, 65_535),
        intervalMillis = intervalMillis.coerceIn(100, 5_000)
    )

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 200
    }
}

/** A sequence of HTTP requests replayed at each point, like a recorded user flow. */
@JsonModel
data class HttpScript(
    val name: String,
    val enabled: Boolean = true,
    val variables: Map<String, String> = emptyMap(),
    val steps: List<HttpScriptStep>
)

/**
 * One request. `${name}` in url/headers/body is replaced by a script variable or a value
 * extracted by an earlier step. Only GET/HEAD run unless [allowWrite] is set: replaying writes
 * at every point could create real records.
 */
@JsonModel
data class HttpScriptStep(
    val name: String,
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val allowWrite: Boolean = false,
    /** Expected status; null accepts any 2xx/3xx. */
    val expectStatus: Int? = null,
    /** Text the response body must contain. */
    val expectContains: String? = null,
    /** Variables to read from a JSON response: name to path like `$.data.token` or `$.items[0].id`. */
    val extract: Map<String, String> = emptyMap()
) {
    val isWrite: Boolean get() = method.uppercase() !in READ_METHODS

    companion object {
        val READ_METHODS = setOf("GET", "HEAD")
    }
}

class ConfigException(message: String) : Exception(message)

object WiproberConfigParser {
    const val FORMAT = "wiprober-config"
    const val VERSION = 1
    const val MAX_SCRIPTS = 20
    const val MAX_STEPS = 50
    const val MAX_TESTS = 30
    const val MAX_PROBES = 50
    private val NAME = Regex("[A-Za-z0-9 _.-]{1,40}")

    /** Parses either a `wiprober-config` JSON or a browser HAR (turned into one script). */
    fun parse(text: String, fileName: String): WiproberConfig {
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            throw ConfigException("Not a JSON file: ${e.message}")
        }
        return if (root.has("log") && root["log"].isJsonObject) {
            WiproberConfig(scripts = listOf(HarImporter.toScript(root, scriptNameFrom(fileName))), mergeScripts = true)
        } else {
            parseConfig(root)
        }
    }

    private fun parseConfig(root: JsonObject): WiproberConfig {
        if (root.string("format") != FORMAT) throw ConfigException("\"format\" must be \"$FORMAT\"")
        val version = root.int("version")
        if (version != VERSION) throw ConfigException("Unsupported version $version (expected $VERSION)")
        val scripts = root.getAsJsonArray("scripts")?.map { parseScript(it.asJsonObject) }
        if ((scripts?.size ?: 0) > MAX_SCRIPTS) throw ConfigException("At most $MAX_SCRIPTS scripts")
        if (scripts != null && scripts.map { it.name }.toSet().size != scripts.size) {
            throw ConfigException("Script names must be unique")
        }
        val tests = root.getAsJsonArray("tests")?.mapIndexed { i, test -> parseTest(test.asJsonObject, "test ${i + 1}") }
        if ((tests?.size ?: 0) > MAX_TESTS) throw ConfigException("At most $MAX_TESTS tests")
        if (tests != null && tests.map { it.name }.toSet().size != tests.size) {
            throw ConfigException("Test names must be unique")
        }
        return WiproberConfig(
            throughput = root.obj("throughput")?.let(::parseThroughput),
            guide = root.obj("guide")?.let(::parseGuide),
            tests = tests,
            scripts = scripts,
            roaming = root.obj("roaming")?.let(::parseRoaming)
        )
    }

    private fun parseTest(json: JsonObject, where: String): NetworkTest {
        val name = json.string("name") ?: throw ConfigException("$where has no \"name\"")
        if (!NAME.matches(name)) throw ConfigException("Test name \"$name\": use letters, digits, space, _ . - (max 40)")
        val type = json.string("type")?.lowercase()
        if (type !in NetworkTest.TYPES) throw ConfigException("Test \"$name\": type must be one of ${NetworkTest.TYPES}")
        val test = NetworkTest(
            name = name,
            type = type!!,
            host = json.string("host")?.trim(),
            port = json.int("port"),
            url = json.string("url")?.trim(),
            count = json.int("count"),
            expectStatus = json.int("expectStatus"),
            expectContains = json.string("expectContains"),
            enabled = json.bool("enabled") ?: true,
            alias = json.string("alias")?.trim()?.take(60)
        )
        when (type) {
            NetworkTest.HTTP -> checkUrl(test.url ?: throw ConfigException("Test \"$name\" needs \"url\""), "Test \"$name\"")
            else -> if (test.host.isNullOrEmpty()) throw ConfigException("Test \"$name\" needs \"host\"")
        }
        if (type == NetworkTest.TCP && test.port !in 1..65_535) throw ConfigException("Test \"$name\" needs a valid \"port\"")
        if (test.count != null && test.count !in 1..MAX_PROBES) throw ConfigException("Test \"$name\": count must be 1..$MAX_PROBES")
        return test
    }

    private fun parseRoaming(json: JsonObject): RoamingSettings {
        val d = RoamingSettings()
        return RoamingSettings(
            enabled = json.bool("enabled") ?: d.enabled,
            host = json.string("host") ?: d.host,
            port = json.int("port") ?: d.port,
            intervalMillis = json.int("intervalMs") ?: d.intervalMillis
        ).sanitized()
    }

    private fun parseThroughput(json: JsonObject): ThroughputSettings {
        val d = ThroughputSettings()
        val iperf = json.obj("iperf")
        val internet = json.obj("internet")
        return ThroughputSettings(
            iperfEnabled = iperf?.bool("enabled") ?: d.iperfEnabled,
            iperfLabel = iperf?.string("label") ?: d.iperfLabel,
            iperfHost = iperf?.string("host") ?: d.iperfHost,
            iperfPort = iperf?.int("port") ?: d.iperfPort,
            iperfDurationSeconds = iperf?.int("durationSeconds") ?: d.iperfDurationSeconds,
            internetEnabled = internet?.bool("enabled") ?: d.internetEnabled,
            internetDownloadUrl = internet?.string("downloadUrl") ?: d.internetDownloadUrl,
            internetUploadUrl = internet?.string("uploadUrl") ?: d.internetUploadUrl,
            internetDurationSeconds = internet?.int("durationSeconds") ?: d.internetDurationSeconds
        ).sanitized()
    }

    private fun parseGuide(json: JsonObject): SurveyGuideSettings {
        val d = SurveyGuideSettings()
        return SurveyGuideSettings(
            enabled = json.bool("enabled") ?: d.enabled,
            spacingMeters = json.double("spacingMeters") ?: d.spacingMeters,
            coverageRadiusMeters = json.double("coverageRadiusMeters") ?: d.coverageRadiusMeters,
            ssidFilter = json.string("ssid") ?: d.ssidFilter
        ).sanitized()
    }

    private fun parseScript(json: JsonObject): HttpScript {
        val name = json.string("name") ?: throw ConfigException("A script has no \"name\"")
        if (!NAME.matches(name)) throw ConfigException("Script name \"$name\": use letters, digits, space, _ . - (max 40)")
        val steps = json.getAsJsonArray("steps")?.mapIndexed { i, step ->
            parseStep(step.asJsonObject, "$name step ${i + 1}")
        }.orEmpty()
        if (steps.isEmpty()) throw ConfigException("Script \"$name\" has no steps")
        if (steps.size > MAX_STEPS) throw ConfigException("Script \"$name\": at most $MAX_STEPS steps")
        return HttpScript(
            name = name,
            enabled = json.bool("enabled") ?: true,
            variables = json.stringMap("variables"),
            steps = steps
        )
    }

    private fun parseStep(json: JsonObject, where: String): HttpScriptStep {
        val url = json.string("url") ?: throw ConfigException("$where has no \"url\"")
        checkUrl(url, where)
        val step = HttpScriptStep(
            name = json.string("name") ?: where.substringAfterLast(' ').let { "step $it" },
            method = (json.string("method") ?: "GET").uppercase(),
            url = url,
            headers = json.stringMap("headers"),
            body = json.string("body"),
            allowWrite = json.bool("allowWrite") ?: false,
            expectStatus = json.int("expectStatus"),
            expectContains = json.string("expectContains"),
            extract = json.stringMap("extract")
        )
        if (step.method !in SUPPORTED_METHODS) throw ConfigException("$where: unsupported method ${step.method}")
        if (step.isWrite && !step.allowWrite) {
            throw ConfigException("$where is a ${step.method}: add \"allowWrite\": true if replaying it is harmless")
        }
        step.extract.values.forEach { path ->
            if (!JsonPathLite.isValid(path)) throw ConfigException("$where: invalid extract path \"$path\"")
        }
        return step
    }

    /** Variables may hide the host, so only literal URLs are checked strictly. */
    internal fun checkUrl(url: String, where: String) {
        if ("\${" in url.substringBefore("://", "")) throw ConfigException("$where: the URL scheme cannot be a variable")
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") throw ConfigException("$where: URL must start with http:// or https://")
        if ("\${" !in url) {
            runCatching { requireNotNull(URI(url).host) }.getOrElse { throw ConfigException("$where: invalid URL $url") }
        }
    }

    private fun scriptNameFrom(fileName: String): String =
        fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(40).ifBlank { "har" }

    private val SUPPORTED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

    private fun JsonObject.member(name: String): JsonElement? = get(name)?.takeUnless(JsonElement::isJsonNull)
    private fun JsonObject.obj(name: String): JsonObject? = member(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(name: String): String? = member(name)?.asString
    private fun JsonObject.bool(name: String): Boolean? = member(name)?.asBoolean
    private fun JsonObject.int(name: String): Int? = member(name)?.asInt
    private fun JsonObject.double(name: String): Double? = member(name)?.asDouble
    private fun JsonObject.stringMap(name: String): Map<String, String> =
        obj(name)?.entrySet()?.associate { (key, value) -> key to value.asString }.orEmpty()
}

/**
 * Turns a browser HAR recording into a script: only the app's own requests (documents, XHR and
 * fetch when the browser recorded the resource type), only reads, at most [WiproberConfigParser.MAX_STEPS].
 * Cookies are dropped (the replay keeps its own cookie jar) and so are browser-managed headers.
 */
object HarImporter {
    private val APP_RESOURCE_TYPES = setOf("document", "xhr", "fetch")
    private val DROPPED_HEADERS = setOf(
        "host", "connection", "content-length", "cookie", "accept-encoding", "transfer-encoding", "keep-alive"
    )

    fun toScript(har: JsonObject, name: String): HttpScript {
        val entries = har.getAsJsonObject("log").getAsJsonArray("entries")
            ?: throw ConfigException("HAR has no entries")
        val steps = entries.map { it.asJsonObject }
            .filter { entry ->
                val type = entry.get("_resourceType")?.takeUnless(JsonElement::isJsonNull)?.asString
                type == null || type in APP_RESOURCE_TYPES
            }
            .map { it.getAsJsonObject("request") }
            .filter { it.get("method").asString.uppercase() in HttpScriptStep.READ_METHODS }
            .filter { request -> request.get("url").asString.let { it.startsWith("http://") || it.startsWith("https://") } }
            .take(WiproberConfigParser.MAX_STEPS)
            .mapIndexed { index, request ->
                val url = request.get("url").asString
                HttpScriptStep(
                    name = "${index + 1} ${URI(url).path.takeLast(30).ifEmpty { "/" }}",
                    method = request.get("method").asString.uppercase(),
                    url = url,
                    headers = request.getAsJsonArray("headers")?.map { it.asJsonObject }
                        ?.filter { header ->
                            val key = header.get("name").asString.lowercase()
                            !key.startsWith(":") && key !in DROPPED_HEADERS
                        }
                        ?.associate { it.get("name").asString to it.get("value").asString }
                        .orEmpty()
                )
            }
        if (steps.isEmpty()) throw ConfigException("HAR has no GET requests of documents, XHR or fetch")
        return HttpScript(name = name, steps = steps)
    }
}

/** The small JSON path subset used by `extract`: `$.a.b`, `$.items[0].id`. */
object JsonPathLite {
    private val SEGMENT = Regex("""\.([A-Za-z0-9_\-]+)|\[(\d+)]""")

    fun isValid(path: String): Boolean =
        path.startsWith("$") && path.length > 1 && SEGMENT.replace(path.substring(1), "").isEmpty()

    fun read(json: String, path: String): String? {
        if (!isValid(path)) return null
        var node: JsonElement = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        SEGMENT.findAll(path.substring(1)).forEach { match ->
            val key = match.groups[1]?.value
            val index = match.groups[2]?.value?.toInt()
            node = when {
                key != null && node.isJsonObject -> node.asJsonObject.get(key) ?: return null
                index != null && node.isJsonArray && index < node.asJsonArray.size() -> node.asJsonArray[index]
                else -> return null
            }
        }
        return when {
            node.isJsonNull -> null
            node.isJsonPrimitive -> node.asString
            else -> node.toString()
        }
    }
}
