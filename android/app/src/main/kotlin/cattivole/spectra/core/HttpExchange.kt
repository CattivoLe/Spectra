package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
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
 * [responseJson] is set only when the body is JSON and was not cut short.
 */
data class HttpExchange(
    val method: String,
    val path: String,
    val query: List<HttpField>,
    val requestHeaders: List<HttpField>,
    val parameters: List<HttpField>,
    val status: Int?,
    val responseHeaders: List<HttpField>,
    val responseJson: String?,
    val responseCut: Boolean,
) {
    val summary: String get() = "$method $path"

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
                responseJson = if (obj.isNull("responseJson")) null else obj.getString("responseJson").takeIf { it.isNotEmpty() },
                responseCut = obj.optBoolean("responseCut"),
            )
        }.getOrNull()
    }
}

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
 * Splits a cleartext HTTP byte stream into requests and responses.
 * Bodies are kept only up to a cap; a larger body is skipped so the next
 * message still lines up, and its JSON is not shown.
 */
class HttpAssembler {
    private val client = ByteCursor()
    private val server = ByteCursor()
    private val ready = mutableListOf<HttpExchange>()
    private var step = Step.REQUEST_HEADERS
    private var open = false
    private var stopped = false
    private var method = ""
    private var path = ""
    private var target = ""
    private var requestHeaders = emptyList<HttpField>()
    private var requestBody = BodyReader(BodyPlan.None, BODY_CAP)
    private var status = 0
    private var responseHeaders = emptyList<HttpField>()
    private var responseBody = BodyReader(BodyPlan.None, BODY_CAP)

    fun exchanges(): List<HttpExchange> = ready.toList()

    fun client(data: ByteArray) {
        if (stopped || data.isEmpty()) return
        client.append(data)
        pump()
    }

    fun server(data: ByteArray) {
        if (stopped || data.isEmpty()) return
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
                    val header = client.takeHeaders() ?: return
                    val parsed = parseRequest(header) ?: run { stopped = true; return }
                    if (ready.size >= MAX_EXCHANGES) {
                        stopped = true
                        return
                    }
                    method = parsed.first
                    target = parsed.second
                    path = target.substringBefore('?').ifEmpty { "/" }
                    requestHeaders = parsed.third
                    requestBody = BodyReader(plan(requestHeaders, request = true, method = method, status = 0), BODY_CAP)
                    step = Step.REQUEST_BODY
                }
                Step.REQUEST_BODY -> {
                    if (!requestBody.pump(client)) return
                    publish(status = null, responseHeaders = emptyList(), body = null, cut = false)
                    step = Step.RESPONSE_HEADERS
                }
                Step.RESPONSE_HEADERS -> {
                    if (server.overflow()) {
                        stopped = true
                        return
                    }
                    val header = server.takeHeaders() ?: return
                    val parsed = parseStatus(header) ?: run { stopped = true; return }
                    status = parsed.first
                    responseHeaders = parsed.second
                    if (status in 100..199 && status != 101) continue
                    if (status == 101 || method == "CONNECT") {
                        publish(status, responseHeaders, body = null, cut = false, complete = true)
                        stopped = true
                        return
                    }
                    responseBody = BodyReader(plan(responseHeaders, request = false, method = method, status = status), BODY_CAP)
                    step = Step.RESPONSE_BODY
                }
                Step.RESPONSE_BODY -> {
                    if (!responseBody.pump(server)) return
                    val decoded = if (responseBody.cut) null else inflate(responseBody.bytes(), responseHeaders)
                    val json = decoded?.let { prettyJson(it) }
                    publish(status, responseHeaders, json?.first, responseBody.cut || json?.second == true, complete = true)
                    step = Step.REQUEST_HEADERS
                }
            }
        }
    }

    private fun publish(status: Int?, responseHeaders: List<HttpField>, body: String?, cut: Boolean, complete: Boolean = false) {
        val raw = if (requestBody.cut) ByteArray(0) else inflate(requestBody.bytes(), requestHeaders)
        val parameters = buildList {
            if (isForm(requestHeaders)) addAll(formFields(raw))
            if (isJsonType(requestHeaders)) addAll(jsonFields(raw))
        }.take(FIELD_CAP)
        val exchange = HttpExchange(
            method = method,
            path = path,
            query = queryFields(target),
            requestHeaders = requestHeaders,
            parameters = parameters,
            status = status,
            responseHeaders = responseHeaders,
            responseJson = body,
            responseCut = cut,
        )
        if (open) ready[ready.lastIndex] = exchange else ready += exchange
        open = !complete
    }

    private enum class Step { REQUEST_HEADERS, REQUEST_BODY, RESPONSE_HEADERS, RESPONSE_BODY }

    private companion object {
        const val MAX_EXCHANGES = 24
        const val BODY_CAP = 64 * 1024
        const val HEADER_CAP = 32 * 1024
        const val FIELD_CAP = 40
        const val JSON_CAP = 48_000
        val METHODS = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE")
        val HEADER_END = byteArrayOf(13, 10, 13, 10)
        val LINE_END = byteArrayOf(13, 10)
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
        private var done = false
        private var atClose = false
        private var fixedLeft = (plan as? BodyPlan.Fixed)?.length ?: 0
        private var chunkLeft = 0
        private var phase = Chunk.SIZE

        fun bytes(): ByteArray = kept.toByteArray()

        fun finishAtClose() {
            atClose = true
        }

        fun pump(src: ByteCursor): Boolean {
            if (done) return true
            when (plan) {
                BodyPlan.None -> done = true
                BodyPlan.UntilClose -> {
                    keepSkipping(src, src.available())
                    if (atClose) done = true
                }
                is BodyPlan.Fixed -> {
                    while (fixedLeft > 0 && src.available() > 0) {
                        val count = min(fixedLeft, src.available())
                        keep(src.read(count))
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
                        val line = String(src.read(end), Charsets.US_ASCII).substringBefore(';').trim()
                        src.read(2)
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
                        keep(src.read(count))
                        chunkLeft -= count
                        if (chunkLeft == 0) phase = Chunk.AFTER
                    }
                    Chunk.AFTER -> {
                        if (src.available() < 2) return
                        src.read(2)
                        phase = Chunk.SIZE
                    }
                    Chunk.TRAILERS -> {
                        if (src.available() < 2) return
                        if (src.indexOf(LINE_END) == 0) {
                            src.read(2)
                            done = true
                            return
                        }
                        val end = src.indexOf(HEADER_END)
                        if (end < 0) {
                            if (src.available() > HEADER_CAP) done = true
                            return
                        }
                        src.read(end + HEADER_END.size)
                        done = true
                    }
                }
            }
        }

        private fun keepSkipping(src: ByteCursor, count: Int) {
            if (count <= 0) return
            keep(src.read(count))
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

private fun parseRequest(header: String): Triple<String, String, List<HttpField>>? {
    val lines = header.split("\r\n")
    val parts = lines.firstOrNull()?.split(' ') ?: return null
    if (parts.size < 2 || parts[0] !in setOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE")) return null
    return Triple(parts[0], parts[1], headerFields(lines.drop(1)))
}

private fun parseStatus(header: String): Pair<Int, List<HttpField>>? {
    val lines = header.split("\r\n")
    val parts = lines.firstOrNull()?.split(' ') ?: return null
    if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
    val status = parts[1].toIntOrNull() ?: return null
    return status to headerFields(lines.drop(1))
}

private fun headerFields(lines: List<String>): List<HttpField> = lines.mapNotNull { line ->
    val colon = line.indexOf(':')
    if (colon <= 0) return@mapNotNull null
    HttpField(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
}.take(40)

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

private fun inflate(body: ByteArray, headers: List<HttpField>): ByteArray {
    if (body.isEmpty()) return body
    val encoding = headers.firstOrNull { it.name.equals("Content-Encoding", true) }?.value?.lowercase().orEmpty()
    return try {
        when {
            "gzip" in encoding -> GZIPInputStream(ByteArrayInputStream(body)).use { it.readBytes() }
            "deflate" in encoding -> InflaterInputStream(ByteArrayInputStream(body)).use { it.readBytes() }
            else -> body
        }
    } catch (_: Exception) {
        body
    }
}

private fun prettyJson(body: ByteArray): Pair<String, Boolean>? {
    val text = body.toString(Charsets.UTF_8).trim()
    if (!text.startsWith("{") && !text.startsWith("[")) return null
    val pretty = try {
        if (text.startsWith("{")) JSONObject(text).toString(2) else JSONArray(text).toString(2)
    } catch (_: Exception) {
        return null
    }
    return if (pretty.length > 48_000) pretty.take(48_000) to true else pretty to false
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
