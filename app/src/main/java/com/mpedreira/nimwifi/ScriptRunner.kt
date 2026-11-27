package com.mpedreira.nimwifi

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToLong

/** One replayed script at a point; connection fields as in [ThroughputTestResult]. */
@JsonModel
data class ScriptRun(
    val name: String,
    val measuredAt: Long,
    val alias: String? = null,
    /** Mejora 9: digest of the script definition, so a changed script is another series. */
    val definitionHash: String? = null,
    /** Steps the script defines; with [steps] it tells how far a run got (0 when skipped). */
    val stepsTotal: Int? = null,
    /** Why no step ran, or why the run stopped early (skipped without Wi-Fi, cancelled). */
    val errorCode: String? = null,
    val ssid: String? = null,
    val bssidStart: String? = null,
    val bssidEnd: String? = null,
    val rssi: Int? = null,
    val steps: List<ScriptStepResult> = emptyList(),
    val error: String? = null
)

/**
 * Timing breakdown of one request on a fresh connection, in ms: DNS lookup, TCP handshake, TLS
 * handshake (https only), time to first byte after sending, body download and total.
 * Null values were not reached; [error] says why. [ok] = status and expectations met.
 */
@JsonModel
data class ScriptStepResult(
    val name: String,
    val method: String,
    val host: String?,
    val path: String?,
    val status: Int? = null,
    val ok: Boolean = false,
    val dnsMs: Double? = null,
    val connectMs: Double? = null,
    val tlsMs: Double? = null,
    val ttfbMs: Double? = null,
    val downloadMs: Double? = null,
    val totalMs: Double? = null,
    val bytes: Long? = null,
    val error: String? = null,
    /** Structured cause ([ErrorCodes]); [error] never repeats secrets. */
    val errorCode: String? = null,
    /** Mejora 14: where a 3xx pointed (host and path, no query). */
    val redirectLocation: String? = null
)

/**
 * Minimal HTTP/1.1 client that times each phase separately (HttpURLConnection cannot). Every
 * request opens its own connection (`Connection: close`), so DNS/TCP/TLS are measured each time.
 * [socketFactory] and [resolve] bind it to the Wi-Fi network on Android; TLS uses the platform
 * trust store (system and, per the app's network security config, user-installed CAs).
 */
class TimedHttpClient(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 15_000,
    /** Tracks sockets so cancelling or a deadline closes them. */
    private val control: MeasurementControl = MeasurementControl(),
    /**
     * Android's cleartext policy (`NetworkSecurityPolicy.isCleartextTrafficPermitted(host)`),
     * applied to this raw-socket client exactly as the platform applies it to HttpURLConnection.
     */
    private val cleartextPermitted: (String) -> Boolean = { true },
    private val maxBodyBytes: Long = MAX_BODY_BYTES
) {
    class Response(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray, val bytes: Long)

    /** Filled in as phases complete, so a failure still reports how far it got. */
    class Timing {
        var dnsMs: Double? = null
        var connectMs: Double? = null
        var tlsMs: Double? = null
        var ttfbMs: Double? = null
        var downloadMs: Double? = null
    }

    fun execute(method: String, url: URI, headers: Map<String, String>, body: ByteArray?, timing: Timing): Response {
        val https = url.scheme.equals("https", ignoreCase = true)
        val host = requireNotNull(url.host) { "URL without host" }
        val port = if (url.port > 0) url.port else if (https) 443 else 80
        if (!https && !cleartextPermitted(host)) {
            throw MeasurementException(ErrorCodes.CLEARTEXT_NOT_PERMITTED, "cleartext HTTP is not permitted by the network security policy")
        }

        var mark = System.nanoTime()
        val address = resolve(host).first()
        timing.dnsMs = elapsed(mark)

        mark = System.nanoTime()
        val plain = control.track(socketFactory.createSocket())
        var socket: Socket = plain
        try {
            plain.soTimeout = readTimeoutMillis
            plain.connect(InetSocketAddress(address, port), connectTimeoutMillis)
            timing.connectMs = elapsed(mark)

            if (https) {
                mark = System.nanoTime()
                val tls = sslSocketFactory.createSocket(plain, host, port, true) as SSLSocket
                tls.startHandshake()
                if (!hostnameVerifier.verify(host, tls.session)) {
                    throw MeasurementException(ErrorCodes.HOSTNAME_MISMATCH, "certificate does not match the host name", SSLPeerUnverifiedException(host))
                }
                timing.tlsMs = elapsed(mark)
                socket = tls
            }

            val output = socket.getOutputStream()
            output.write(requestHead(method, url, host, port, https, headers, body).toByteArray(StandardCharsets.ISO_8859_1))
            body?.let(output::write)
            output.flush()
            mark = System.nanoTime()

            val input = socket.getInputStream().buffered()
            val first = input.read()
            if (first < 0) throw IOException("connection closed without a response")
            timing.ttfbMs = elapsed(mark)

            mark = System.nanoTime()
            val statusLine = (first.toChar() + readLine(input))
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("invalid status line: ${statusLine.take(60)}")
            val responseHeaders = generateSequence { readLine(input).takeIf(String::isNotEmpty) }
                .map { it.substringBefore(':').trim() to it.substringAfter(':', "").trim() }
                .take(MAX_HEADERS + 1)
                .toList()
            if (responseHeaders.size > MAX_HEADERS) throw MeasurementException(ErrorCodes.RESPONSE_TOO_LARGE, "more than $MAX_HEADERS response headers")
            val (content, bytes) = readBody(input, method, status, responseHeaders)
            timing.downloadMs = elapsed(mark)
            return Response(status, responseHeaders, content, bytes)
        } finally {
            control.untrack(plain)
            runCatching { socket.close() }
        }
    }

    private fun requestHead(
        method: String, url: URI, host: String, port: Int, https: Boolean,
        headers: Map<String, String>, body: ByteArray?
    ): String = buildString {
        val target = (url.rawPath?.ifEmpty { "/" } ?: "/") + (url.rawQuery?.let { "?$it" } ?: "")
        append("$method $target HTTP/1.1\r\n")
        append("Host: ").append(if (port == (if (https) 443 else 80)) host else "$host:$port").append("\r\n")
        val custom = headers.filterKeys { it.lowercase() !in MANAGED_HEADERS }
        if (custom.keys.none { it.equals("User-Agent", true) }) append("User-Agent: nimWifi\r\n")
        custom.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
        append("Accept-Encoding: identity\r\n")
        append("Connection: close\r\n")
        if (body != null) append("Content-Length: ${body.size}\r\n")
        append("\r\n")
    }

    /** Returns the body (kept up to [MAX_KEPT_BODY] for checks/extraction) and its full size. */
    private fun readBody(
        input: InputStream, method: String, status: Int, headers: List<Pair<String, String>>
    ): Pair<ByteArray, Long> {
        if (method == "HEAD" || status in 100..199 || status == 204 || status == 304) return ByteArray(0) to 0L
        fun header(name: String) = headers.lastOrNull { it.first.equals(name, true) }?.second
        val kept = ByteArrayOutputStream()
        var total = 0L
        val buffer = ByteArray(16 * 1024)
        fun consume(limit: Long?) {
            var left = limit ?: Long.MAX_VALUE
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (read < 0) {
                    if (limit != null) throw IOException("response body truncated")
                    break
                }
                if (kept.size() < MAX_KEPT_BODY) kept.write(buffer, 0, minOf(read, MAX_KEPT_BODY - kept.size()))
                total += read
                if (total > maxBodyBytes) throw MeasurementException(ErrorCodes.RESPONSE_TOO_LARGE, "response body over $maxBodyBytes bytes")
                left -= read
            }
        }
        when {
            header("Transfer-Encoding")?.contains("chunked", true) == true -> {
                while (true) {
                    val size = readLine(input).substringBefore(';').trim().toLongOrNull(16)
                        ?: throw IOException("invalid chunk size")
                    if (size == 0L) {
                        while (readLine(input).isNotEmpty()) Unit // trailers
                        break
                    }
                    consume(size)
                    readLine(input)
                }
            }
            header("Content-Length") != null -> consume(header("Content-Length")!!.toLongOrNull()
                ?: throw IOException("invalid Content-Length"))
            else -> consume(null)
        }
        return kept.toByteArray() to total
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) {
                if (line.isEmpty()) throw IOException("connection closed")
                break
            }
            if (c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
            if (line.length > MAX_LINE) throw IOException("response header line too long")
        }
        return line.toString()
    }

    private fun elapsed(start: Long): Double = ((System.nanoTime() - start) / 100_000.0).roundToLong() / 10.0

    companion object {
        private val MANAGED_HEADERS = setOf("host", "connection", "content-length", "accept-encoding", "transfer-encoding")
        private const val MAX_KEPT_BODY = 1_000_000
        private const val MAX_LINE = 16_384
        private const val MAX_HEADERS = 100
        /** A script step reads at most this much, then fails with [ErrorCodes.RESPONSE_TOO_LARGE]. */
        const val MAX_BODY_BYTES = 50L * 1024 * 1024
    }
}

/**
 * Replays an [HttpScript]: substitutes `${variables}` and `${secret.NAME}` credentials, keeps
 * cookies between steps (host-only, bounded), checks the expected status/text and extracts values
 * for later steps. A failed step does not stop the script; steps that need a value that was never
 * extracted, or a credential that was never provided, fail with that reason.
 *
 * Redirects are not followed (a 3xx is the step's status). Credential and extracted values never
 * reach the result: hosts and errors are redacted and the exported path comes from the
 * definition (no query, token-like segments hidden).
 */
class ScriptRunner(private val http: TimedHttpClient, private val credentials: Map<String, String> = emptyMap()) {
    fun run(
        script: HttpScript,
        onStep: (index: Int, step: HttpScriptStep) -> Unit = { _, _ -> },
        onResult: (ScriptStepResult) -> Unit = {},
        /** Wraps each step (deadline, budget); the default runs it as it is. */
        guard: (HttpScriptStep, () -> ScriptStepResult) -> ScriptStepResult = { _, run -> run() }
    ): List<ScriptStepResult> {
        val variables = script.variables.toMutableMap()
        val cookies = mutableMapOf<String, MutableMap<String, String>>()
        val secrets = credentials.values.toMutableSet()
        return script.steps.mapIndexed { index, step ->
            onStep(index, step)
            guard(step) { runStep(step, variables, cookies, secrets) }.also(onResult)
        }
    }

    private fun runStep(
        step: HttpScriptStep,
        variables: MutableMap<String, String>,
        cookies: MutableMap<String, MutableMap<String, String>>,
        secrets: MutableSet<String>
    ): ScriptStepResult {
        val timing = TimedHttpClient.Timing()
        val templatePath = runCatching { URI(step.url.replace(PLACEHOLDER, "x")).rawPath }.getOrNull()
        var result = ScriptStepResult(name = step.name, method = step.method, host = null, path = Sensitive.displayPath(templatePath))
        val start = System.nanoTime()
        fun total() = ((System.nanoTime() - start) / 100_000.0).roundToLong() / 10.0
        fun withTiming(r: ScriptStepResult) = r.copy(
            dnsMs = timing.dnsMs, connectMs = timing.connectMs, tlsMs = timing.tlsMs,
            ttfbMs = timing.ttfbMs, downloadMs = timing.downloadMs
        )
        val redactor = { Redactor(secrets) }
        try {
            if (step.isWrite && !step.allowWrite) {
                throw MeasurementException(ErrorCodes.WRITE_NOT_ALLOWED, "${step.method} not allowed (allowWrite is false)")
            }
            val url = try {
                URI(substitute(step.url, variables))
            } catch (e: java.net.URISyntaxException) {
                throw MeasurementException(ErrorCodes.INVALID_TARGET, "resolved URL is not valid")
            }
            result = result.copy(host = redactor().redact(url.host))
            val headers = step.headers.mapValues { (_, value) -> substitute(value, variables) }.toMutableMap()
            ResolvedRequestValidator.check(url, headers)
            cookies[url.host]?.takeIf { it.isNotEmpty() }?.let { jar ->
                headers["Cookie"] = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            val body = step.body?.let { substitute(it, variables).toByteArray(StandardCharsets.UTF_8) }

            val response = http.execute(step.method, url, headers, body, timing)
            response.headers.filter { it.first.equals("Set-Cookie", true) }.forEach { (_, value) ->
                val pair = value.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val cookie = pair.substringAfter('=', "").trim()
                val jar = cookies.getOrPut(url.host) { linkedMapOf() }
                if (name.isNotEmpty() && name.length <= 128 && cookie.length <= MAX_COOKIE_LENGTH && (name in jar || jar.size < MAX_COOKIES)) {
                    jar[name] = cookie
                    secrets += cookie
                }
            }
            val text = String(response.body, StandardCharsets.UTF_8)
            val problems = mutableListOf<Pair<String, String>>()
            val location = HttpOutcomePolicy.location(url, response.headers)
            HttpOutcomePolicy.problem(response.status, step.expectStatus, step.allowRedirect, location)?.let { problems += it }
            result = result.copy(redirectLocation = location?.let { redactor().redact(it) })
            step.expectContains?.let { if (it !in text) problems += ErrorCodes.EXPECTATION_FAILED to "expected text not found" }
            step.extract.forEach { (name, path) ->
                val value = JsonPathLite.read(text, path)
                if (value == null) {
                    problems += ErrorCodes.EXTRACT_FAILED to "cannot extract $name from $path"
                } else {
                    variables[name] = value
                    secrets += value
                }
            }
            return withTiming(result).copy(
                status = response.status,
                ok = problems.isEmpty(),
                totalMs = total(),
                bytes = response.bytes,
                error = problems.joinToString("; ") { it.second }.ifEmpty { null },
                errorCode = problems.firstOrNull()?.first
            )
        } catch (e: MeasurementCancelled) {
            throw e
        } catch (e: Exception) {
            return withTiming(result).copy(
                ok = false, totalMs = total(),
                error = redactor().redact(ErrorClassifier.detail(e)), errorCode = ErrorClassifier.code(e)
            )
        }
    }

    private fun substitute(text: String, variables: Map<String, String>): String =
        PLACEHOLDER.replace(text) { match ->
            val name = match.groupValues[2]
            if (match.groupValues[1].isNotEmpty()) {
                credentials[name] ?: throw MeasurementException(ErrorCodes.MISSING_CREDENTIAL, "credential $name not provided")
            } else {
                variables[name] ?: throw MeasurementException(ErrorCodes.UNKNOWN_VARIABLE, "unknown variable $name")
            }
        }

    private companion object {
        val PLACEHOLDER = Regex("""\$\{(secret\.)?([A-Za-z0-9_]+)\}""")
        const val MAX_COOKIES = 30
        const val MAX_COOKIE_LENGTH = 4_096
    }
}
