package com.example.wiprober

import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.concurrent.thread

/** Tiny HTTP/1.1 server for tests: one request per connection, then close. */
class MiniHttpServer(private val handler: (Request) -> Response) : Closeable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    data class Response(
        val status: Int = 200,
        val body: String = "",
        val headers: List<Pair<String, String>> = emptyList(),
        val chunked: Boolean = false
    )

    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val base: String get() = "http://127.0.0.1:$port"
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use(::serve) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val (method, path) = readLine(input).split(' ').let { it[0] to it[1] }
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = String(ByteArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }, StandardCharsets.UTF_8)
        val request = Request(method, path, headers, body)
        requests += request
        val response = handler(request)
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ${response.status} X\r\n")
        response.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (response.chunked) head.append("Transfer-Encoding: chunked\r\n") else head.append("Content-Length: ${bytes.size}\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (response.chunked) {
            bytes.toList().chunked(3).forEach { chunk ->
                out.write("${Integer.toHexString(chunk.size)}\r\n".toByteArray())
                out.write(chunk.toByteArray())
                out.write("\r\n".toByteArray())
            }
            out.write("0\r\n\r\n".toByteArray())
        } else {
            out.write(bytes)
        }
        out.flush()
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.toString()
    }

    override fun close() = server.close()
}
