package com.animeow.app.testing

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

internal data class RecordedRequest(val path: String, val body: ByteArray)

/** One-request loopback fixture. No test data leaves the device. */
internal class LocalHttpServer(private val respond: (RecordedRequest) -> ByteArray) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var connection: Socket? = null
    val request = CompletableFuture<RecordedRequest>()
    val baseUrl = "http://127.0.0.1:${server.localPort}"

    init {
        executor.submit {
            try {
                server.accept().use { socket ->
                    connection = socket
                    socket.soTimeout = 30_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val path = input.line().split(' ')[1]
                    var length = 0
                    while (true) {
                        val line = input.line()
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    require(length in 0..(30 * 1024 * 1024))
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(body, offset, length - offset)
                        check(read > 0)
                        offset += read
                    }
                    val recorded = RecordedRequest(path, body)
                    request.complete(recorded)
                    val response = respond(recorded)
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(response)
                    }
                }
            } catch (error: Exception) {
                request.completeExceptionally(error)
            }
        }
    }

    override fun close() {
        connection?.close()
        server.close()
        executor.shutdownNow()
    }

    private fun BufferedInputStream.line(): String = buildString {
        while (true) {
            val byte = read()
            check(byte >= 0) { "Unexpected end of HTTP headers" }
            if (byte == 10) break
            if (byte != 13) append(byte.toChar())
            check(length < 8192)
        }
    }
}
