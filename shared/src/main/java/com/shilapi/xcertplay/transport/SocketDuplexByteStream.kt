package com.shilapi.xcertplay.transport

import java.io.IOException
import java.net.Socket

/**
 * A [BlockingDuplexByteStream] backed by a TCP socket. [recv] uses [Socket.setSoTimeout] for
 * per-read deadlines.
 */
class SocketDuplexByteStream(private val socket: Socket) : BlockingDuplexByteStream {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun send(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        socket.soTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val buffer = ByteArray(maxBytes)
        return try {
            val n = input.read(buffer)
            when {
                n < 0 -> ByteArray(0)
                n == 0 -> null
                else -> buffer.copyOf(n)
            }
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) { }
    }
}
