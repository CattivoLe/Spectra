package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Slows the recorded connections down, like Throttle Settings in Charles:
 * a download and an upload rate, and a delay added to everything the phone
 * receives. [preset] names a ready setting; "custom" keeps the numbers given.
 */
data class ThrottleSettings(
    val preset: String = OFF,
    val downKbps: Int = 0,
    val upKbps: Int = 0,
    val latencyMs: Int = 0,
) {
    val enabled: Boolean get() = preset != OFF

    fun toJson(): String = JSONObject()
        .put("preset", preset)
        .put("downKbps", downKbps)
        .put("upKbps", upKbps)
        .put("latencyMs", latencyMs)
        .toString()

    companion object {
        const val OFF = "off"
        const val CUSTOM = "custom"

        /** Ready settings, slowest first: name, download kbit/s, upload kbit/s, added delay. */
        val PRESETS: List<ThrottleSettings> = listOf(
            ThrottleSettings("edge", 240, 200, 400),
            ThrottleSettings("3g", 780, 330, 200),
            ThrottleSettings("fast3g", 1600, 750, 100),
            ThrottleSettings("4g", 9000, 9000, 50),
        )

        fun preset(name: String): ThrottleSettings = PRESETS.firstOrNull { it.preset == name } ?: ThrottleSettings()

        fun fromJson(text: String?): ThrottleSettings = runCatching {
            val obj = JSONObject(text ?: return ThrottleSettings())
            ThrottleSettings(
                preset = obj.optString("preset", OFF),
                downKbps = obj.optInt("downKbps").coerceIn(0, 1_000_000),
                upKbps = obj.optInt("upKbps").coerceIn(0, 1_000_000),
                latencyMs = obj.optInt("latencyMs").coerceIn(0, 10_000),
            )
        }.getOrDefault(ThrottleSettings())
    }
}

/**
 * Token buckets for one recording, shared by every connection: [down] for what
 * goes to the phone, [up] for what goes to the servers. A rate of 0 does not
 * limit that direction.
 */
class Throttle(val settings: ThrottleSettings) {
    val down = Bucket(settings.downKbps)
    val up = Bucket(settings.upKbps)
    val latencyMs: Long get() = if (settings.enabled) settings.latencyMs.toLong() else 0

    class Bucket(kbps: Int) {
        /** Bytes per millisecond; 0 is no limit. */
        private val rate = kbps / 8.0
        /** About 50 ms of data, and never less than one packet, so a full segment can always pass. */
        private val capacity = maxOf(rate * 50, 1500.0)
        private var tokens = capacity
        private var last = -1L

        val limited: Boolean get() = rate > 0

        /** How many of [want] bytes may go at [now]; they are taken from the bucket. */
        fun take(want: Int, now: Long): Int {
            if (!limited || want <= 0) return want
            if (last < 0) last = now
            tokens = minOf(capacity, tokens + (now - last).coerceAtLeast(0) * rate)
            last = now
            val allowed = minOf(want, tokens.toInt())
            tokens -= allowed
            return allowed
        }
    }
}

/**
 * Map Local, as in Charles: requests to a matching host and path are answered
 * from here, with [status], [contentType] and [body], and never reach the
 * server. [host] is a pattern like the TLS host list's; [path] matches the
 * request target (path and query) with `*` for any characters, empty for any.
 */
data class MapRule(
    val id: String,
    val enabled: Boolean = true,
    val host: String,
    val path: String = "",
    val status: Int = 200,
    val contentType: String = "application/json",
    val body: String = "",
) {
    fun matchesHost(name: String?): Boolean = name != null && TlsHostFilter.matches(host, name)

    fun matches(name: String?, target: String): Boolean {
        if (!enabled || !matchesHost(name)) return false
        if (path.isBlank()) return true
        return globMatch(path.trim(), target) || globMatch(path.trim(), target.substringBefore('?'))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("enabled", enabled)
        .put("host", host)
        .put("path", path)
        .put("status", status)
        .put("contentType", contentType)
        .put("body", body)

    companion object {
        fun fromJson(obj: JSONObject): MapRule? = runCatching {
            MapRule(
                id = obj.getString("id"),
                enabled = obj.optBoolean("enabled", true),
                host = obj.getString("host"),
                path = obj.optString("path"),
                status = obj.optInt("status", 200),
                contentType = obj.optString("contentType", "application/json"),
                body = obj.optString("body"),
            )
        }.getOrNull()

        fun listToJson(rules: List<MapRule>): String = JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<MapRule> = runCatching {
            val array = JSONArray(text ?: return emptyList())
            (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        private fun globMatch(pattern: String, text: String): Boolean {
            var p = 0
            var t = 0
            var star = -1
            var mark = 0
            while (t < text.length) {
                when {
                    p < pattern.length && pattern[p] != '*' && pattern[p] == text[t] -> { p++; t++ }
                    p < pattern.length && pattern[p] == '*' -> { star = p++; mark = t }
                    star >= 0 -> { p = star + 1; t = ++mark }
                    else -> return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}

/** The rules in force for one connection, and the responses they make. */
class MapLocal(rules: List<MapRule>) {
    private val active = rules.filter { it.enabled }

    val isEmpty: Boolean get() = active.isEmpty()

    /** True when some rule could answer requests to [host]: such a connection is kept on HTTP/1.1. */
    fun coversHost(host: String?): Boolean = active.any { it.matchesHost(host) }

    fun ruleFor(host: String?, target: String): MapRule? = active.firstOrNull { it.matches(host, target) }

    companion object {
        /** The header every local answer carries, so the recording shows which requests never left the phone. */
        const val HEADER = "X-Spectra-Map-Local"

        /** An HTTP/1.1 response for [rule]. HEAD gets the headers only. */
        fun response(rule: MapRule, method: String): ByteArray {
            val body = rule.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${rule.status} ${reason(rule.status)}\r\n")
                if (rule.contentType.isNotBlank()) append("Content-Type: ${rule.contentType}\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("$HEADER: ${rule.host}${rule.path}\r\n")
                append("\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            return if (method == "HEAD") head else head + body
        }

        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}

/** A request to send again, as recorded or as edited on the Repeat screen. */
data class RepeatRequest(
    val method: String,
    val url: String,
    val headers: List<HttpField>,
    val body: ByteArray?,
) {
    /** Headers as editable text, one `Name: value` a line. */
    fun headerText(): String = headers.joinToString("\n") { "${it.name}: ${it.value}" }

    companion object {
        /** Headers the client sets itself, or that would contradict the body sent. */
        private val SKIPPED = setOf("host", "content-length", "connection", "transfer-encoding", "keep-alive", "upgrade", "te")

        fun from(flow: TrafficFlow, exchange: HttpExchange, body: ByteArray?): RepeatRequest = RepeatRequest(
            method = exchange.method,
            url = requestUrl(flow, exchange),
            headers = exchange.requestHeaders.filter { field ->
                !field.name.startsWith(":") && field.name.lowercase() !in SKIPPED &&
                    !(body != null && field.name.equals("Content-Encoding", true))
            },
            body = body?.takeIf { it.isNotEmpty() },
        )

        /** Reads `Name: value` lines back; lines without a colon are left out. */
        fun parseHeaders(text: String): List<HttpField> = text.lines().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            HttpField(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
        }.filter { it.name.lowercase() !in SKIPPED }
    }

    override fun equals(other: Any?): Boolean = other is RepeatRequest && method == other.method && url == other.url &&
        headers == other.headers && (body?.contentEquals(other.body) ?: (other.body == null))

    override fun hashCode(): Int = (method + url).hashCode()
}

/** What came back when a request was sent again. */
data class RepeatResult(
    val status: Int?,
    val statusText: String?,
    val headers: List<HttpField>,
    val body: ByteArray,
    val cut: Boolean,
    val durationMs: Long,
    val error: String?,
)
