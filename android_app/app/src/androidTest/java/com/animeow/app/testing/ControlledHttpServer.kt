package com.animeow.app.testing

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal data class TestHttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
internal data class TestHttpResponse(
    val status: Int = 200,
    val body: String = "",
    val headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
)

/** Loopback-only HTTP fixture, including delayed failures and redirect responses. */
internal class ControlledHttpServer(private val respond: (TestHttpRequest) -> TestHttpResponse) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val requests = LinkedBlockingQueue<TestHttpRequest>()
    val baseUrl = "http://127.0.0.1:${server.localPort}"

    init {
        executor.submit {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                executor.submit { handle(socket) }
            }
        }
    }

    fun awaitRequest(): TestHttpRequest = checkNotNull(requests.poll(10, TimeUnit.SECONDS)) { "No local HTTP request received" }

    private fun handle(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 10_000
                val input = BufferedInputStream(socket.getInputStream())
                val start = input.line().split(' ')
                val headers = buildMap {
                    while (true) {
                        val line = input.line()
                        if (line.isEmpty()) break
                        put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
                    }
                }
                val length = (headers["content-length"]?.toIntOrNull() ?: 0).also { require(it in 0..1_000_000) }
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(body, offset, length - offset)
                    check(read > 0)
                    offset += read
                }
                val request = TestHttpRequest(start[0], start[1], headers, body.toString(Charsets.UTF_8))
                requests.put(request)
                val response = respond(request)
                val bytes = response.body.toByteArray()
                socket.getOutputStream().use { output ->
                    val responseHeaders = response.headers + mapOf("Content-Length" to bytes.size.toString(), "Connection" to "close")
                    output.write(("HTTP/1.1 ${response.status} Test\r\n" + responseHeaders.entries.joinToString("") {
                        "${it.key}: ${it.value}\r\n"
                    } + "\r\n").toByteArray())
                    if (request.method != "HEAD") output.write(bytes)
                }
            }
        } finally {
            sockets.remove(socket)
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    private fun BufferedInputStream.line(): String = buildString {
        while (true) {
            val byte = read()
            check(byte >= 0) { "Unexpected end of HTTP request" }
            if (byte == 10) break
            if (byte != 13) append(byte.toChar())
            check(length < 8192)
        }
    }
}
