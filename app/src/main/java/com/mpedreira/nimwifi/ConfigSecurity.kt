package com.mpedreira.nimwifi

import java.net.URI

/**
 * What counts as a credential in imported definitions, and how to keep credential values out of
 * the screen, the export and error messages.
 */
object Sensitive {
    /** Headers that carry credentials and must never stay in a definition. */
    private val CREDENTIAL_HEADERS = setOf(
        "authorization", "proxy-authorization", "x-api-key", "api-key", "apikey", "x-auth-token",
        "x-access-token", "x-amz-security-token", "ocp-apim-subscription-key"
    )
    /** Session/anti-forgery headers: dropped from a HAR (they belong to the recorded session). */
    private val SESSION_HEADERS = setOf("cookie", "set-cookie", "x-csrf-token", "x-xsrf-token", "x-requested-with-token")
    private val SENSITIVE_NAME = Regex("(?i)(token|secret|passw|pwd|session|auth|api[-_]?key|signature|credential|jwt|bearer)")
    private val SENSITIVE_QUERY = Regex("(?i)^(code|key|sig|sid|state|nonce|x-amz-[a-z-]+)$")

    fun isCredentialHeader(name: String): Boolean = name.lowercase() in CREDENTIAL_HEADERS
    fun isSessionHeader(name: String): Boolean = name.lowercase() in SESSION_HEADERS
    fun isSensitiveName(name: String): Boolean = SENSITIVE_NAME.containsMatchIn(name)
    fun isSensitiveQuery(name: String): Boolean = isSensitiveName(name) || SENSITIVE_QUERY.matches(name)

    /** A path segment that looks like a token or an id (long, mixed letters and digits). */
    fun looksLikeToken(segment: String): Boolean =
        segment.length >= 16 && segment.any(Char::isDigit) && segment.any(Char::isLetter) &&
            segment.all { it.isLetterOrDigit() || it in "-_.~=%" }

    /** Path for display/export: no query, token-like segments replaced by `…`. */
    fun displayPath(rawPath: String?): String {
        val path = rawPath?.substringBefore('?')?.ifEmpty { "/" } ?: "/"
        return path.split('/').joinToString("/") { if (looksLikeToken(it)) "…" else it }.take(120)
    }

    /** Credential reference in a definition: `${secret.NAME}`. */
    fun reference(name: String) = "\${secret.$name}"

    val CREDENTIAL_NAME = Regex("[A-Za-z0-9_]{1,40}")
    const val MAX_CREDENTIAL_LENGTH = 4_096
    const val MAX_CREDENTIALS = 50

    fun credentialName(vararg parts: String): String =
        parts.joinToString("_").replace(Regex("[^A-Za-z0-9_]"), "_").trim('_').take(40).ifEmpty { "credential" }
}

/** Replaces every known secret value in a text (error, host, path) with `…`. */
class Redactor(values: Collection<String>) {
    private val secrets = values.filter { it.length >= MIN_LENGTH }.distinct().sortedByDescending { it.length }

    fun redact(text: String?): String? = text?.let { value ->
        secrets.fold(ErrorClassifier.sanitize(value)) { acc, secret -> acc.replace(secret, "…") }
    }

    companion object {
        /** Shorter values (e.g. `1`, `en`) would redact ordinary text. */
        const val MIN_LENGTH = 4
        val NONE = Redactor(emptyList())
    }
}

/**
 * Checks a request after its variables were resolved: an extracted or secret value must not be
 * able to change the scheme, add URL credentials or inject header lines.
 */
object ResolvedRequestValidator {
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")

    fun check(url: URI, headers: Map<String, String>) {
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") invalid("resolved URL must be http or https")
        if (url.host.isNullOrEmpty()) invalid("resolved URL has no valid host")
        if (url.rawUserInfo != null) invalid("resolved URL must not contain credentials")
        if (url.port != -1 && url.port !in 1..65_535) invalid("resolved URL has an invalid port")
        headers.forEach { (name, value) ->
            if (!HEADER_NAME.matches(name)) invalid("invalid header name")
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) invalid("header $name contains a line break")
            if (value.length > 8_192) invalid("header $name is too long")
        }
    }

    private fun invalid(message: String): Nothing = throw MeasurementException(ErrorCodes.INVALID_TARGET, message)
}

/**
 * What an imported file would activate, shown before anything is saved. Holds names, hosts and
 * methods only: never credential values, header values or query strings.
 */
data class ImportPreview(
    val kind: String,
    val destinations: List<Destination>,
    /** `script · step` of every request that may write (allowWrite). */
    val writeSteps: List<String>,
    /** Credentials or session data removed from the file, e.g. `header Authorization (step 2)`. */
    val stripped: List<String>,
    /** Names of credentials the file provides (stored apart, never shown). */
    val credentialsProvided: List<String>,
    /** Credentials referenced by the definitions but not available yet. */
    val credentialsMissing: List<String>,
    val tests: Int?,
    val scripts: List<String>?,
    val throughput: Boolean,
    val guide: Boolean,
    val roaming: Boolean,
    /** Number of requirements in the file (report verdict); null without the section. */
    val requirements: Int? = null
) {
    data class Destination(val scheme: String, val host: String, val port: Int?, val methods: Set<String>, val cleartext: Boolean)

    val requiresWriteConfirmation: Boolean get() = writeSteps.isNotEmpty()

    companion object {
        const val KIND_HAR = "har"
        const val KIND_CONFIG = "config"

        fun of(config: WiproberConfig, knownCredentials: Set<String>): ImportPreview {
            val destinations = linkedMapOf<Triple<String, String, Int?>, MutableSet<String>>()
            fun add(url: String, method: String) {
                val scheme = url.substringBefore("://", "").lowercase()
                val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
                val host = authority.substringBeforeLast(':').takeIf { ':' in authority && !authority.endsWith(']') } ?: authority
                val port = authority.substringAfterLast(':', "").toIntOrNull()
                destinations.getOrPut(Triple(scheme, host, port)) { linkedSetOf() } += method
            }
            config.throughput?.let { t ->
                if (t.iperfEnabled && t.iperfHost.isNotBlank()) add("iperf3://${t.iperfHost}:${t.iperfPort}", "IPERF3")
                if (t.internetEnabled) {
                    add(t.internetDownloadUrl, "GET")
                    add(t.internetUploadUrl, "POST")
                }
            }
            config.tests.orEmpty().filter { it.enabled }.forEach { test ->
                when (test.type) {
                    NetworkTest.HTTP -> test.url?.let { add(it, "GET") }
                    else -> test.host?.let { add("${test.type}://$it" + (test.port?.let { p -> ":$p" } ?: ""), test.type.uppercase()) }
                }
            }
            config.scripts.orEmpty().forEach { script -> script.steps.forEach { add(it.url, it.method) } }
            config.roaming?.takeIf { it.enabled && it.host.isNotBlank() }?.let { add("tcp://${it.host}:${it.port}", "TCP") }

            val referenced = config.scripts.orEmpty().flatMap(HttpScript::credentialReferences).toSortedSet()
            val provided = config.credentials.orEmpty().keys
            return ImportPreview(
                kind = if (config.mergeScripts) KIND_HAR else KIND_CONFIG,
                destinations = destinations.map { (key, methods) ->
                    Destination(key.first, key.second, key.third, methods, cleartext = key.first == "http")
                },
                writeSteps = config.scripts.orEmpty().flatMap { script ->
                    script.steps.filter { it.isWrite }.map { "${script.displayAlias} · ${it.name}" }
                },
                stripped = config.stripped,
                credentialsProvided = provided.sorted(),
                credentialsMissing = (referenced - provided - knownCredentials).toList(),
                tests = config.tests?.size,
                scripts = config.scripts?.map(HttpScript::displayAlias),
                throughput = config.throughput != null,
                guide = config.guide != null,
                roaming = config.roaming != null,
                requirements = config.requirements?.let { r ->
                    r.throughput.orEmpty().size + r.networkTests.orEmpty().size + (if (r.roaming != null) 1 else 0)
                }
            )
        }
    }
}


/**
 * Mejora 14: success of an HTTP service check. Without an expected status only 2xx succeeds; a
 * 3xx is a visible [ErrorCodes.REDIRECT] (a redirect to a login page is not "the service works")
 * unless the profile allows redirects or expects that exact status.
 */
object HttpOutcomePolicy {
    fun problem(status: Int, expectStatus: Int?, allowRedirect: Boolean, location: String?): Pair<String, String>? = when {
        expectStatus != null -> if (status == expectStatus) null else ErrorCodes.HTTP_STATUS to "status $status, expected $expectStatus"
        status in 200..299 -> null
        status in 300..399 -> if (allowRedirect) null else ErrorCodes.REDIRECT to "redirect $status to ${location ?: "?"}"
        else -> ErrorCodes.HTTP_STATUS to "status $status"
    }

    /** Where a redirect goes, without query or token-like path segments. */
    fun location(request: java.net.URI, headers: List<Pair<String, String>>): String? {
        val raw = headers.lastOrNull { it.first.equals("Location", true) }?.second ?: return null
        val target = runCatching { request.resolve(raw.trim()) }.getOrNull() ?: return null
        return "${target.host ?: request.host}${Sensitive.displayPath(target.rawPath)}"
    }
}


/** Mejora 17: `${secret.NAME}` references outside scripts (test URLs, throughput URLs). */
object SecretRefs {
    private val REFERENCE = Regex("""\$\{secret\.([A-Za-z0-9_]+)\}""")

    fun names(text: String?): Set<String> = text?.let { REFERENCE.findAll(it).map { m -> m.groupValues[1] }.toSet() }.orEmpty()

    fun resolve(text: String, credentials: Map<String, String>): String = REFERENCE.replace(text) { match ->
        credentials[match.groupValues[1]]
            ?: throw MeasurementException(ErrorCodes.MISSING_CREDENTIAL, "credential ${match.groupValues[1]} not provided")
    }

    /**
     * Replaces the values of sensitive query parameters of [url] by references, storing them in
     * [credentials] under `<prefix>_<param>`; returns the URL to keep in the definition.
     */
    fun extract(url: String, prefix: String, credentials: MutableMap<String, String>, moved: MutableList<String>): String {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return url
        val params = query.split('&').filter(String::isNotEmpty).joinToString("&") { param ->
            val key = param.substringBefore('=')
            val value = param.substringAfter('=', "")
            if (value.isNotEmpty() && "\${" !in value && Sensitive.isSensitiveQuery(java.net.URLDecoder.decode(key, "UTF-8"))) {
                val name = Sensitive.credentialName(prefix, key)
                credentials[name] = java.net.URLDecoder.decode(value, "UTF-8")
                moved += "query $key ($prefix)"
                "$key=${Sensitive.reference(name)}"
            } else {
                param
            }
        }
        return url.substringBefore('?') + "?" + params
    }
}
