package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * One forwarded connection. [host] comes from DNS, the TLS server name, or
 * cleartext HTTP. [exchanges] are the HTTP messages, including those read
 * after TLS was opened with this app's certificate.
 */
data class TrafficFlow(
    val id: Long,
    val protocol: String,
    val source: String,
    val sourcePort: Int,
    val destination: String,
    val destinationPort: Int,
    val host: String?,
    val request: String?,
    val app: String?,
    val tls: Boolean,
    val sent: Long,
    val received: Long,
    val startedAt: Long,
    val lastAt: Long,
    val closed: Boolean,
    val exchanges: List<HttpExchange> = emptyList(),
    /** The phone refused the certificate this app offered for this connection. */
    val refused: Boolean = false,
) {
    val title: String get() = host?.takeIf { it.isNotEmpty() } ?: destination

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("protocol", protocol)
        .put("source", source)
        .put("sourcePort", sourcePort)
        .put("destination", destination)
        .put("destinationPort", destinationPort)
        .put("host", host ?: JSONObject.NULL)
        .put("request", request ?: JSONObject.NULL)
        .put("app", app ?: JSONObject.NULL)
        .put("tls", tls)
        .put("sent", sent)
        .put("received", received)
        .put("startedAt", startedAt)
        .put("lastAt", lastAt)
        .put("closed", closed)
        .put("exchanges", JSONArray().apply { exchanges.forEach { put(it.toJson()) } })
        .put("refused", refused)

    companion object {
        fun fromJson(obj: JSONObject): TrafficFlow? = runCatching {
            TrafficFlow(
                id = obj.getLong("id"),
                protocol = obj.getString("protocol"),
                source = obj.getString("source"),
                sourcePort = obj.getInt("sourcePort"),
                destination = obj.getString("destination"),
                destinationPort = obj.getInt("destinationPort"),
                host = obj.optStringOrNull("host"),
                request = obj.optStringOrNull("request"),
                app = obj.optStringOrNull("app"),
                tls = obj.optBoolean("tls"),
                sent = obj.getLong("sent"),
                received = obj.getLong("received"),
                startedAt = obj.getLong("startedAt"),
                lastAt = obj.getLong("lastAt"),
                closed = obj.optBoolean("closed"),
                exchanges = if (!obj.has("exchanges")) emptyList() else {
                    val array = obj.getJSONArray("exchanges")
                    (0 until array.length()).mapNotNull { HttpExchange.fromJson(array.getJSONObject(it)) }
                },
                refused = obj.optBoolean("refused"),
            )
        }.getOrNull()
    }
}

/** A saved recording: the connections forwarded during one run. */
class TrafficSession(
    val id: String,
    val startedAt: Long,
    val stoppedAt: Long,
    val flows: List<TrafficFlow>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("startedAt", startedAt)
        .put("stoppedAt", stoppedAt)
        .put("flows", JSONArray().apply { flows.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(text: String): TrafficSession? = runCatching {
            val obj = JSONObject(text)
            val array = obj.getJSONArray("flows")
            val flows = (0 until array.length()).mapNotNull { index ->
                TrafficFlow.fromJson(array.getJSONObject(index))
            }
            TrafficSession(
                id = obj.getString("id"),
                startedAt = obj.getLong("startedAt"),
                stoppedAt = obj.getLong("stoppedAt"),
                flows = flows,
            )
        }.getOrNull()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotEmpty() }
}

/** Names and requests that are visible without decrypting TLS. */
object TrafficHints {
    fun dnsQuestion(payload: ByteArray): String? {
        if (payload.size < 12) return null
        if (u16(payload, 4) < 1) return null
        val (name, _) = readName(payload, 12) ?: return null
        return cleanName(name)
    }

    /** Host names learned from A and AAAA answers, paired with the address text. */
    fun dnsAnswers(payload: ByteArray): List<Pair<String, String>> {
        if (payload.size < 12) return emptyList()
        if (u16(payload, 2) and 0x8000 == 0) return emptyList()
        var offset = 12
        repeat(u16(payload, 4)) {
            val read = readName(payload, offset) ?: return emptyList()
            offset = read.second + 4
            if (offset > payload.size) return emptyList()
        }
        val found = mutableListOf<Pair<String, String>>()
        repeat(u16(payload, 6)) {
            val read = readName(payload, offset) ?: return found
            offset = read.second
            if (offset + 10 > payload.size) return found
            val type = u16(payload, offset)
            val length = u16(payload, offset + 8)
            val data = offset + 10
            if (data + length > payload.size) return found
            val host = cleanName(read.first)
            if (host != null && type == 1 && length == 4) {
                found += host to IpCodec.text(payload.copyOfRange(data, data + 4))
            } else if (host != null && type == 28 && length == 16) {
                found += host to IpCodec.text(payload.copyOfRange(data, data + 16))
            }
            offset = data + length
        }
        return found
    }

    /**
     * Server name from a TLS ClientHello. Null when the bytes are not a
     * ClientHello or the record is still incomplete.
     */
    fun tlsServerName(data: ByteArray): String? {
        if (data.size < 5 || (data[0].toInt() and 0xFF) != 22) return null
        val record = u16(data, 3)
        if (data.size < 5 + record) return null
        var index = 5
        if (index >= data.size || (data[index].toInt() and 0xFF) != 1) return null
        index += 4
        if (index + 34 > data.size) return null
        index += 34
        val session = data[index].toInt() and 0xFF
        index += 1 + session
        if (index + 2 > data.size) return null
        val ciphers = u16(data, index)
        index += 2 + ciphers
        if (index + 1 > data.size) return null
        val compression = data[index].toInt() and 0xFF
        index += 1 + compression
        if (index + 2 > data.size) return null
        val extensions = u16(data, index)
        index += 2
        val end = minOf(index + extensions, data.size)
        while (index + 4 <= end) {
            val type = u16(data, index)
            val length = u16(data, index + 2)
            index += 4
            if (index + length > data.size) return null
            if (type == 0 && length >= 5) {
                val nameLength = u16(data, index + 3)
                if (nameLength > 0 && index + 5 + nameLength <= data.size) {
                    return cleanName(String(data, index + 5, nameLength, Charsets.US_ASCII))
                }
            }
            index += length
        }
        return null
    }

    /** True once a TLS record header is present and its body has arrived. */
    fun tlsRecordComplete(data: ByteArray): Boolean {
        if (data.size < 5 || (data[0].toInt() and 0xFF) != 22) return false
        return data.size >= 5 + u16(data, 3)
    }

    /** Request line and host, once the header block has arrived. Empty host when the header is missing. */
    fun httpRequest(data: ByteArray): Pair<String, String>? {
        if (httpMethod(data) == null) return null
        val text = String(data, Charsets.ISO_8859_1)
        if (!text.contains("\r\n\r\n")) return null
        val lines = text.substringBefore("\r\n\r\n").split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val host = lines.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(':')
            .orEmpty()
        return "${parts[0]} ${parts[1]}" to host
    }

    /** True while the opening bytes could still grow into an HTTP request. */
    fun awaitsHttp(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        if (httpMethod(data) != null) {
            return data.size < 4096 && !String(data, Charsets.ISO_8859_1).contains("\r\n\r\n")
        }
        val head = String(data, 0, minOf(8, data.size), Charsets.US_ASCII)
        return HTTP_METHODS.any { it.startsWith(head) }
    }

    private fun httpMethod(data: ByteArray): String? =
        HTTP_METHODS.firstOrNull { data.size >= it.length && String(data, 0, it.length, Charsets.US_ASCII) == it }

    private val HTTP_METHODS = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "PATCH ", "OPTIONS ", "CONNECT ")
}

private fun cleanName(name: String): String? {
    val trimmed = name.trim().trimEnd('.')
    if (trimmed.isEmpty() || trimmed.length > 253) return null
    if (trimmed.any { it.code < 33 || it.code > 126 }) return null
    return trimmed
}

private fun readName(payload: ByteArray, start: Int): Pair<String, Int>? {
    val labels = mutableListOf<String>()
    var index = start
    var end = -1
    var hops = 0
    while (index < payload.size && hops < 16) {
        val length = payload[index].toInt() and 0xFF
        when {
            length == 0 -> {
                if (end < 0) end = index + 1
                return labels.joinToString(".") to end
            }
            length and 0xC0 == 0xC0 -> {
                if (index + 1 >= payload.size) return null
                if (end < 0) end = index + 2
                index = ((length and 0x3F) shl 8) or (payload[index + 1].toInt() and 0xFF)
                hops++
            }
            else -> {
                if (index + 1 + length > payload.size) return null
                labels += String(payload, index + 1, length, Charsets.US_ASCII)
                index += 1 + length
            }
        }
    }
    return null
}

/**
 * IPv4 and IPv6 packets as they come out of a TUN device. Checksums are filled
 * in because the kernel drops a packet whose sum does not match.
 */
object IpCodec {
    const val TCP = 6
    const val UDP = 17
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val MSS = 1200

    class Packet(
        val version: Int,
        val protocol: Int,
        val src: ByteArray,
        val dst: ByteArray,
        val payload: ByteArray,
    )

    class Tcp(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val window: Int,
        val payload: ByteArray,
        val windowScale: Int?,
    )

    class Udp(
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    fun parse(packet: ByteArray): Packet? {
        if (packet.isEmpty()) return null
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> parse4(packet)
            6 -> parse6(packet)
            else -> null
        }
    }

    fun parseTcp(segment: ByteArray): Tcp? {
        if (segment.size < 20) return null
        val header = ((segment[12].toInt() and 0xF0) shr 4) * 4
        if (header < 20 || segment.size < header) return null
        return Tcp(
            srcPort = u16(segment, 0),
            dstPort = u16(segment, 2),
            seq = u32(segment, 4),
            ack = u32(segment, 8),
            flags = segment[13].toInt() and 0xFF,
            window = u16(segment, 14),
            payload = segment.copyOfRange(header, segment.size),
            windowScale = windowScale(segment, header),
        )
    }

    fun parseUdp(segment: ByteArray): Udp? {
        if (segment.size < 8) return null
        val length = u16(segment, 4)
        if (length < 8 || length > segment.size) return null
        return Udp(u16(segment, 0), u16(segment, 2), segment.copyOfRange(8, length))
    }

    fun tcpPacket(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
        id: Int = 0,
        mss: Int? = null,
        windowScale: Int? = null,
    ): ByteArray {
        var options = 0
        if (mss != null) options += 4
        if (windowScale != null) options += 4
        val header = 20 + options
        val segment = ByteArray(header + payload.size)
        put16(segment, 0, srcPort)
        put16(segment, 2, dstPort)
        put32(segment, 4, seq)
        put32(segment, 8, ack)
        segment[12] = ((header / 4) shl 4).toByte()
        segment[13] = flags.toByte()
        put16(segment, 14, window)
        var at = 20
        if (mss != null) {
            segment[at] = 2
            segment[at + 1] = 4
            put16(segment, at + 2, mss)
            at += 4
        }
        if (windowScale != null) {
            segment[at] = 1
            segment[at + 1] = 3
            segment[at + 2] = 3
            segment[at + 3] = windowScale.coerceIn(0, 14).toByte()
        }
        payload.copyInto(segment, header)
        put16(segment, 16, transportSum(src, dst, TCP, segment))
        return wrap(src, dst, TCP, segment, id)
    }

    fun udpPacket(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        id: Int = 0,
    ): ByteArray {
        val segment = ByteArray(8 + payload.size)
        put16(segment, 0, srcPort)
        put16(segment, 2, dstPort)
        put16(segment, 4, segment.size)
        payload.copyInto(segment, 8)
        var sum = transportSum(src, dst, UDP, segment)
        if (sum == 0) sum = 0xFFFF
        put16(segment, 6, sum)
        return wrap(src, dst, UDP, segment, id)
    }

    fun text(addr: ByteArray): String =
        InetAddress.getByAddress(addr).hostAddress ?: addr.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

    fun unicast(addr: ByteArray): Boolean = when (addr.size) {
        4 -> {
            val first = addr[0].toInt() and 0xFF
            first in 1..223 && first != 127
        }
        16 -> (addr[0].toInt() and 0xFF) != 0xFF
        else -> false
    }

    /** The one's-complement sum of [segment], including its checksum, folds to 0xFFFF when the sum is right. */
    fun transportValid(src: ByteArray, dst: ByteArray, protocol: Int, segment: ByteArray): Boolean {
        var sum = pseudo(src, dst, protocol, segment.size)
        sum = addWords(sum, segment)
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum == 0xFFFFL
    }

    private fun parse4(packet: ByteArray): Packet? {
        if (packet.size < 20) return null
        val header = (packet[0].toInt() and 0x0F) * 4
        val total = u16(packet, 2)
        if (header < 20 || total < header || packet.size < total) return null
        if (u16(packet, 6) and 0x1FFF != 0) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != TCP && protocol != UDP) return null
        return Packet(
            version = 4,
            protocol = protocol,
            src = packet.copyOfRange(12, 16),
            dst = packet.copyOfRange(16, 20),
            payload = packet.copyOfRange(header, total),
        )
    }

    private fun parse6(packet: ByteArray): Packet? {
        if (packet.size < 40) return null
        val payloadLength = u16(packet, 4)
        val total = 40 + payloadLength
        if (packet.size < total) return null
        var next = packet[6].toInt() and 0xFF
        var offset = 40
        var hops = 0
        while (next == 0 || next == 43 || next == 44 || next == 60) {
            if (next == 44 || offset + 2 > total || hops++ > 6) return null
            val length = (packet[offset + 1].toInt() and 0xFF) * 8 + 8
            if (offset + length > total) return null
            next = packet[offset].toInt() and 0xFF
            offset += length
        }
        if (next != TCP && next != UDP) return null
        return Packet(
            version = 6,
            protocol = next,
            src = packet.copyOfRange(8, 24),
            dst = packet.copyOfRange(24, 40),
            payload = packet.copyOfRange(offset, total),
        )
    }

    private fun wrap(src: ByteArray, dst: ByteArray, protocol: Int, segment: ByteArray, id: Int): ByteArray {
        return if (src.size == 16) {
            val packet = ByteArray(40 + segment.size)
            packet[0] = 0x60
            put16(packet, 4, segment.size)
            packet[6] = protocol.toByte()
            packet[7] = 64
            src.copyInto(packet, 8)
            dst.copyInto(packet, 24)
            segment.copyInto(packet, 40)
            packet
        } else {
            val packet = ByteArray(20 + segment.size)
            packet[0] = 0x45
            put16(packet, 2, packet.size)
            put16(packet, 4, id)
            put16(packet, 6, 0x4000)
            packet[8] = 64
            packet[9] = protocol.toByte()
            src.copyInto(packet, 12)
            dst.copyInto(packet, 16)
            put16(packet, 10, finish(addWords(0, packet, 0, 20)))
            segment.copyInto(packet, 20)
            packet
        }
    }

    private fun windowScale(segment: ByteArray, header: Int): Int? {
        var index = 20
        while (index < header) {
            val kind = segment[index].toInt() and 0xFF
            if (kind == 0) return null
            if (kind == 1) {
                index++
                continue
            }
            if (index + 1 >= header) return null
            val length = segment[index + 1].toInt() and 0xFF
            if (length < 2 || index + length > header) return null
            if (kind == 3 && length >= 3) return segment[index + 2].toInt() and 0xFF
            index += length
        }
        return null
    }

    private fun transportSum(src: ByteArray, dst: ByteArray, protocol: Int, segment: ByteArray): Int {
        var sum = pseudo(src, dst, protocol, segment.size)
        sum = addWords(sum, segment)
        return finish(sum)
    }

    private fun pseudo(src: ByteArray, dst: ByteArray, protocol: Int, length: Int): Long {
        var sum = addWords(0, src)
        sum = addWords(sum, dst)
        return if (src.size == 4) {
            sum + protocol + length
        } else {
            sum + (length ushr 16) + (length and 0xFFFF) + protocol
        }
    }

    private fun addWords(seed: Long, data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        var sum = seed
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((data[index].toInt() and 0xFF) shl 8 or (data[index + 1].toInt() and 0xFF)).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xFF) shl 8).toLong()
        return sum
    }

    private fun finish(sum: Long): Int {
        var value = sum
        while (value ushr 16 != 0L) value = (value and 0xFFFF) + (value ushr 16)
        return (value.inv() and 0xFFFF).toInt()
    }
}

private fun u16(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

private fun u32(data: ByteArray, offset: Int): Long =
    ((data[offset].toLong() and 0xFF) shl 24) or
        ((data[offset + 1].toLong() and 0xFF) shl 16) or
        ((data[offset + 2].toLong() and 0xFF) shl 8) or
        (data[offset + 3].toLong() and 0xFF)

private fun put16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 8).toByte()
    data[offset + 1] = value.toByte()
}

private fun put32(data: ByteArray, offset: Int, value: Long) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}
