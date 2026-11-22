package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Reads a stored body: the flow, the exchange's index, and whether it is the request's. */
fun interface BodySource {
    fun body(flow: TrafficFlow, index: Int, request: Boolean): ByteArray?
}

/** The URL an exchange went to, rebuilt from the connection and its Host header. */
fun requestUrl(flow: TrafficFlow, exchange: HttpExchange): String {
    val target = exchange.target ?: run {
        val query = exchange.query.joinToString("&") { "${encode(it.name)}=${encode(it.value)}" }
        if (query.isEmpty()) exchange.path else "${exchange.path}?$query"
    }
    if (target.startsWith("http://") || target.startsWith("https://")) return target
    val scheme = if (flow.tls) "https" else "http"
    val hostHeader = exchange.header("Host", response = false)?.trim()?.takeIf { it.isNotEmpty() }
    val authority = hostHeader ?: run {
        val name = flow.host?.takeIf { it.isNotEmpty() } ?: flow.destination.let { if (':' in it) "[$it]" else it }
        val usual = if (flow.tls) 443 else 80
        if (flow.destinationPort == usual) name else "$name:${flow.destinationPort}"
    }
    return "$scheme://$authority${if (target.startsWith("/")) target else "/$target"}"
}

private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** HTTP Archive 1.2, the format Charles, browsers and proxies exchange recordings in. */
object Har {
    fun build(flows: List<TrafficFlow>, bodies: BodySource, version: String): String {
        val entries = JSONArray()
        val ordered = flows.flatMap { flow -> flow.exchanges.indices.map { flow to it } }
            .sortedBy { (flow, index) -> flow.exchanges[index].startedAt ?: flow.startedAt }
        for ((flow, index) in ordered) entries.put(entry(flow, index, bodies))
        val log = JSONObject()
            .put("version", "1.2")
            .put("creator", JSONObject().put("name", "Spectra").put("version", version))
            .put("entries", entries)
        return JSONObject().put("log", log).toString(2)
    }

    private fun entry(flow: TrafficFlow, index: Int, bodies: BodySource): JSONObject {
        val exchange = flow.exchanges[index]
        val start = exchange.startedAt ?: flow.startedAt
        val send = span(exchange.startedAt, exchange.requestEndAt)
        val wait = span(exchange.requestEndAt, exchange.responseStartAt)
        val receive = span(exchange.responseStartAt, exchange.endedAt)
        return JSONObject()
            .put("startedDateTime", isoTime(start))
            .put("time", exchange.durationMs ?: (send + wait + receive))
            .put("request", request(flow, index, exchange, bodies))
            .put("response", response(flow, index, exchange, bodies))
            .put("cache", JSONObject())
            .put(
                "timings",
                JSONObject()
                    .put("blocked", -1)
                    .put("dns", -1)
                    .put("connect", -1)
                    .put("ssl", -1)
                    .put("send", send)
                    .put("wait", wait)
                    .put("receive", receive),
            )
            .put("serverIPAddress", flow.destination)
            .put("connection", flow.sourcePort.toString())
    }

    private fun request(flow: TrafficFlow, index: Int, exchange: HttpExchange, bodies: BodySource): JSONObject {
        val obj = JSONObject()
            .put("method", exchange.method)
            .put("url", requestUrl(flow, exchange))
            .put("httpVersion", exchange.httpVersion ?: "HTTP/1.1")
            .put("cookies", JSONArray())
            .put("headers", fields(exchange.requestHeaders))
            .put("queryString", fields(exchange.query))
            .put("headersSize", exchange.requestHeaderBytes ?: -1)
            .put("bodySize", exchange.requestBodyBytes ?: -1)
        val body = if (exchange.requestBodyStored) bodies.body(flow, index, true) else null
        if (body != null && body.isNotEmpty()) {
            val post = JSONObject().put("mimeType", exchange.header("Content-Type", response = false).orEmpty())
            putText(post, body)
            if (exchange.parameters.isNotEmpty() && exchange.header("Content-Type", false)?.contains("form-urlencoded", true) == true) {
                post.put("params", fields(exchange.parameters))
            }
            obj.put("postData", post)
        }
        return obj
    }

    private fun response(flow: TrafficFlow, index: Int, exchange: HttpExchange, bodies: BodySource): JSONObject {
        val body = if (exchange.responseBodyStored) bodies.body(flow, index, false) else null
        val content = JSONObject()
            .put("mimeType", exchange.header("Content-Type", response = true).orEmpty())
        when {
            body != null -> {
                content.put("size", body.size)
                if (body.isNotEmpty()) putText(content, body)
                exchange.responseBodyBytes?.let { wire -> content.put("compression", body.size - wire) }
            }
            exchange.responseJson != null -> {
                content.put("size", exchange.responseJson.toByteArray().size)
                content.put("text", exchange.responseJson)
            }
            else -> content.put("size", 0)
        }
        if (exchange.responseCut) content.put("comment", "Spectra kept only the start of this body.")
        return JSONObject()
            .put("status", exchange.status ?: 0)
            .put("statusText", exchange.statusText.orEmpty())
            .put("httpVersion", exchange.httpVersion ?: "HTTP/1.1")
            .put("cookies", JSONArray())
            .put("headers", fields(exchange.responseHeaders))
            .put("content", content)
            .put("redirectURL", exchange.header("Location", response = true).orEmpty())
            .put("headersSize", exchange.responseHeaderBytes ?: -1)
            .put("bodySize", exchange.responseBodyBytes ?: -1)
    }

    /** Text when the bytes are UTF-8 a person can read, base64 otherwise. */
    private fun putText(obj: JSONObject, body: ByteArray) {
        val text = utf8(body)
        if (text != null) obj.put("text", text) else {
            obj.put("text", Base64.getEncoder().encodeToString(body))
            obj.put("encoding", "base64")
        }
    }

    private fun fields(list: List<HttpField>): JSONArray =
        JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("value", it.value)) } }

    private fun span(from: Long?, to: Long?): Long = if (from != null && to != null) (to - from).coerceAtLeast(0) else 0

    fun isoTime(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(millis))
    }
}

/** The request as a curl command, to run it again from a terminal. */
object Curl {
    fun command(flow: TrafficFlow, exchange: HttpExchange, body: ByteArray?): String {
        val parts = mutableListOf("curl")
        val hasBody = body != null && body.isNotEmpty()
        val usual = (exchange.method == "GET" && !hasBody) || (exchange.method == "POST" && hasBody)
        if (!usual) parts += "-X ${exchange.method}"
        parts += quote(requestUrl(flow, exchange))
        var compressed = false
        for (field in exchange.requestHeaders) {
            when {
                field.name.equals("Host", true) || field.name.equals("Content-Length", true) -> continue
                field.name.equals("Accept-Encoding", true) -> compressed = true
                field.name.equals("Content-Encoding", true) && hasBody -> continue
                else -> parts += "-H ${quote("${field.name}: ${field.value}")}"
            }
        }
        if (compressed) parts += "--compressed"
        if (hasBody) parts += "--data-binary ${quoteBytes(body!!)}"
        return parts.joinToString(" \\\n  ")
    }

    /** Single quotes keep everything literal; a quote inside closes, escapes and reopens. */
    fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private fun quoteBytes(body: ByteArray): String {
        val text = utf8(body)
        if (text != null && text.none { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) return quote(text)
        return buildString {
            append("$'")
            for (byte in body) {
                val c = byte.toInt() and 0xFF
                when {
                    c == '\''.code -> append("\\'")
                    c == '\\'.code -> append("\\\\")
                    c in 32..126 -> append(c.toChar())
                    else -> append("\\x%02x".format(c))
                }
            }
            append("'")
        }
    }
}

/** The bytes as text when they are valid UTF-8 and not mostly control characters. */
private fun utf8(body: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
    val text = try {
        decoder.decode(java.nio.ByteBuffer.wrap(body)).toString()
    } catch (_: Exception) {
        return null
    }
    val sample = text.take(512)
    val bad = sample.count { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }
    return if (bad > sample.length / 10) null else text
}
