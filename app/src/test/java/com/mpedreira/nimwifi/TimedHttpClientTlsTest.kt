package com.mpedreira.nimwifi

import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * HTTPS through the raw-socket client: a certificate signed by a trusted (here: test, in the app a
 * corporate/user-installed) CA is accepted for its host name and rejected for any other name.
 */
class TimedHttpClientTlsTest {
    companion object {
        @get:ClassRule @JvmStatic val temp = TemporaryFolder()
        private lateinit var keyStore: KeyStore
        private const val PASSWORD = "changeit"

        /** Self-signed certificate for `portal.example.com`, generated with the JDK's keytool. */
        @BeforeClass @JvmStatic
        fun createCertificate() {
            val keytool = File(System.getProperty("java.home"), "bin/keytool")
            assumeTrue("keytool not available", keytool.canExecute())
            val file = File(temp.root, "server.p12")
            val process = ProcessBuilder(
                keytool.path, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=portal.example.com", "-ext", "SAN=dns:portal.example.com", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", file.path, "-storepass", PASSWORD, "-keypass", PASSWORD
            ).redirectErrorStream(true).start()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            keyStore = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
        }
    }

    private fun withServer(block: (port: Int, client: (String) -> TimedHttpClient) -> Unit) {
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD.toCharArray()) }.keyManagers, null, null)
        }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", keyStore.getCertificate("server"))
        }
        val clientContext = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }.trustManagers, null)
        }
        (serverContext.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            socket.use {
                                val input = it.getInputStream().bufferedReader()
                                while (input.readLine()?.isNotEmpty() == true) Unit
                                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                            }
                        }
                    }
                }
            }
            block(server.localPort) { _ ->
                TimedHttpClient(
                    resolve = { arrayOf(InetAddress.getLoopbackAddress()) },
                    sslSocketFactory = clientContext.socketFactory,
                    hostnameVerifier = { host, session ->
                        val cert = session.peerCertificates.first() as java.security.cert.X509Certificate
                        cert.subjectAlternativeNames.orEmpty().any { it[1] == host }
                    }
                )
            }
        }
    }

    private fun step(host: String, port: Int) = HttpScript("s", steps = listOf(HttpScriptStep("a", url = "https://$host:$port/")))

    @Test
    fun validHttpsIsTimedPhaseByPhase() = withServer { port, client ->
        val result = ScriptRunner(client("portal.example.com")).run(step("portal.example.com", port)).single()
        assertTrue(result.error ?: "", result.ok)
        assertNotNull(result.dnsMs)
        assertNotNull(result.connectMs)
        assertNotNull(result.tlsMs)
        assertNotNull(result.ttfbMs)
        assertEquals(2L, result.bytes)
    }

    @Test
    fun aCertificateForAnotherHostNameIsRejected() = withServer { port, client ->
        val result = ScriptRunner(client("intranet.example.com")).run(step("intranet.example.com", port)).single()
        assertEquals(ErrorCodes.HOSTNAME_MISMATCH, result.errorCode)
        assertEquals(null, result.ttfbMs)
    }

    @Test
    fun anUntrustedCertificateFailsTheHandshake() = withServer { port, _ ->
        // Platform trust store only: the test CA is not in it.
        val result = ScriptRunner(TimedHttpClient(resolve = { arrayOf(InetAddress.getLoopbackAddress()) }))
            .run(step("portal.example.com", port)).single()
        assertEquals(ErrorCodes.TLS_FAILED, result.errorCode)
    }
}
