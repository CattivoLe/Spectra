package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import org.brotli.dec.BrotliInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min

/** One header or parameter. */
data class HttpField(val name: String, val value: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("value", value)

    companion object {
        fun fromJson(obj: JSONObject): HttpField? = runCatching {
            HttpField(obj.getString("name"), obj.getString("value"))
        }.getOrNull()
    }
}

/**
 * One cleartext HTTP request and, once it has arrived, its response.
 *
 * Bodies are not kept here: they go to a [TrafficBodies] folder as files, so a
 * list of hundreds of exchanges stays small. [requestBodyStored] and
 * [responseBodyStored] say a body was handed over to be written.
 * [responseJson] is how older versions kept the response: the body as text when
 * it was JSON or readable. Recordings saved before bodies were stored still
 * have it, new ones leave it empty.
 *
 * Times are milliseconds since the epoch: the request's first byte, its last
 * byte, the response's first byte and its last byte. Sizes count the bytes on
 * the wire: the header block, then the body as sent (compressed, chunked).
 */
data class HttpExchange(
    val method: String,
    val path: String,
    val query: List<HttpField>,
    val requestHeaders: List<HttpField>,
    val parameters: List<HttpField>,
    val status: Int?,
    val responseHeaders: List<HttpField>,
    val responseJson: String? = null,
    val responseCut: Boolean = false,
    val requestCut: Boolean = false,
    val requestBodyStored: Boolean = false,
    val responseBodyStored: Boolean = false,
    val httpVersion: String? = null,
    /** The request target as sent, path and query undecoded. Null in older recordings. */
    val target: String? = null,
    val statusText: String? = null,
    val startedAt: Long? = null,
    val requestEndAt: Long? = null,
    val responseStartAt: Long? = null,
    val endedAt: Long? = null,
    val requestHeaderBytes: Long? = null,
    val requestBodyBytes: Long? = null,
    val responseHeaderBytes: Long? = null,
    val responseBodyBytes: Long? = null,
) {
    val summary: String get() = "$method $path"

    /** Header block plus body, as sent. Null for recordings made before sizes were kept. */
    val requestSize: Long? get() = requestHeaderBytes?.let { it + (requestBodyBytes ?: 0) }

    val responseSize: Long? get() = responseHeaderBytes?.let { it + (responseBodyBytes ?: 0) }

    /** From the request's first byte to the response's first byte. */
    val waitMs: Long? get() = if (startedAt != null && responseStartAt != null) (responseStartAt - startedAt).coerceAtLeast(0) else null

    /** From the request's first byte to the response's last byte. */
    val durationMs: Long? get() = if (startedAt != null && endedAt != null) (endedAt - startedAt).coerceAtLeast(0) else null

    fun header(name: String, response: Boolean): String? =
        (if (response) responseHeaders else requestHeaders).firstOrNull { it.name.equals(name, true) }?.value

    fun toJson(): JSONObject = JSONObject()
        .put("method", method)
        .put("path", path)
        .put("query", query.toJsonArray())
        .put("requestHeaders", requestHeaders.toJsonArray())
        .put("parameters", parameters.toJsonArray())
        .put("status", status ?: JSONObject.NULL)
        .put("responseHeaders", responseHeaders.toJsonArray())
        .put("responseJson", responseJson ?: JSONObject.NULL)
        .put("responseCut", responseCut)
        .put("requestCut", requestCut)
        .put("requestBodyStored", requestBodyStored)
        .put("responseBodyStored", responseBodyStored)
        .putOpt("httpVersion", httpVersion)
        .putOpt("target", target)
        .putOpt("statusText", statusText)
        .putOpt("startedAt", startedAt)
        .putOpt("requestEndAt", requestEndAt)
        .putOpt("responseStartAt", responseStartAt)
        .putOpt("endedAt", endedAt)
        .putOpt("requestHeaderBytes", requestHeaderBytes)
        .putOpt("requestBodyBytes", requestBodyBytes)
        .putOpt("responseHeaderBytes", responseHeaderBytes)
        .putOpt("responseBodyBytes", responseBodyBytes)

    companion object {
        fun fromJson(obj: JSONObject): HttpExchange? = runCatching {
            HttpExchange(
                method = obj.getString("method"),
                path = obj.getString("path"),
                query = obj.fields("query"),
                requestHeaders = obj.fields("requestHeaders"),
                parameters = obj.fields("parameters"),
                status = if (obj.isNull("status")) null else obj.getInt("status"),
                responseHeaders = obj.fields("responseHeaders"),
                responseJson = if (!obj.has("responseJson") || obj.isNull("responseJson")) null else obj.getString("responseJson").takeIf { it.isNotEmpty() },
                responseCut = obj.optBoolean("responseCut"),
                requestCut = obj.optBoolean("requestCut"),
                requestBodyStored = obj.optBoolean("requestBodyStored"),
                responseBodyStored = obj.optBoolean("responseBodyStored"),
                httpVersion = obj.optText("httpVersion"),
                target = obj.optText("target"),
                statusText = obj.optText("statusText"),
                startedAt = obj.optLongOrNull("startedAt"),
                requestEndAt = obj.optLongOrNull("requestEndAt"),
                responseStartAt = obj.optLongOrNull("responseStartAt"),
                endedAt = obj.optLongOrNull("endedAt"),
                requestHeaderBytes = obj.optLongOrNull("requestHeaderBytes"),
                requestBodyBytes = obj.optLongOrNull("requestBodyBytes"),
                responseHeaderBytes = obj.optLongOrNull("responseHeaderBytes"),
                responseBodyBytes = obj.optLongOrNull("responseBodyBytes"),
            )
        }.getOrNull()
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.optText(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

/** How a row is grouped in the traffic list. HTTP and TLS win over raw TCP. */
enum class TrafficKind { HTTP, TLS, DNS, TCP, UDP }

fun TrafficFlow.kind(): TrafficKind = when {
    exchanges.isNotEmpty() || !request.isNullOrEmpty() -> TrafficKind.HTTP
    tls -> TrafficKind.TLS
    destinationPort == 53 -> TrafficKind.DNS
    protocol == "UDP" -> TrafficKind.UDP
    else -> TrafficKind.TCP
}

/**
 * Takes a finished body: the exchange's index, which side it is, and the bytes
 * once Content-Encoding is undone. True when the body will be kept.
 */
fun interface BodySink {
    fun keep(index: Int, request: Boolean, body: ByteArray): Boolean
}

/**
 * Splits a cleartext HTTP byte stream into requests and responses.
 * A body larger than its cap is read only up to that cap, so the next
 * message still lines up; the exchange says it was cut. Finished bodies go to
 * [sink]; the exchanges keep their times and sizes.
 */
class HttpAssembler(
    private val sink: BodySink = BodySink { _, _, _ -> false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client = ByteCursor()
    private val server = ByteCursor()
    private val ready = mutableListOf<HttpExchange>()
    private var step = Step.REQUEST_HEADERS
    private var open = false
    private var stopped = false
    private var method = ""
    private var path = ""
    private var target = ""
    private var version: String? = null
    private var requestHeaders = emptyList<HttpField>()
    private var requestBody = BodyReader(BodyPlan.None, REQUEST_CAP)
    private var status = 0
    private var statusText: String? = null
    private var responseHeaders = emptyList<HttpField>()
    private var responseBody = BodyReader(BodyPlan.None, RESPONSE_CAP)
    private var parameters = emptyList<HttpField>()
    private var requestCut = false
    private var requestStored = false
    private var requestHeaderBytes = 0L
    private var responseHeaderBytes = 0L

    /** When the oldest byte still waiting in each cursor arrived. */
    private var clientMark: Long? = null
    private var serverMark: Long? = null
    private var startedAt: Long? = null
    private var requestEndAt: Long? = null
    private var responseStartAt: Long? = null

    fun exchanges(): List<HttpExchange> = ready.toList()

    fun client(data: ByteArray) {
        if (stopped || data.isEmpty()) return
        if (client.available() == 0) clientMark = clock()
        client.append(data)
        pump()
    }

    fun server(data: ByteArray) {
        if (stopped || data.isEmpty()) return
        if (server.available() == 0) serverMark = clock()
        server.append(data)
        pump()
    }

    /** The server closed, so a response without a length ends here. */
    fun serverClosed() {
        if (stopped) return
        if (step == Step.RESPONSE_BODY) responseBody.finishAtClose()
        pump()
    }

    private fun pump() {
        while (!stopped) {
            when (step) {
                Step.REQUEST_HEADERS -> {
                    if (client.overflow()) {
                        stopped = true
                        return
                    }
                    val mark = clientMark
                    val header = client.takeHeaders() ?: return
                    val parsed = parseRequest(header) ?: run { stopped = true; return }
                    if (ready.size >= MAX_EXCHANGES) {
                        stopped = true
                        return
                    }
                    startedAt = mark ?: clock()
                    clientMark = if (client.available() > 0) clock() else null
                    requestEndAt = null
                    responseStartAt = null
                    requestHeaderBytes = header.length + 4L
                    responseHeaderBytes = 0
                    method = parsed.method
                    target = parsed.target
                    version = parsed.version
                    path = target.substringBefore('?').ifEmpty { "/" }
                    requestHeaders = parsed.headers
                    statusText = null
                    parameters = emptyList()
                    requestCut = false
                    requestStored = false
                    requestBody = BodyReader(plan(requestHeaders, request = true, method = method, status = 0), REQUEST_CAP)
                    step = Step.REQUEST_BODY
                }
                Step.REQUEST_BODY -> {
                    if (!requestBody.pump(client)) return
                    requestEndAt = clock()
                    if (client.available() == 0) clientMark = null
                    val (decoded, decodeCut) = decodeBody(requestBody.bytes(), requestHeaders, REQUEST_CAP)
                    requestCut = requestBody.cut || decodeCut
                    parameters = buildList {
                        if (!requestCut && isForm(requestHeaders)) addAll(formFields(decoded))
                        if (!requestCut && isJsonType(requestHeaders)) addAll(jsonFields(decoded))
                    }.take(FIELD_CAP)
                    requestStored = decoded.isNotEmpty() && sink.keep(ready.size, true, decoded)
                    publish(response = false, body = null)
                    step = Step.RESPONSE_HEADERS
                }
                Step.RESPONSE_HEADERS -> {
                    if (server.overflow()) {
                        stopped = true
                        return
                    }
                    val mark = serverMark
                    val header = server.takeHeaders() ?: return
                    val parsed = parseStatus(header) ?: run { stopped = true; return }
                    if (responseStartAt == null) responseStartAt = mark ?: clock()
                    serverMark = if (server.available() > 0) clock() else null
                    responseHeaderBytes += header.length + 4L
                    status = parsed.first
                    statusText = parsed.second
                    responseHeaders = parsed.third
                    if (status in 100..199 && status != 101) continue
                    if (status == 101 || method == "CONNECT") {
                        publish(response = true, body = BodyResult.NONE, endedAt = clock())
                        stopped = true
                        return
                    }
                    responseBody = BodyReader(plan(responseHeaders, request = false, method = method, status = status), RESPONSE_CAP)
                    step = Step.RESPONSE_BODY
                }
                Step.RESPONSE_BODY -> {
                    if (!responseBody.pump(server)) return
                    val end = clock()
                    if (server.available() == 0) serverMark = null
                    val (decoded, decodeCut) = decodeBody(responseBody.bytes(), responseHeaders, RESPONSE_CAP)
                    val stored = decoded.isNotEmpty() && sink.keep(ready.lastIndex.coerceAtLeast(0), false, decoded)
                    publish(
                        response = true,
                        body = BodyResult(stored, responseBody.cut || decodeCut, responseBody.consumed),
                        endedAt = end,
                    )
                    step = Step.REQUEST_HEADERS
                }
            }
        }
    }

    private class BodyResult(val stored: Boolean, val cut: Boolean, val wireBytes: Long) {
        companion object {
            val NONE = BodyResult(false, false, 0)
        }
    }

    private fun publish(response: Boolean, body: BodyResult?, endedAt: Long? = null) {
        val exchange = HttpExchange(
            method = method,
            path = path,
            query = queryFields(target),
            requestHeaders = requestHeaders,
            parameters = parameters,
            status = if (response) status else null,
            responseHeaders = if (response) responseHeaders else emptyList(),
            responseCut = body?.cut ?: false,
            requestCut = requestCut,
            requestBodyStored = requestStored,
            responseBodyStored = body?.stored ?: false,
            httpVersion = version,
            target = target,
            statusText = if (response) statusText else null,
            startedAt = startedAt,
            requestEndAt = requestEndAt,
            responseStartAt = if (response) responseStartAt else null,
            endedAt = endedAt,
            requestHeaderBytes = requestHeaderBytes,
            requestBodyBytes = requestBody.consumed,
            responseHeaderBytes = if (response) responseHeaderBytes else null,
            responseBodyBytes = if (response) body?.wireBytes else null,
        )
        if (open) ready[ready.lastIndex] = exchange else ready += exchange
        open = !response
    }

    private enum class Step { REQUEST_HEADERS, REQUEST_BODY, RESPONSE_HEADERS, RESPONSE_BODY }

    companion object {
        const val MAX_EXCHANGES = 24
        /** The most of a request body that is read and kept. */
        const val REQUEST_CAP = 1024 * 1024
        /** The most of a response body that is read and kept, before and after it is decompressed. */
        const val RESPONSE_CAP = 4 * 1024 * 1024
        private const val HEADER_CAP = 32 * 1024
        private const val FIELD_CAP = 40
        private val HEADER_END = byteArrayOf(13, 10, 13, 10)
        private val LINE_END = byteArrayOf(13, 10)
    }

    private class ByteCursor {
        private var data = ByteArray(0)
        private var offset = 0

        fun append(more: ByteArray) {
            if (offset > 8192) {
                data = data.copyOfRange(offset, data.size)
                offset = 0
            }
            data += more
        }

        fun available(): Int = data.size - offset

        fun indexOf(pattern: ByteArray): Int {
            val last = data.size - pattern.size
            var index = offset
            while (index <= last) {
                var matches = true
                for (step in pattern.indices) {
                    if (data[index + step] != pattern[step]) {
                        matches = false
                        break
                    }
                }
                if (matches) return index - offset
                index++
            }
            return -1
        }

        fun overflow(): Boolean {
            val end = indexOf(HEADER_END)
            return end > HEADER_CAP || (end < 0 && available() > HEADER_CAP)
        }

        fun takeHeaders(): String? {
            val end = indexOf(HEADER_END)
            if (end < 0 || end > HEADER_CAP) return null
            val text = String(data, offset, end, Charsets.ISO_8859_1)
            offset += end + HEADER_END.size
            return text
        }

        fun read(count: Int): ByteArray {
            val out = data.copyOfRange(offset, offset + count)
            offset += count
            return out
        }
    }

    private class BodyReader(private val plan: BodyPlan, private val limit: Int) {
        private val kept = ByteArrayOutputStream()
        var cut: Boolean = false
        /** Every byte the body took on the wire, chunk framing included. */
        var consumed: Long = 0
            private set
        private var done = false
        private var atClose = false
        private var fixedLeft = (plan as? BodyPlan.Fixed)?.length ?: 0
        private var chunkLeft = 0
        private var phase = Chunk.SIZE

        fun bytes(): ByteArray = kept.toByteArray()

        fun finishAtClose() {
            atClose = true
        }

        private fun take(src: ByteCursor, count: Int): ByteArray {
            consumed += count
            return src.read(count)
        }

        fun pump(src: ByteCursor): Boolean {
            if (done) return true
            when (plan) {
                BodyPlan.None -> done = true
                BodyPlan.UntilClose -> {
                    if (src.available() > 0) keep(take(src, src.available()))
                    if (atClose) done = true
                }
                is BodyPlan.Fixed -> {
                    while (fixedLeft > 0 && src.available() > 0) {
                        val count = min(fixedLeft, src.available())
                        keep(take(src, count))
                        fixedLeft -= count
                    }
                    if (fixedLeft == 0) done = true
                }
                BodyPlan.Chunked -> pumpChunks(src)
            }
            return done
        }

        private fun pumpChunks(src: ByteCursor) {
            while (!done && src.available() > 0) {
                when (phase) {
                    Chunk.SIZE -> {
                        val end = src.indexOf(LINE_END)
                        if (end < 0) return
                        val line = String(take(src, end), Charsets.US_ASCII).substringBefore(';').trim()
                        take(src, 2)
                        chunkLeft = line.toIntOrNull(16) ?: run { done = true; return }
                        phase = if (chunkLeft == 0) Chunk.TRAILERS else Chunk.DATA
                    }
                    Chunk.DATA -> {
                        if (chunkLeft == 0) {
                            phase = Chunk.AFTER
                            continue
                        }
                        val count = min(chunkLeft, src.available())
                        if (count == 0) return
                        keep(take(src, count))
                        chunkLeft -= count
                        if (chunkLeft == 0) phase = Chunk.AFTER
                    }
                    Chunk.AFTER -> {
                        if (src.available() < 2) return
                        take(src, 2)
                        phase = Chunk.SIZE
                    }
                    Chunk.TRAILERS -> {
                        if (src.available() < 2) return
                        if (src.indexOf(LINE_END) == 0) {
                            take(src, 2)
                            done = true
                            return
                        }
                        val end = src.indexOf(HEADER_END)
                        if (end < 0) {
                            if (src.available() > HEADER_CAP) done = true
                            return
                        }
                        take(src, end + HEADER_END.size)
                        done = true
                    }
                }
            }
        }

        private fun keep(chunk: ByteArray) {
            val room = limit - kept.size()
            if (room <= 0) {
                cut = true
                return
            }
            if (chunk.size <= room) kept.write(chunk) else {
                kept.write(chunk, 0, room)
                cut = true
            }
        }

        private enum class Chunk { SIZE, DATA, AFTER, TRAILERS }
    }
}

private sealed class BodyPlan {
    data object None : BodyPlan()
    data object Chunked : BodyPlan()
    data object UntilClose : BodyPlan()
    data class Fixed(val length: Int) : BodyPlan()
}

private fun plan(headers: List<HttpField>, request: Boolean, method: String, status: Int): BodyPlan {
    if (!request && (method == "HEAD" || status == 204 || status == 304 || status in 100..199)) return BodyPlan.None
    if (headers.any { it.name.equals("Transfer-Encoding", true) && it.value.contains("chunked", true) }) return BodyPlan.Chunked
    val length = headers.firstOrNull { it.name.equals("Content-Length", true) }?.value?.trim()?.toIntOrNull()
    if (length != null && length >= 0) return BodyPlan.Fixed(length)
    return if (request) BodyPlan.None else BodyPlan.UntilClose
}

private class RequestLine(val method: String, val target: String, val version: String?, val headers: List<HttpField>)

private fun parseRequest(header: String): RequestLine? {
    val lines = header.split("\r\n")
    val parts = lines.firstOrNull()?.split(' ') ?: return null
    if (parts.size < 2 || parts[0] !in setOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE")) return null
    return RequestLine(parts[0], parts[1], parts.getOrNull(2)?.takeIf { it.startsWith("HTTP/") }, headerFields(lines.drop(1)))
}

private fun parseStatus(header: String): Triple<Int, String?, List<HttpField>>? {
    val lines = header.split("\r\n")
    val parts = lines.firstOrNull()?.split(' ') ?: return null
    if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
    val status = parts[1].toIntOrNull() ?: return null
    val text = parts.drop(2).joinToString(" ").trim().takeIf { it.isNotEmpty() }
    return Triple(status, text, headerFields(lines.drop(1)))
}

private fun headerFields(lines: List<String>): List<HttpField> = lines.mapNotNull { line ->
    val colon = line.indexOf(':')
    if (colon <= 0) return@mapNotNull null
    HttpField(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
}

private fun queryFields(target: String): List<HttpField> {
    val query = target.substringAfter('?', "")
    if (query.isEmpty()) return emptyList()
    return query.split('&').mapNotNull { pair ->
        if (pair.isEmpty()) return@mapNotNull null
        HttpField(decode(pair.substringBefore('=')), decode(pair.substringAfter('=', "")))
    }.take(40)
}

private fun formFields(body: ByteArray): List<HttpField> {
    if (body.isEmpty()) return emptyList()
    return String(body, Charsets.UTF_8).split('&').mapNotNull { pair ->
        if (pair.isEmpty()) return@mapNotNull null
        HttpField(decode(pair.substringBefore('=')), decode(pair.substringAfter('=', "")))
    }.take(40)
}

private fun jsonFields(body: ByteArray): List<HttpField> {
    val text = body.toString(Charsets.UTF_8).trim()
    if (!text.startsWith("{")) return emptyList()
    val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    return obj.keys().asSequence().mapNotNull { key ->
        val value = obj.get(key)
        if (value is JSONObject || value is JSONArray) null else HttpField(key, value.toString())
    }.take(40).toList()
}

private fun isForm(headers: List<HttpField>): Boolean =
    headers.any { it.name.equals("Content-Type", true) && it.value.contains("application/x-www-form-urlencoded", true) }

private fun isJsonType(headers: List<HttpField>): Boolean =
    headers.any { it.name.equals("Content-Type", true) && it.value.contains("json", true) }

/**
 * Undoes Content-Encoding, keeping at most [cap] bytes. The flag says the result
 * was cut. A body that cannot be decoded at all comes back as it was.
 */
internal fun decodeBody(body: ByteArray, headers: List<HttpField>, cap: Int): Pair<ByteArray, Boolean> {
    if (body.isEmpty()) return body to false
    val encoding = headers.firstOrNull { it.name.equals("Content-Encoding", true) }?.value?.lowercase().orEmpty()
    val stream: () -> java.io.InputStream? = when {
        "br" in encoding || "brotli" in encoding -> { { BrotliInputStream(ByteArrayInputStream(body)) } }
        "gzip" in encoding || (body.size > 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()) ->
            { { GZIPInputStream(ByteArrayInputStream(body)) } }
        "deflate" in encoding -> { { InflaterInputStream(ByteArrayInputStream(body)) } }
        else -> { { null } }
    }
    val decoded = readCapped(stream, cap)
        ?: if ("deflate" in encoding) readCapped({ InflaterInputStream(ByteArrayInputStream(body), Inflater(true)) }, cap) else null
    if (decoded != null) return decoded
    return if (body.size > cap) body.copyOf(cap) to true else body to false
}

/** Reads up to [cap] bytes. What was read before a damaged or cut stream failed is kept; null when nothing was. */
private fun readCapped(open: () -> java.io.InputStream?, cap: Int): Pair<ByteArray, Boolean>? {
    val out = ByteArrayOutputStream()
    var cut = false
    try {
        val input = open() ?: return null
        input.use {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                val room = cap - out.size()
                if (count > room) {
                    out.write(buffer, 0, room)
                    cut = true
                    break
                }
                out.write(buffer, 0, count)
            }
        }
    } catch (_: Exception) {
        if (out.size() == 0) return null
        cut = true
    }
    return out.toByteArray() to cut
}

/** Text worth showing. Binary bodies, including ones we could not decompress, are skipped. */
internal fun readableText(body: ByteArray): String? {
    if (body.isEmpty()) return null
    val text = body.toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
    if (text.isEmpty()) return null
    val sample = text.take(512)
    var bad = 0
    for (char in sample) {
        if (char == '\uFFFD' || (char.code < 32 && char != '\n' && char != '\r' && char != '\t')) bad++
    }
    if (bad > sample.length / 10) return null
    return text
}

internal fun prettyJson(body: ByteArray): Pair<String, Boolean>? {
    var text = body.toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
    if (text.startsWith(")]}',")) text = text.removePrefix(")]}',").trim()
    if (text.startsWith("while(1);")) text = text.removePrefix("while(1);").trim()
    if (!text.startsWith("{") && !text.startsWith("[")) return null
    val pretty = try {
        if (text.startsWith("{")) JSONObject(text).toString(2) else JSONArray(text).toString(2)
    } catch (_: Exception) {
        return text to false
    }
    return pretty to false
}

private fun decode(text: String): String = try {
    URLDecoder.decode(text, Charsets.UTF_8.name())
} catch (_: Exception) {
    text
}

private fun List<HttpField>.toJsonArray(): JSONArray = JSONArray().apply { forEach { put(it.toJson()) } }

private fun JSONObject.fields(key: String): List<HttpField> {
    if (!has(key)) return emptyList()
    val array = getJSONArray(key)
    return (0 until array.length()).mapNotNull { HttpField.fromJson(array.getJSONObject(it)) }
}
