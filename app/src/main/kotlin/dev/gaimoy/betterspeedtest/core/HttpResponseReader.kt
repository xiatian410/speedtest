package dev.gaimoy.betterspeedtest.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal data class HttpResponseHead(val status: Int, val headers: Map<String, String>)

/** Consumes exactly the header bytes, preserving the first body byte even with fragmented reads. */
internal fun readHttpResponseHead(input: InputStream, maxBytes: Int = 32 * 1024): HttpResponseHead {
    var remaining = maxBytes
    fun line(): String {
        val out = StringBuilder()
        while (remaining-- > 0) {
            val byte = input.read()
            if (byte < 0) throw EOFException("响应头不完整")
            if (byte == 10) return out.toString().removeSuffix("\r")
            out.append(byte.toChar())
        }
        throw IOException("响应头过大")
    }
    repeat(8) {
        val status = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*|)").matchEntire(line())
            ?.groupValues?.get(1)?.toInt() ?: throw IOException("无效 HTTP 状态行")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = line()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("无效 HTTP 响应头")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (status !in 100..199) return HttpResponseHead(status, headers)
    }
    throw IOException("过多临时 HTTP 响应")
}

/** Streams entity bytes only; HTTP framing is excluded from throughput statistics. */
internal fun readHttpBody(input: InputStream, head: HttpResponseHead, active: () -> Boolean, onBytes: (Int) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    fun copy(length: Long) {
        var remaining = length
        while (active() && remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("响应正文提前结束")
            if (n > 0) { onBytes(n); remaining -= n }
        }
    }
    if (head.headers["transfer-encoding"]?.split(',')?.any { it.trim().equals("chunked", true) } == true) {
        fun chunkLine(): String {
            val result = StringBuilder()
            repeat(8192) {
                val b = input.read()
                if (b < 0) throw EOFException("chunked 响应提前结束")
                if (b == 10) return result.toString().removeSuffix("\r")
                result.append(b.toChar())
            }
            throw IOException("chunked 响应行过长")
        }
        while (active()) {
            val size = chunkLine().substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 }
                ?: throw IOException("无效 chunk 大小")
            if (size == 0L) return
            copy(size)
            if (!active()) return
            if (chunkLine().isNotEmpty()) throw IOException("无效 chunk 分隔符")
        }
    } else {
        val length = head.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
        if (length != null) copy(length) else while (active()) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) onBytes(n)
        }
    }
}
