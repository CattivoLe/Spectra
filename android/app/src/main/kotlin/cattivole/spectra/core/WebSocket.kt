package cattivole.spectra.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * One WebSocket message after an upgrade: a whole text or binary message (its
 * fragments joined), or a control frame. [size] is the payload as sent;
 * [preview] the start of a text message. The payload itself goes to the body
 * folder like an HTTP body, when [stored].
 */
data class WsMessage(
    val fromClient: Boolean,
    val opcode: Int,
    val at: Long,
    val size: Long,
    val preview: String?,
    val stored: Boolean,
    val cut: Boolean = false,
    val compressed: Boolean = false,
) {
    val kind: String
        get() = when (opcode) {
            TEXT -> "text"
            BINARY -> "binary"
            CLOSE -> "close"
            PING -> "ping"
            PONG -> "pong"
            else -> "opcode $opcode"
        }

    fun toJson(): JSONObject = JSONObject()
        .put("fromClient", fromClient)
        .put("opcode", opcode)
        .put("at", at)
        .put("size", size)
        .putOpt("preview", preview)
        .put("stored", stored)
        .put("cut", cut)
        .put("compressed", compressed)

    companion object {
        const val TEXT = 1
        const val BINARY = 2
        const val CLOSE = 8
        const val PING = 9
        const val PONG = 10
        const val PREVIEW = 200

        fun fromJson(obj: JSONObject): WsMessage? = runCatching {
            WsMessage(
                fromClient = obj.getBoolean("fromClient"),
                opcode = obj.getInt("opcode"),
                at = obj.getLong("at"),
                size = obj.optLong("size"),
                preview = if (obj.has("preview") && !obj.isNull("preview")) obj.getString("preview") else null,
                stored = obj.optBoolean("stored"),
                cut = obj.optBoolean("cut"),
                compressed = obj.optBoolean("compressed"),
            )
        }.getOrNull()
    }
}

/**
 * Reads the frames of one WebSocket connection in both directions (RFC 6455),
 * joining fragments and undoing permessage-deflate (RFC 7692). [onMessage] gets
 * each finished message and its payload, cut to [cap].
 */
class WsReader(
    extensions: String?,
    private val clock: () -> Long,
    private val onMessage: (WsMessage, ByteArray) -> Unit,
    private val cap: Int = 1024 * 1024,
) {
    private val deflate = extensions?.contains("permessage-deflate", ignoreCase = true) == true
    private val client = Side(fromClient = true, reset = extensions?.contains("client_no_context_takeover", true) == true)
    private val server = Side(fromClient = false, reset = extensions?.contains("server_no_context_takeover", true) == true)
    var broken = false
        private set

    fun client(data: ByteArray) = feed(client, data)

    fun server(data: ByteArray) = feed(server, data)

    private fun feed(side: Side, data: ByteArray) {
        if (broken || data.isEmpty()) return
        side.append(data)
        try {
            side.pump()
        } catch (_: Exception) {
            broken = true
        }
    }

    private inner class Side(val fromClient: Boolean, val reset: Boolean) {
        /** Bytes not yet read, from [head] to [tail]; grows by doubling, so a large frame is not copied over and over. */
        private var pending = ByteArray(4096)
        private var head = 0
        private var tail = 0
        private var inflater: Inflater? = null
        private var inflaterBroken = false

        /** The data message being joined from fragments. */
        private var opcode = 0
        private var startedAt = 0L
        private var compressed = false
        private val payload = ByteArrayOutputStream()
        private var wire = 0L
        private var cut = false

        fun append(data: ByteArray) {
            if (tail + data.size > pending.size) {
                val live = tail - head
                val grown = if (live + data.size > pending.size / 2) ByteArray(maxOf(pending.size * 2, live + data.size)) else pending
                System.arraycopy(pending, head, grown, 0, live)
                pending = grown
                head = 0
                tail = live
            }
            System.arraycopy(data, 0, pending, tail, data.size)
            tail += data.size
        }

        fun pump() {
            while (true) {
                val frame = header(pending, head, tail) ?: break
                if (tail - frame.start < frame.length) break
                val end = (frame.start + frame.length).toInt()
                val body = pending.copyOfRange(frame.start, end)
                frame.mask?.let { mask -> for (i in body.indices) body[i] = (body[i].toInt() xor mask[i and 3].toInt()).toByte() }
                head = end
                handle(frame, body)
            }
            if (head == tail) {
                head = 0
                tail = 0
            }
            if (tail - head > MAX_FRAME + 14) throw IllegalStateException("frame too large to hold")
        }

        private fun handle(frame: Frame, body: ByteArray) {
            val now = clock()
            if (frame.opcode >= 8) {
                val text = if (frame.opcode == WsMessage.CLOSE && body.size >= 2) {
                    val code = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    val reason = String(body, 2, body.size - 2, Charsets.UTF_8)
                    if (reason.isEmpty()) "$code" else "$code $reason"
                } else {
                    String(body, Charsets.UTF_8).takeIf { body.isNotEmpty() && frame.opcode != WsMessage.CLOSE }
                }
                emit(frame.opcode, now, body.size.toLong(), body, text?.take(WsMessage.PREVIEW), cut = false, compressed = false)
                return
            }
            if (frame.opcode != 0) {
                opcode = frame.opcode
                startedAt = now
                compressed = deflate && frame.rsv1
                payload.reset()
                wire = 0
                cut = false
            }
            wire += body.size
            val room = (if (compressed) MAX_FRAME else cap) - payload.size()
            if (body.size > room) {
                if (room > 0) payload.write(body, 0, room)
                cut = true
            } else {
                payload.write(body)
            }
            if (!frame.fin) return
            var bytes = payload.toByteArray()
            var messageCut = cut
            if (compressed) {
                val inflated = inflate(bytes, complete = !cut)
                if (inflated == null) {
                    messageCut = true
                } else {
                    bytes = inflated.first
                    messageCut = messageCut || inflated.second
                }
            }
            val text = if (opcode == WsMessage.TEXT) String(bytes, 0, minOf(bytes.size, WsMessage.PREVIEW * 4), Charsets.UTF_8).take(WsMessage.PREVIEW) else null
            emit(opcode, startedAt, wire, bytes, text, messageCut, compressed)
            payload.reset()
        }

        private fun emit(opcode: Int, at: Long, size: Long, body: ByteArray, preview: String?, cut: Boolean, compressed: Boolean) {
            val shown = if (body.size > cap) body.copyOf(cap) else body
            onMessage(
                WsMessage(fromClient, opcode, at, size, preview, stored = false, cut = cut || body.size > cap, compressed = compressed),
                shown,
            )
        }

        /** The message as sent, undoing permessage-deflate. Null once the shared window was lost. */
        private fun inflate(data: ByteArray, complete: Boolean): Pair<ByteArray, Boolean>? {
            if (inflaterBroken) return null
            if (!complete) {
                // Part of the stream was not kept, so later messages that refer back to it cannot be read.
                if (!reset) inflaterBroken = true
                return null
            }
            val engine = inflater ?: Inflater(true).also { inflater = it }
            return try {
                engine.setInput(data + TAIL)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var over = false
                while (true) {
                    val count = engine.inflate(buffer)
                    if (count == 0 && (engine.needsInput() || engine.finished())) break
                    if (count == 0 && engine.needsDictionary()) throw IllegalStateException("dictionary")
                    val room = cap - out.size()
                    if (count > room) {
                        if (room > 0) out.write(buffer, 0, room)
                        over = true
                    } else {
                        out.write(buffer, 0, count)
                    }
                }
                if (reset) {
                    engine.end()
                    inflater = null
                }
                out.toByteArray() to over
            } catch (_: Exception) {
                inflaterBroken = true
                null
            }
        }
    }

    private class Frame(val fin: Boolean, val rsv1: Boolean, val opcode: Int, val start: Int, val length: Long, val mask: ByteArray?)

    private fun header(data: ByteArray, at: Int, end: Int): Frame? {
        if (end - at < 2) return null
        val b0 = data[at].toInt() and 0xFF
        val b1 = data[at + 1].toInt() and 0xFF
        var length = (b1 and 0x7F).toLong()
        var index = at + 2
        if (length == 126L) {
            if (end - index < 2) return null
            length = (((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)).toLong()
            index += 2
        } else if (length == 127L) {
            if (end - index < 8) return null
            length = 0
            for (i in 0 until 8) length = (length shl 8) or (data[index + i].toLong() and 0xFF)
            index += 8
        }
        if (length < 0 || length > MAX_FRAME) throw IllegalStateException("frame of $length bytes")
        val masked = b1 and 0x80 != 0
        val mask = if (masked) {
            if (end - index < 4) return null
            data.copyOfRange(index, index + 4).also { index += 4 }
        } else {
            null
        }
        return Frame(b0 and 0x80 != 0, b0 and 0x40 != 0, b0 and 0x0F, index, length, mask)
    }

    private companion object {
        /** Frames are held whole before they are read; a larger one ends the reading, not the connection. */
        const val MAX_FRAME = 4 * 1024 * 1024
        val TAIL = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte())
    }
}
