package cattivole.spectra.core

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Rewrites cleartext requests so the server sends an uncompressed body.
 * `Accept-Encoding` becomes `identity`. The bytes the phone sent are left
 * untouched for the recording; only the copy forwarded to the server changes.
 *
 * With [mapLocal], a request a rule matches is not forwarded at all, body
 * included: [onLocal] gets the response to give the phone instead. Responses go
 * out in order only while the phone waits for each answer before the next
 * request, as HTTP/1.1 clients do (pipelining is practically unused).
 */
class AcceptEncodingStrip(
    private val host: String? = null,
    private val mapLocal: MapLocal? = null,
    private val onLocal: (ByteArray) -> Unit = {},
    private val rewriteEncoding: Boolean = true,
) {
    /** The body being read belongs to a request answered locally: it is dropped, not forwarded. */
    private var dropping = false
    private val pending = ByteArrayOutputStream()
    private var mode = Mode.HEADERS
    private var fixedLeft = 0
    private var chunkLeft = 0
    private var passthrough = false

    fun feed(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        if (passthrough) return listOf(data)
        pending.write(data)
        return drain(finish = false)
    }

    /** Forwards whatever is still buffered when the phone closes. */
    fun finish(): List<ByteArray> {
        if (passthrough) return emptyList()
        passthrough = true
        return drain(finish = true)
    }

    private fun drain(finish: Boolean): List<ByteArray> {
        val buf = pending.toByteArray()
        pending.reset()
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < buf.size) {
            when (mode) {
                Mode.HEADERS -> {
                    val end = indexOf(buf, offset, HEADER_END)
                    if (end < 0 && !couldBeRequest(buf, offset)) {
                        // Not HTTP/1.1 any more (a WebSocket, another protocol): let it all through at once.
                        passthrough = true
                        out += buf.copyOfRange(offset, buf.size)
                        offset = buf.size
                        break
                    }
                    if (end < 0) {
                        if (finish || buf.size - offset > HEADER_CAP) {
                            passthrough = true
                            out += buf.copyOfRange(offset, buf.size)
                            offset = buf.size
                        }
                        break
                    }
                    val text = String(buf, offset, end - offset, Charsets.ISO_8859_1)
                    val first = text.substringBefore("\r\n")
                    val method = first.substringBefore(' ')
                    if (method !in METHODS) {
                        passthrough = true
                        out += buf.copyOfRange(offset, buf.size)
                        offset = buf.size
                        break
                    }
                    dropping = false
                    val target = first.split(' ').getOrNull(1).orEmpty()
                    val rule = if (method != "CONNECT") mapLocal?.ruleFor(host, target) else null
                    if (rule != null) {
                        onLocal(MapLocal.response(rule, method))
                        offset = end + HEADER_END.size
                        mode = bodyMode(text)
                        dropping = mode != Mode.HEADERS
                        continue
                    }
                    val sent = if (rewriteEncoding) rewrite(text) else text
                    out += (sent + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                    offset = end + HEADER_END.size
                    if (method == "CONNECT" || text.contains("\r\nUpgrade:", ignoreCase = true)) {
                        passthrough = true
                        if (offset < buf.size) out += buf.copyOfRange(offset, buf.size)
                        offset = buf.size
                        break
                    }
                    mode = bodyMode(text)
                }
                Mode.FIXED -> {
                    val take = min(fixedLeft, buf.size - offset)
                    if (take > 0) keep(out, buf.copyOfRange(offset, offset + take))
                    offset += take
                    fixedLeft -= take
                    if (fixedLeft == 0) mode = Mode.HEADERS else break
                }
                Mode.CHUNK_SIZE -> {
                    val end = indexOf(buf, offset, LINE_END)
                    if (end < 0) break
                    val line = String(buf, offset, end - offset, Charsets.US_ASCII).substringBefore(';').trim()
                    keep(out, buf.copyOfRange(offset, end + LINE_END.size))
                    offset = end + LINE_END.size
                    val size = line.toIntOrNull(16)
                    if (size == null) {
                        passthrough = true
                        if (offset < buf.size) keep(out, buf.copyOfRange(offset, buf.size))
                        offset = buf.size
                    } else if (size == 0) {
                        mode = Mode.CHUNK_TRAILERS
                    } else {
                        chunkLeft = size
                        mode = Mode.CHUNK_DATA
                    }
                }
                Mode.CHUNK_DATA -> {
                    val take = min(chunkLeft, buf.size - offset)
                    if (take > 0) keep(out, buf.copyOfRange(offset, offset + take))
                    offset += take
                    chunkLeft -= take
                    if (chunkLeft == 0) mode = Mode.CHUNK_CRLF else break
                }
                Mode.CHUNK_CRLF -> {
                    if (buf.size - offset < 2) break
                    keep(out, buf.copyOfRange(offset, offset + 2))
                    offset += 2
                    mode = Mode.CHUNK_SIZE
                }
                Mode.CHUNK_TRAILERS -> {
                    if (buf.size - offset >= 2 && buf[offset] == 13.toByte() && buf[offset + 1] == 10.toByte()) {
                        keep(out, buf.copyOfRange(offset, offset + 2))
                        offset += 2
                        mode = Mode.HEADERS
                    } else {
                        val end = indexOf(buf, offset, HEADER_END)
                        if (end < 0) break
                        keep(out, buf.copyOfRange(offset, end + HEADER_END.size))
                        offset = end + HEADER_END.size
                        mode = Mode.HEADERS
                    }
                }
            }
        }
        if (offset < buf.size) pending.write(buf, offset, buf.size - offset)
        return out
    }

    private fun keep(out: MutableList<ByteArray>, bytes: ByteArray) {
        if (!dropping) out += bytes
    }

    private enum class Mode { HEADERS, FIXED, CHUNK_SIZE, CHUNK_DATA, CHUNK_CRLF, CHUNK_TRAILERS }

    private fun bodyMode(header: String): Mode {
        val lines = header.split("\r\n").drop(1)
        if (lines.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true) }) {
            return Mode.CHUNK_SIZE
        }
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
        if (length != null && length > 0) {
            fixedLeft = length
            return Mode.FIXED
        }
        return Mode.HEADERS
    }

    private fun rewrite(header: String): String = header.split("\r\n").joinToString("\r\n") { line ->
        if (line.startsWith("Accept-Encoding:", ignoreCase = true)) "Accept-Encoding: identity" else line
    }

    private fun indexOf(data: ByteArray, start: Int, pattern: ByteArray): Int {
        val last = data.size - pattern.size
        var index = start
        while (index <= last) {
            var matches = true
            for (step in pattern.indices) {
                if (data[index + step] != pattern[step]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
            index++
        }
        return -1
    }

    private companion object {
        val HEADER_END = byteArrayOf(13, 10, 13, 10)
        val LINE_END = byteArrayOf(13, 10)
        const val HEADER_CAP = 32 * 1024
        val METHODS = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE")
    }

    /** True while the bytes from [offset] could still be the start of a request line. */
    private fun couldBeRequest(buf: ByteArray, offset: Int): Boolean {
        val head = String(buf, offset, minOf(8, buf.size - offset), Charsets.ISO_8859_1)
        return METHODS.any { method -> "$method ".startsWith(head) || head.startsWith("$method ") }
    }
}
