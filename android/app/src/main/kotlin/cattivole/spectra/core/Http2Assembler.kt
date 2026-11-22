package cattivole.spectra.core

import java.io.ByteArrayOutputStream

/**
 * Reads an HTTP/2 connection (RFC 9113) into exchanges, one per stream: the
 * frames of both directions, header blocks through [HpackDecoder], bodies from
 * DATA frames. Times and sizes are kept as for HTTP/1.1; header sizes count the
 * compressed blocks and their frame headers. Trailers (gRPC's status lives
 * there) are added to the response headers.
 */
class Http2Assembler(
    private val sink: BodySink = BodySink { _, _, _ -> false },
    private val clock: () -> Long = System::currentTimeMillis,
) : HttpStream {
    private val client = Direction(fromClient = true)
    private val server = Direction(fromClient = false)
    private val streams = HashMap<Int, Stream>()
    private val ready = mutableListOf<HttpExchange>()
    private var broken = false

    override fun exchanges(): List<HttpExchange> = ready.toList()

    override fun client(data: ByteArray) = client.feed(data)

    override fun server(data: ByteArray) = server.feed(data)

    override fun serverClosed() {
        val now = clock()
        for (stream in streams.values.toList()) {
            if (!stream.responseDone && stream.responseStartAt != null) finish(stream, request = false, now)
        }
    }

    private class Body(val cap: Int) {
        val kept = ByteArrayOutputStream()
        var wire = 0L
        var cut = false

        fun add(data: ByteArray, from: Int, to: Int) {
            wire += to - from
            val room = cap - kept.size()
            val count = to - from
            if (count > room) {
                if (room > 0) kept.write(data, from, room)
                cut = true
            } else {
                kept.write(data, from, count)
            }
        }
    }

    private class Stream(val id: Int, val index: Int, val startedAt: Long) {
        var requestHeaders: List<HttpField> = emptyList()
        var responseHeaders: List<HttpField> = emptyList()
        var status: Int? = null
        var requestHeaderBytes = 0L
        var responseHeaderBytes = 0L
        val requestBody = Body(HttpAssembler.REQUEST_CAP)
        val responseBody = Body(HttpAssembler.RESPONSE_CAP)
        var requestEndAt: Long? = null
        var responseStartAt: Long? = null
        var endedAt: Long? = null
        var requestDone = false
        var responseDone = false
        var requestStored = false
        var requestCut = false
        var responseStored = false
        var responseCut = false
        var parameters: List<HttpField> = emptyList()
    }

    /** A header block spread over HEADERS (or PUSH_PROMISE) and CONTINUATION frames. */
    private class Block(val stream: Int, val endStream: Boolean, val promised: Int?) {
        val bytes = ByteArrayOutputStream()
        var frames = 0
    }

    private inner class Direction(val fromClient: Boolean) {
        private val hpack = HpackDecoder()
        private var data = ByteArray(16 * 1024)
        private var head = 0
        private var tail = 0
        private var preface = fromClient
        private var block: Block? = null

        fun feed(bytes: ByteArray) {
            if (broken || bytes.isEmpty()) return
            append(bytes)
            try {
                pump()
            } catch (_: Exception) {
                broken = true
            }
        }

        private fun append(bytes: ByteArray) {
            if (tail + bytes.size > data.size) {
                val live = tail - head
                val grown = if (live + bytes.size > data.size / 2) ByteArray(maxOf(data.size * 2, live + bytes.size)) else data
                System.arraycopy(data, head, grown, 0, live)
                data = grown
                head = 0
                tail = live
            }
            System.arraycopy(bytes, 0, data, tail, bytes.size)
            tail += bytes.size
        }

        private fun pump() {
            if (preface) {
                if (tail - head < PREFACE.size) return
                for (i in PREFACE.indices) if (data[head + i] != PREFACE[i]) throw IllegalStateException("not HTTP/2")
                head += PREFACE.size
                preface = false
            }
            while (tail - head >= 9) {
                val length = u24(data, head)
                if (length > MAX_FRAME) throw IllegalStateException("frame of $length bytes")
                if (tail - head < 9 + length) break
                val type = data[head + 3].toInt() and 0xFF
                val flags = data[head + 4].toInt() and 0xFF
                val id = u31(data, head + 5)
                val start = head + 9
                head = start + length
                frame(type, flags, id, start, start + length)
            }
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        private fun frame(type: Int, flags: Int, id: Int, start: Int, end: Int) {
            val now = clock()
            when (type) {
                DATA -> {
                    val stream = streams[id] ?: return
                    val (from, to) = unpad(flags, start, end)
                    val body = if (fromClient) stream.requestBody else stream.responseBody
                    if (stream.index >= 0) body.add(data, from, to) else body.wire += to - from
                    if (flags and END_STREAM != 0) finish(stream, request = fromClient, now)
                }
                HEADERS -> {
                    var (from, to) = unpad(flags, start, end)
                    if (flags and PRIORITY != 0) from += 5
                    open(Block(id, flags and END_STREAM != 0, null), flags, from, to, now)
                }
                PUSH_PROMISE -> {
                    val (from, to) = unpad(flags, start, end)
                    open(Block(id, false, u31(data, from)), flags, from + 4, to, now)
                }
                CONTINUATION -> {
                    val current = block ?: throw IllegalStateException("stray CONTINUATION")
                    current.bytes.write(data, start, end - start)
                    current.frames++
                    if (flags and END_HEADERS != 0) close(current, now)
                }
                RST_STREAM -> streams[id]?.let { stream ->
                    if (!stream.requestDone) finish(stream, request = true, now)
                    if (!stream.responseDone) finish(stream, request = false, now)
                }
                else -> Unit
            }
        }

        private fun open(next: Block, flags: Int, from: Int, to: Int, now: Long) {
            if (to < from) throw IllegalStateException("short frame")
            next.bytes.write(data, from, to - from)
            next.frames = 1
            if (flags and END_HEADERS != 0) close(next, now) else block = next
        }

        private fun unpad(flags: Int, start: Int, end: Int): Pair<Int, Int> {
            if (flags and PADDED == 0) return start to end
            val pad = data[start].toInt() and 0xFF
            if (start + 1 + pad > end) throw IllegalStateException("padding")
            return start + 1 to end - pad
        }

        /** Decodes a finished header block; every block is decoded, so the table stays in step. */
        private fun close(done: Block, now: Long) {
            block = null
            val raw = done.bytes.toByteArray()
            val fields = hpack.decode(raw)
            val bytes = raw.size + 9L * done.frames
            if (done.promised != null) {
                val stream = start(done.promised, now) ?: return
                stream.requestHeaders = fields
                stream.requestHeaderBytes = bytes
                finish(stream, request = true, now)
                return
            }
            if (fromClient) {
                val existing = streams[done.stream]
                val stream = if (existing == null) start(done.stream, now) ?: return else existing
                if (existing == null) {
                    stream.requestHeaders = fields
                } else {
                    stream.requestHeaders = stream.requestHeaders + fields
                }
                stream.requestHeaderBytes += bytes
                if (done.endStream) finish(stream, request = true, now) else publish(stream)
            } else {
                val stream = streams[done.stream] ?: return
                if (stream.responseStartAt == null) stream.responseStartAt = now
                stream.responseHeaderBytes += bytes
                val status = fields.firstOrNull { it.name == ":status" }?.value?.toIntOrNull()
                when {
                    status != null && status in 100..199 -> Unit
                    stream.status == null && status != null -> {
                        stream.status = status
                        stream.responseHeaders = fields
                    }
                    else -> stream.responseHeaders = stream.responseHeaders + fields
                }
                if (done.endStream) finish(stream, request = false, now) else publish(stream)
            }
        }
    }

    private fun start(id: Int, now: Long): Stream? {
        val index = if (ready.size < MAX_STREAMS) ready.size else -1
        val stream = Stream(id, index, now)
        streams[id] = stream
        if (index >= 0) ready += placeholder()
        return stream
    }

    private fun placeholder() = HttpExchange("", "", emptyList(), emptyList(), emptyList(), null, emptyList())

    private fun finish(stream: Stream, request: Boolean, now: Long) {
        if (request) {
            if (stream.requestDone) return
            stream.requestDone = true
            stream.requestEndAt = now
            if (stream.index >= 0) {
                val (decoded, decodeCut) = decodeBody(stream.requestBody.kept.toByteArray(), stream.requestHeaders, HttpAssembler.REQUEST_CAP)
                stream.requestCut = stream.requestBody.cut || decodeCut
                if (!stream.requestCut) {
                    stream.parameters = buildList {
                        if (isForm(stream.requestHeaders)) addAll(formFields(decoded))
                        if (isJsonType(stream.requestHeaders)) addAll(jsonFields(decoded))
                    }.take(40)
                }
                stream.requestStored = decoded.isNotEmpty() && sink.keep(stream.index, true, decoded)
                stream.requestBody.kept.reset()
            }
        } else {
            if (stream.responseDone) return
            stream.responseDone = true
            stream.endedAt = now
            if (stream.index >= 0) {
                val (decoded, decodeCut) = decodeBody(stream.responseBody.kept.toByteArray(), stream.responseHeaders, HttpAssembler.RESPONSE_CAP)
                stream.responseCut = stream.responseBody.cut || decodeCut
                stream.responseStored = decoded.isNotEmpty() && sink.keep(stream.index, false, decoded)
                stream.responseBody.kept.reset()
            }
        }
        publish(stream)
        if (stream.requestDone && stream.responseDone) streams.remove(stream.id)
    }

    private fun publish(stream: Stream) {
        if (stream.index < 0) return
        val pseudo = { name: String -> stream.requestHeaders.firstOrNull { it.name == name }?.value }
        val target = pseudo(":path") ?: "/"
        val responded = stream.responseStartAt != null
        ready[stream.index] = HttpExchange(
            method = pseudo(":method") ?: "",
            path = target.substringBefore('?').ifEmpty { "/" },
            query = queryFields(target),
            requestHeaders = stream.requestHeaders,
            parameters = stream.parameters,
            status = stream.status,
            responseHeaders = stream.responseHeaders,
            responseCut = stream.responseCut,
            requestCut = stream.requestCut,
            requestBodyStored = stream.requestStored,
            responseBodyStored = stream.responseStored,
            httpVersion = VERSION,
            target = target,
            startedAt = stream.startedAt,
            requestEndAt = stream.requestEndAt,
            responseStartAt = stream.responseStartAt,
            endedAt = stream.endedAt,
            requestHeaderBytes = stream.requestHeaderBytes,
            requestBodyBytes = stream.requestBody.wire,
            responseHeaderBytes = if (responded) stream.responseHeaderBytes else null,
            responseBodyBytes = if (responded) stream.responseBody.wire else null,
        )
    }

    companion object {
        const val VERSION = "HTTP/2"
        /** Streams listed for one connection; later ones are read but not kept. */
        const val MAX_STREAMS = 200
        private const val MAX_FRAME = 16 * 1024 * 1024
        private const val DATA = 0
        private const val HEADERS = 1
        private const val RST_STREAM = 3
        private const val PUSH_PROMISE = 5
        private const val CONTINUATION = 9
        private const val END_STREAM = 0x1
        private const val END_HEADERS = 0x4
        private const val PADDED = 0x8
        private const val PRIORITY = 0x20
        val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII)

        private fun u24(data: ByteArray, at: Int): Int =
            ((data[at].toInt() and 0xFF) shl 16) or ((data[at + 1].toInt() and 0xFF) shl 8) or (data[at + 2].toInt() and 0xFF)

        private fun u31(data: ByteArray, at: Int): Int =
            ((data[at].toInt() and 0x7F) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
                ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
    }
}
