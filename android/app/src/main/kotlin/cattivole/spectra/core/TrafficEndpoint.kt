package cattivole.spectra.core

import java.net.URLDecoder

/**
 * What a request asks for, whoever it is about: the method, the server and the path without its query, with the
 * changing parts (numbers, UUIDs, long tokens) written as `{id}`. Two requests with the same one are the same call.
 * Mirrors the iOS `Endpoint`: both follow one rule, checked by the same table of paths in the tests.
 */
data class Endpoint(val method: String, val host: String, val path: String) {
    /** `GET api.example.com/v1/users/{id}`. */
    val key: String get() = "$method $host$path"

    companion object {
        const val PLACEHOLDER = "{id}"

        fun of(flow: TrafficFlow, exchange: HttpExchange): Endpoint {
            val method = exchange.method.uppercase()
            var target = exchange.target ?: exchange.path
            var authority = exchange.header(":authority", response = false) ?: exchange.header("Host", response = false)
            if (method == "CONNECT") {
                authority = target
                target = ""
            } else if (target.startsWith("http") && "://" in target) {
                // A request to a proxy names the whole address.
                val rest = target.substringAfter("://")
                val cut = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
                authority = rest.substring(0, cut)
                target = rest.substring(cut)
            }
            // One HTTP/2 connection can carry requests to several names, so the request's own name comes first.
            val name = authority?.let(::cleanHost)?.takeIf { it.isNotEmpty() }
                ?: cleanHost(flow.host?.takeIf { it.isNotEmpty() } ?: flow.destination)
            return Endpoint(method, name, if (method == "CONNECT") "" else template(target))
        }

        private fun cleanHost(text: String): String {
            var host = text.trim().lowercase().substringAfterLast('@')
            for (port in listOf(":443", ":80")) {
                if ((host.endsWith(port) && host.count { it == ':' } == 1) || host.endsWith("]$port")) {
                    host = host.dropLast(port.length)
                    break
                }
            }
            return host.trimEnd('.')
        }

        // The path.

        private val cache = HashMap<String, String>()
        private const val CACHE_LIMIT = 20_000

        /** The path of a request target with its changing parts as `{id}`. Remembered: a live list asks again and again. */
        fun template(target: String): String {
            synchronized(cache) { cache[target] }?.let { return it }
            val made = build(target)
            synchronized(cache) {
                if (cache.size >= CACHE_LIMIT) cache.clear()
                cache[target] = made
            }
            return made
        }

        private fun build(target: String): String {
            val path = target.substringBefore('?').substringBefore('#')
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return "/"
            val out = StringBuilder()
            var previous: String? = null
            segments.forEachIndexed { index, raw ->
                val segment = readable(raw.substringBefore(';'))
                out.append('/').append(masked(segment, previous, index == segments.lastIndex))
                previous = segment
            }
            return out.toString()
        }

        /** A segment as it reads, unless decoding would change where the path splits. */
        private fun readable(raw: String): String {
            if ('%' !in raw) return raw
            val decoded = percentDecoded(raw) ?: return raw
            return if (decoded.any { it == '/' || it == '?' || it == '#' || it.code < 32 }) raw else decoded
        }

        /** Percent-decoding alone (a plus stays a plus); null when the text is not valid UTF-8 behind it. */
        internal fun percentDecoded(raw: String): String? = runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").takeIf { '�' !in it }
        }.getOrNull()

        private val VERSIONED = setOf("api", "rest", "v", "ver", "version")

        private fun masked(segment: String, previous: String?, last: Boolean): String {
            if (isNumber(segment)) {
                // `/2/tweets`, `/api/3/movie`: a version, not somebody's number.
                val version = segment.length <= 2 && !last && (previous == null || previous.lowercase() in VERSIONED)
                return if (version) segment else PLACEHOLDER
            }
            variable(segment)?.let { return it }
            // `8f3a…e1.png`: the name changes, the kind of file does not.
            val dot = segment.lastIndexOf('.')
            if (dot > 0) {
                val ext = segment.substring(dot + 1)
                val base = segment.substring(0, dot)
                if (ext.length in 1..5 && ext.all(::isAlphanumeric) && ext.any(::isLetter)) {
                    val changed = if (isNumber(base)) PLACEHOLDER else variable(base)
                    if (changed != null) return "$changed.$ext"
                }
            }
            return segment
        }

        /** A segment that names one thing among many, with the part that changes as `{id}`; null for an ordinary word. */
        private fun variable(text: String): String? {
            if (isIdentifier(text)) return PLACEHOLDER
            // `user-<uuid>`, `order_100482`: a short word, then what changes.
            val cut = text.indexOfFirst { !isLetter(it) }
            if (cut in 2..12 && text[cut] in "-_:") {
                val rest = text.substring(cut + 1)
                if ((isNumber(rest) && rest.length >= 5) || isIdentifier(rest)) return text.substring(0, cut + 1) + PLACEHOLDER
            }
            // Also for what must never be shown: a key or an e-mail address in the path.
            if (text.length >= 16 && TrafficSecrets.kindOfValue(text) != null) return PLACEHOLDER
            if (TrafficSecrets.isEmail(text)) return PLACEHOLDER
            return null
        }

        /** A UUID, a long hex number, a list or chain of numbers (dates, coordinates), or a long random-looking token. */
        fun isIdentifier(text: String): Boolean = isUuid(text) || isHex(text) || isNumbers(text) || isToken(text)

        fun isNumber(text: String): Boolean {
            val digits = if (text.startsWith('-') || text.startsWith('+')) text.substring(1) else text
            return digits.isNotEmpty() && digits.all(::isDigit)
        }

        /** `12,15,18`, `2026-11-23`, `55.7558,37.6173`: digits and separators only. Short ones (`1.1`, `2.0.1`) are versions. */
        private fun isNumbers(text: String): Boolean {
            if (text.any { !isDigit(it) && it !in ".,:+-_" }) return false
            if (text.count(::isDigit) >= 8) return true
            val parts = text.split(',')
            return parts.size >= 2 && parts.all(::isNumber)
        }

        private fun isUuid(text: String): Boolean {
            val bare = if (text.length == 38 && text.startsWith('{') && text.endsWith('}')) text.substring(1, 37) else text
            return bare.length == 36 && bare.withIndex().all { (index, c) ->
                if (index == 8 || index == 13 || index == 18 || index == 23) c == '-' else isHexDigit(c)
            }
        }

        private fun isHex(text: String): Boolean {
            val digits = if (text.startsWith("0x") || text.startsWith("0X")) text.substring(2) else text
            return digits.length >= 12 && digits.all(::isHexDigit) && digits.any(::isDigit)
        }

        /** Twenty characters or more in which letters and digits keep changing places: words and slugs do not. */
        private fun isToken(text: String): Boolean {
            if (text.length < 20) return false
            if (text.startsWith("eyJ") && text.all { isAlphanumeric(it) || it in "._-=" }) return true
            if (text.any { !isAlphanumeric(it) && it !in "_-~=+" }) return false
            fun kind(c: Char) = when (c) {
                in '0'..'9' -> 1
                in 'a'..'z' -> 2
                in 'A'..'Z' -> 3
                else -> 0
            }
            var changes = 0
            var digitRuns = 0
            var before = 0
            for (c in text) {
                val now = kind(c)
                if (now == 1 && before != 1) digitRuns += 1
                if (now != 0 && before != 0 && now != before) changes += 1
                before = now
            }
            return digitRuns >= 2 && changes * 4 >= text.length
        }

        private fun isDigit(c: Char) = c in '0'..'9'
        private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
        private fun isAlphanumeric(c: Char) = isDigit(c) || isLetter(c)
        private fun isHexDigit(c: Char) = isDigit(c) || c in 'a'..'f' || c in 'A'..'F'
    }
}

/** One HTTP request of a connection, as a row of its own. */
data class RequestHit(val flow: TrafficFlow, val index: Int) {
    val exchange: HttpExchange get() = flow.exchanges[index]
    val at: Long get() = exchange.startedAt ?: flow.lastAt

    companion object {
        fun all(flows: List<TrafficFlow>): List<RequestHit> = flows.flatMap { flow -> flow.exchanges.indices.map { RequestHit(flow, it) } }
    }
}

/** A connection that stays open and talks both ways: how long it lasted says nothing about speed. */
val HttpExchange.isWebSocket: Boolean get() = status == 101 || messages.isNotEmpty()

/** An answer that is meant to last: a WebSocket, server-sent events, sound or video. */
val HttpExchange.isStream: Boolean
    get() {
        if (isWebSocket || method.uppercase() == "CONNECT") return true
        val type = header("Content-Type", response = true)?.lowercase().orEmpty()
        return type.startsWith("text/event-stream") || type.startsWith("video/") || type.startsWith("audio/")
    }

/** The requests of one endpoint in a recording. Mirrors the iOS `EndpointGroup`. */
class EndpointGroup(val endpoint: Endpoint, unordered: List<RequestHit>, isShown: (RequestHit) -> Boolean = { true }) {
    /** Every request of it, newest first: what the numbers count. */
    val hits: List<RequestHit> = unordered.sortedByDescending { it.at }
    /** Those that pass the list's filter, newest first: the rows under it. */
    val shown: List<RequestHit> = hits.filter(isShown)
    val errors: Int = hits.count { RequestFilter.failed(it.exchange, it.flow) }
    /** The middle duration and the longest one, in milliseconds. Streams are left out of both. */
    val typicalMs: Long?
    val worstMs: Long?
    val bytes: Long = hits.sumOf { (it.exchange.requestSize ?: 0) + (it.exchange.responseSize ?: 0) }
    val lastAt: Long = hits.firstOrNull()?.at ?: 0
    val firstAt: Long = hits.lastOrNull()?.at ?: 0
    val webSocket: Boolean = hits.any { it.exchange.isWebSocket }
    val http2: Boolean = hits.any { it.exchange.httpVersion == Http2Assembler.VERSION }

    init {
        val times = hits.mapNotNull { hit -> if (hit.exchange.isStream) null else hit.exchange.durationMs }.sorted()
        typicalMs = median(times)
        worstMs = times.lastOrNull()
    }

    val key: String get() = endpoint.key

    /** The statuses its answers had, the most frequent first; 0 stands for no answer on a connection that is over. */
    val statuses: List<Pair<Int, Int>>
        get() = hits.mapNotNull { hit -> hit.exchange.status ?: if (hit.flow.closed) 0 else null }
            .groupingBy { it }.eachCount().toList().sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })

    companion object {
        /** The middle value of a sorted list; of two middle ones, the lower. */
        fun median(sorted: List<Long>): Long? = if (sorted.isEmpty()) null else sorted[(sorted.size - 1) / 2]

        /**
         * The endpoints of [flows] with a request the filter lets through: the most requested first, then by name, so
         * that a live list does not jump about with every request.
         */
        fun build(flows: List<TrafficFlow>, filter: RequestFilter = RequestFilter()): List<EndpointGroup> {
            val order = LinkedHashMap<Endpoint, MutableList<RequestHit>>()
            for (hit in RequestHit.all(flows)) {
                if (hit.exchange.method.isNotEmpty()) order.getOrPut(Endpoint.of(hit.flow, hit.exchange)) { mutableListOf() }.add(hit)
            }
            return order.map { (endpoint, hits) -> EndpointGroup(endpoint, hits) { filter.passes(it.exchange, it.flow) } }
                .filter { it.shown.isNotEmpty() }
                .sortedWith(compareByDescending<EndpointGroup> { it.hits.size }.thenBy { it.key })
        }

        /** One endpoint of [flows], whole. */
        fun find(key: String, flows: List<TrafficFlow>): EndpointGroup? {
            val hits = RequestHit.all(flows).filter { it.exchange.method.isNotEmpty() && Endpoint.of(it.flow, it.exchange).key == key }
            return hits.firstOrNull()?.let { EndpointGroup(Endpoint.of(it.flow, it.exchange), hits) }
        }
    }
}
