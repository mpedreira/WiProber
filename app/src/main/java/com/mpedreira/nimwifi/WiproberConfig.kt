package com.mpedreira.nimwifi

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
    val mergeScripts: Boolean = false,
    /**
     * Credential values by name, referenced as `${secret.NAME}`. Stored apart from the
     * definitions (never shown, exported or backed up); merged by name on import.
     */
    val credentials: Map<String, String>? = null,
    /** What was removed or moved out of the definitions on import (names only, no values). */
    val stripped: List<String> = emptyList()
) {
    /** Never print credential values (logs, crash reports). */
    override fun toString(): String =
        "WiproberConfig(tests=${tests?.size}, scripts=${scripts?.map { it.name }}, credentials=${credentials?.keys})"
}

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
    val alias: String? = null,
    /** Mejora 14: a 3xx counts as success only when allowed (default: it is a visible `redirect`). */
    val allowRedirect: Boolean = false
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
    val steps: List<HttpScriptStep>,
    /** Exported alias; the stable identifier is [name]. */
    val alias: String? = null
) {
    val displayAlias: String get() = alias?.takeIf { it.isNotBlank() } ?: name

    /** Names used as `${secret.NAME}` anywhere in the steps. */
    fun credentialReferences(): Set<String> = steps.flatMap { step ->
        (listOf(step.url, step.body.orEmpty()) + step.headers.values).flatMap { text ->
            SECRET_REFERENCE.findAll(text).map { it.groupValues[1] }.toList()
        }
    }.toSet()

    companion object {
        val SECRET_REFERENCE = Regex("""\$\{secret\.([A-Za-z0-9_]+)\}""")
    }
}

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
    val extract: Map<String, String> = emptyMap(),
    /** Mejora 14: a 3xx counts as success only when allowed or expected. */
    val allowRedirect: Boolean = false
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
    const val MAX_HEADERS = 30
    private val NAME = Regex("[A-Za-z0-9 _.-]{1,40}")

    /** Parses either a `wiprober-config` JSON or a browser HAR (turned into one script). */
    fun parse(text: String, fileName: String): WiproberConfig {
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            throw ConfigException("Not a JSON file: ${e.message}")
        }
        return if (root.has("log") && root["log"].isJsonObject) {
            val imported = HarImporter.import(root, scriptNameFrom(fileName))
            WiproberConfig(scripts = listOf(imported.script), mergeScripts = true, stripped = imported.stripped)
        } else {
            parseConfig(root)
        }
    }

    /**
     * A configuration ZIP: `wiprober-config.json` (required, may carry `_comment` keys) plus
     * optional browser `.har` recordings, each added as one more script. README and other text
     * files are ignored. [entries] maps root file names to their text.
     */
    fun parseZip(entries: Map<String, String>): WiproberConfig {
        val configText = entries.entries.firstOrNull { it.key.equals(CONFIG_FILE_NAME, ignoreCase = true) }?.value
            ?: throw ConfigException("The ZIP has no $CONFIG_FILE_NAME")
        val config = parse(configText, CONFIG_FILE_NAME)
        val hars = entries.filterKeys { it.endsWith(".har", ignoreCase = true) }.toSortedMap()
            .map { (name, text) -> HarImporter.import(parseRoot(text), scriptNameFrom(name)) }
        if (hars.isEmpty()) return config
        val scripts = config.scripts.orEmpty() + hars.map { it.script }
        if (scripts.size > MAX_SCRIPTS) throw ConfigException("At most $MAX_SCRIPTS scripts")
        if (scripts.map { it.name }.toSet().size != scripts.size) throw ConfigException("Script names must be unique (config and HAR files)")
        return config.copy(
            scripts = scripts,
            // Without a scripts section the HARs are added to the device's scripts, as a lone HAR is.
            mergeScripts = config.scripts == null,
            stripped = config.stripped + hars.flatMap { har -> har.stripped.map { "${har.script.name}: $it" } }
        )
    }

    const val CONFIG_FILE_NAME = "wiprober-config.json"

    private fun parseRoot(text: String): JsonObject = try {
        JsonParser.parseString(text).asJsonObject
    } catch (e: Exception) {
        throw ConfigException("Not a JSON file: ${e.message}")
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
        val credentials = parseCredentials(root.obj("credentials"))
        val split = scripts?.let { CredentialSplitter.split(it, credentials.orEmpty()) }
        val tests = root.getAsJsonArray("tests")?.mapIndexed { i, test -> parseTest(test.asJsonObject, "test ${i + 1}") }
        if ((tests?.size ?: 0) > MAX_TESTS) throw ConfigException("At most $MAX_TESTS tests")
        if (tests != null && tests.map { it.name }.toSet().size != tests.size) {
            throw ConfigException("Test names must be unique")
        }
        return WiproberConfig(
            throughput = root.obj("throughput")?.let(::parseThroughput),
            guide = root.obj("guide")?.let(::parseGuide),
            tests = tests,
            scripts = split?.scripts,
            roaming = root.obj("roaming")?.let(::parseRoaming),
            credentials = split?.credentials ?: credentials,
            stripped = split?.moved.orEmpty()
        )
    }

    private fun parseCredentials(json: JsonObject?): Map<String, String>? {
        json ?: return null
        if (json.keySet().count { !it.startsWith("_") } > Sensitive.MAX_CREDENTIALS) throw ConfigException("At most ${Sensitive.MAX_CREDENTIALS} credentials")
        return json.entrySet().filterNot { it.key.startsWith("_") }.associate { (name, value) ->
            if (!Sensitive.CREDENTIAL_NAME.matches(name)) throw ConfigException("Credential name \"$name\": use letters, digits and _ (max 40)")
            if (!value.isJsonPrimitive) throw ConfigException("Credential \"$name\" must be a string")
            val text = value.asString
            // The value itself is never quoted in an error.
            if (text.length > Sensitive.MAX_CREDENTIAL_LENGTH) throw ConfigException("Credential \"$name\" is too long")
            if (text.any { it == '\r' || it == '\n' }) throw ConfigException("Credential \"$name\" contains a line break")
            name to text
        }
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
            alias = json.string("alias")?.trim()?.take(60),
            allowRedirect = json.bool("allowRedirect") ?: false
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
            internetDurationSeconds = internet?.int("durationSeconds") ?: d.internetDurationSeconds,
            expectedSsid = json.string("expectedSsid") ?: d.expectedSsid
        ).sanitized()
    }

    private fun parseGuide(json: JsonObject): SurveyGuideSettings {
        val d = SurveyGuideSettings()
        return SurveyGuideSettings(
            enabled = json.bool("enabled") ?: d.enabled,
            spacingMeters = json.double("spacingMeters") ?: d.spacingMeters,
            coverageRadiusMeters = json.double("coverageRadiusMeters") ?: d.coverageRadiusMeters,
            ssidFilter = json.string("ssid") ?: d.ssidFilter,
            profile = json.string("profile") ?: d.profile
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
            steps = steps,
            alias = json.string("alias")?.trim()?.take(60)
        )
    }

    private fun parseStep(json: JsonObject, where: String): HttpScriptStep {
        val headers = json.stringMap("headers")
        if (headers.size > MAX_HEADERS) throw ConfigException("$where: at most $MAX_HEADERS headers")
        val url = json.string("url") ?: throw ConfigException("$where has no \"url\"")
        checkUrl(url, where)
        val step = HttpScriptStep(
            name = json.string("name") ?: where.substringAfterLast(' ').let { "step $it" },
            method = (json.string("method") ?: "GET").uppercase(),
            url = url,
            headers = headers,
            body = json.string("body"),
            allowWrite = json.bool("allowWrite") ?: false,
            expectStatus = json.int("expectStatus"),
            expectContains = json.string("expectContains"),
            extract = json.stringMap("extract"),
            allowRedirect = json.bool("allowRedirect") ?: false
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
            val uri = runCatching { URI(url).also { requireNotNull(it.host) } }
                .getOrElse { throw ConfigException("$where: invalid URL") }
            if (uri.rawUserInfo != null) throw ConfigException("$where: put credentials in \"credentials\", not in the URL")
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
    /** Keys starting with `_` are comments (`"_comment": "…"`), never values. */
    private fun JsonObject.stringMap(name: String): Map<String, String> =
        obj(name)?.entrySet()?.filterNot { it.key.startsWith("_") }?.associate { (key, value) -> key to value.asString }.orEmpty()
}

/**
 * Moves literal credentials out of imported script definitions: values of credential headers
 * (`Authorization`, `X-API-Key`, …) and of variables with sensitive names become stored
 * credentials, and the definition keeps only a `${secret.NAME}` reference.
 */
object CredentialSplitter {
    class Result(val scripts: List<HttpScript>, val credentials: Map<String, String>, val moved: List<String>)

    fun split(scripts: List<HttpScript>, provided: Map<String, String>): Result {
        val credentials = provided.toMutableMap()
        val moved = mutableListOf<String>()
        fun store(name: String, value: String): String {
            val existing = credentials[name]
            if (existing != null && existing != value) throw ConfigException("Credential \"$name\" is defined twice with different values")
            credentials[name] = value
            return Sensitive.reference(name)
        }
        val result = scripts.map { script ->
            val secretVariables = script.variables.filterKeys(Sensitive::isSensitiveName)
            secretVariables.keys.forEach { name ->
                store(Sensitive.credentialName(name), secretVariables.getValue(name))
                moved += "variable $name (${script.name})"
            }
            fun moveVariables(text: String) = secretVariables.keys.fold(text) { acc, name ->
                acc.replace("\${$name}", Sensitive.reference(Sensitive.credentialName(name)))
            }
            script.copy(
                variables = script.variables - secretVariables.keys,
                steps = script.steps.map { step ->
                    step.copy(
                        url = moveVariables(step.url),
                        body = step.body?.let(::moveVariables),
                        headers = step.headers.mapValues { (header, value) ->
                            val resolved = moveVariables(value)
                            if (Sensitive.isCredentialHeader(header) && "\${" !in resolved) {
                                moved += "header $header (${script.name} · ${step.name})"
                                store(Sensitive.credentialName(script.name, header), resolved)
                            } else {
                                resolved
                            }
                        }
                    )
                }
            )
        }
        if (credentials.size > Sensitive.MAX_CREDENTIALS) throw ConfigException("At most ${Sensitive.MAX_CREDENTIALS} credentials")
        return Result(result, credentials, moved)
    }
}

/**
 * Turns a browser HAR recording into a script: only the app's own requests (documents, XHR and
 * fetch when the browser recorded the resource type), only reads, at most [WiproberConfigParser.MAX_STEPS].
 *
 * Credentials are stripped by default and never stored: cookies and session headers are dropped
 * (the replay keeps its own cookie jar), credential headers and sensitive query parameters are
 * replaced by `${secret.NAME}` references the user must provide separately, and URL user info is
 * removed. Response bodies and request bodies are never imported.
 */
object HarImporter {
    private val APP_RESOURCE_TYPES = setOf("document", "xhr", "fetch")
    private val DROPPED_HEADERS = setOf(
        "host", "connection", "content-length", "accept-encoding", "transfer-encoding", "keep-alive", "te", "upgrade"
    )

    class Imported(val script: HttpScript, val stripped: List<String>)

    fun import(har: JsonObject, name: String): Imported {
        val entries = har.getAsJsonObject("log").getAsJsonArray("entries")
            ?: throw ConfigException("HAR has no entries")
        val stripped = linkedSetOf<String>()
        val steps = entries.map { it.asJsonObject }
            .filter { entry ->
                val type = entry.get("_resourceType")?.takeUnless(JsonElement::isJsonNull)?.asString
                type == null || type in APP_RESOURCE_TYPES
            }
            .mapNotNull { it.getAsJsonObject("request") }
            .filter { it.get("method")?.asString?.uppercase() in HttpScriptStep.READ_METHODS }
            .filter { request -> request.get("url")?.asString.orEmpty().let { it.startsWith("http://") || it.startsWith("https://") } }
            .take(WiproberConfigParser.MAX_STEPS)
            .mapIndexed { index, request ->
                val stepName = "${index + 1}"
                val url = sanitizeUrl(request.get("url").asString, stepName, stripped)
                val headers = request.getAsJsonArray("headers")?.map { it.asJsonObject }.orEmpty()
                    .mapNotNull { header ->
                        val key = header.get("name")?.asString ?: return@mapNotNull null
                        val lower = key.lowercase()
                        when {
                            lower.startsWith(":") || lower in DROPPED_HEADERS -> null
                            Sensitive.isSessionHeader(key) -> { stripped += "header $key"; null }
                            Sensitive.isCredentialHeader(key) -> {
                                stripped += "header $key (step $stepName)"
                                key to Sensitive.reference(Sensitive.credentialName("har", key))
                            }
                            Sensitive.isSensitiveName(key) -> { stripped += "header $key (step $stepName)"; null }
                            else -> key to header.get("value")?.asString.orEmpty()
                        }
                    }
                    .take(WiproberConfigParser.MAX_HEADERS)
                    .toMap()
                if (request.getAsJsonArray("cookies")?.size() ?: 0 > 0) stripped += "cookies"
                val uri = URI(url.replace(Regex("""\$\{[^\}]*\}"""), "x"))
                HttpScriptStep(
                    name = "$stepName ${uri.host}${Sensitive.displayPath(uri.rawPath).takeLast(30)}".take(60),
                    method = request.get("method").asString.uppercase(),
                    url = url,
                    headers = headers
                )
            }
        if (steps.isEmpty()) throw ConfigException("HAR has no GET requests of documents, XHR or fetch")
        return Imported(HttpScript(name = name, steps = steps), stripped.toList())
    }

    /** Drops URL user info and replaces sensitive query values by credential references. */
    private fun sanitizeUrl(url: String, step: String, stripped: MutableSet<String>): String {
        val scheme = url.substringBefore("://")
        var rest = url.substringAfter("://")
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        if ('@' in authority) {
            stripped += "URL credentials (step $step)"
            rest = authority.substringAfterLast('@') + rest.substring(authorityEnd)
        }
        rest = rest.substringBefore('#')
        val query = rest.substringAfter('?', "")
        if (query.isEmpty()) return "$scheme://$rest"
        val params = query.split('&').filter(String::isNotEmpty).joinToString("&") { param ->
            val key = param.substringBefore('=')
            if (Sensitive.isSensitiveQuery(java.net.URLDecoder.decode(key, "UTF-8"))) {
                stripped += "query $key (step $step)"
                "$key=${Sensitive.reference(Sensitive.credentialName("har", key))}"
            } else {
                param
            }
        }
        return "$scheme://${rest.substringBefore('?')}?$params"
    }
}

/** The small JSON path subset used by `extract`: `$.a.b`, `$.items[0].id`. */
object JsonPathLite {
    private val SEGMENT = Regex("""\.([A-Za-z0-9_\-]+)|\[(\d+)\]""")

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
