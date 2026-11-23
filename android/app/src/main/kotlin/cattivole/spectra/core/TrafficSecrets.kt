package cattivole.spectra.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Passwords, tokens and keys in recorded requests: by the name of the field that carries them, and by their own look.
 * What is found is named by its kind and its place only. The value is never kept, shown or handed on.
 * Mirrors the iOS `TrafficSecrets`.
 */
object TrafficSecrets {
    enum class Kind { PASSWORD, TOKEN, KEY, SESSION, CARD, PRIVATE_KEY, EMAIL }

    sealed class Place {
        data object Path : Place()
        data class Query(val name: String) : Place()
        data class RequestHeader(val name: String) : Place()
        data class ResponseHeader(val name: String) : Place()
        /** A field of the body by name; empty when the value alone gave it away. */
        data class RequestBody(val name: String) : Place()
        data class ResponseBody(val name: String) : Place()
        data object Message : Place()

        /** Part of the address itself, which servers write to their logs and browsers to their history. */
        val inAddress: Boolean get() = this is Path || this is Query
    }

    data class Find(val kind: Kind, val place: Place)

    /** How much of one body is searched. */
    const val BODY_LIMIT = 256 * 1024

    // By name.

    private val PASSWORDS = setOf("pwd", "pass", "passwd", "passcode", "passphrase", "pincode", "otp")
    private val CARDS = setOf("cardnumber", "cardno", "ccnumber", "creditcard", "pan")
    // Cursors, form guards and push addresses are called tokens too, but open nothing.
    private val HARMLESS = listOf("csrf", "xsrf", "page", "next", "prev", "continuation", "cursor", "sync", "push", "device", "fcm", "apns", "recaptcha", "captcha")
    private val KEYS = listOf("apikey", "secretkey", "accesskey", "privatekey", "clientsecret", "appsecret", "apisecret", "secret")

    /** What a field called [name] carries when it carries a secret at all. */
    fun kindOfName(name: String): Kind? {
        val plain = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        if (plain.isEmpty()) return null
        if (plain in PASSWORDS) return Kind.PASSWORD
        if (listOf("password", "passwd", "passphrase").any(plain::endsWith)) return Kind.PASSWORD
        if (plain in CARDS) return Kind.CARD
        if (plain.endsWith("token") || plain in setOf("jwt", "bearer", "authorization", "oauth")) {
            return if (HARMLESS.any { it in plain }) null else Kind.TOKEN
        }
        if (KEYS.any(plain::endsWith)) return Kind.KEY
        if (plain in setOf("sid", "session", "sessionkey") || plain.endsWith("sessionid") || plain.endsWith("sessid")) return Kind.SESSION
        return null
    }

    /** Whether [value] can be the secret its field is called after: not a flag, a blank or a placeholder. */
    fun plausible(value: String, kind: Kind): Boolean {
        val text = value.trim()
        if (text.lowercase() in setOf("true", "false", "null", "none", "undefined", "nil")) return false
        return when (kind) {
            Kind.PASSWORD -> text.length >= 4
            Kind.CARD -> isCard(text)
            Kind.EMAIL -> isEmail(text)
            else -> text.length >= 8
        }
    }

    // By look.

    private val PATTERNS = listOf(
        Kind.PRIVATE_KEY to Regex("-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----"),
        Kind.KEY to Regex("AIza[0-9A-Za-z_-]{35}"),
        Kind.KEY to Regex("(?:AKIA|ASIA)[0-9A-Z]{16}"),
        Kind.KEY to Regex("[sr]k_live_[0-9A-Za-z]{16}"),
        Kind.KEY to Regex("sk-(?:ant-|proj-)?[0-9A-Za-z_-]{32}"),
        Kind.TOKEN to Regex("""eyJ[0-9A-Za-z_-]{8,}\.eyJ[0-9A-Za-z_-]{8,}"""),
        Kind.TOKEN to Regex("gh[pousr]_[0-9A-Za-z]{30}"),
        Kind.TOKEN to Regex("xox[abprs]-[0-9A-Za-z-]{10}"),
        Kind.TOKEN to Regex("""ya29\.[0-9A-Za-z_-]{20}"""),
        Kind.TOKEN to Regex("[0-9]{8,10}:AA[0-9A-Za-z_-]{30}"),
    )

    /** All of them at once: nearly every value is none of these, and a recording has tens of thousands of values. */
    private val ANY_LOOK = Regex(PATTERNS.joinToString("|") { it.second.pattern })
    private const val SHORTEST_LOOK = 15

    /** What [text] holds by its own look, whatever the field is called. */
    fun kindOfValue(text: String): Kind? =
        if (text.length < SHORTEST_LOOK || !ANY_LOOK.containsMatchIn(text)) null else PATTERNS.firstOrNull { it.second.containsMatchIn(text) }?.first

    fun isEmail(text: String): Boolean {
        val parts = text.split('@')
        if (parts.size != 2 || parts[0].isEmpty() || text.length > 120 || text.any(Char::isWhitespace)) return false
        val labels = parts[1].split('.')
        return labels.size >= 2 && labels.all { it.isNotEmpty() } && labels.last().length >= 2 && labels.last().all(Char::isLetter)
    }

    private fun isCard(text: String): Boolean {
        val digits = text.filter { it in '0'..'9' }.map { it - '0' }
        if (digits.size !in 13..19 || text.any { it !in '0'..'9' && it != ' ' && it != '-' }) return false
        val sum = digits.reversed().withIndex().sumOf { (index, digit) ->
            val doubled = if (index % 2 == 1) digit * 2 else digit
            if (doubled > 9) doubled - 9 else doubled
        }
        return sum % 10 == 0
    }

    // In a request.

    /** A field's name as it may be shown: a name can itself be a token (`?eyJhbGci…`). [limit] as for any foreign text. */
    fun safeName(name: String, limit: Int = 30): String {
        if (kindOfValue(name) != null || isEmail(name) || Endpoint.isIdentifier(name) || Endpoint.isNumber(name)) return Endpoint.PLACEHOLDER
        return cattivole.spectra.assistant.AssistantDigest.clean(name, limit)
    }

    fun field(name: String, value: String, place: (String) -> Place): Find? {
        kindOfName(name)?.let { if (plausible(value, it)) return Find(it, place(safeName(name))) }
        kindOfValue(value)?.let { return Find(it, place(safeName(name))) }
        // `?eyJhbGci…`: the token is the name.
        kindOfValue(name)?.let { return Find(it, place(safeName(name))) }
        return null
    }

    private val PLAIN_HEADERS = setOf("if-none-match", "etag", "user-agent", "accept", "sec-websocket-key")

    /**
     * Everything found in the request's address, headers and kept form or JSON fields. Cookies and the usual sign-in
     * headers are included: whether they are a problem depends on the encryption, which the audit knows.
     */
    fun inExchange(exchange: HttpExchange): List<Find> {
        val found = LinkedHashSet<Find>()
        val path = (exchange.target ?: exchange.path).substringBefore('?')
        kindOfValue(Endpoint.percentDecoded(path) ?: path)?.let { found.add(Find(it, Place.Path)) }
        for (segment in path.split('/')) {
            if (isEmail(Endpoint.percentDecoded(segment) ?: segment)) found.add(Find(Kind.EMAIL, Place.Path))
        }
        for (item in exchange.query) {
            field(item.name, item.value) { Place.Query(it) }?.let(found::add)
            if (isEmail(item.value)) found.add(Find(Kind.EMAIL, Place.Query(safeName(item.name))))
        }
        fun queryLeaks(url: String) = queryFields(url).any { field(it.name, it.value) { name -> Place.Query(name) } != null }
        // HTTP/2 sends the address as a header (`:path`): it was read above.
        for (header in exchange.requestHeaders.filterNot { it.name.startsWith(":") }) {
            when (val name = header.name.lowercase()) {
                "authorization", "proxy-authorization" -> if (header.value.isNotEmpty()) {
                    val basic = header.value.startsWith("basic ", ignoreCase = true)
                    found.add(Find(if (basic) Kind.PASSWORD else Kind.TOKEN, Place.RequestHeader(safeName(header.name))))
                }
                "cookie" -> if (header.value.isNotEmpty()) found.add(Find(Kind.SESSION, Place.RequestHeader("Cookie")))
                // The page before handed its own address on, secrets and all.
                "referer" -> if (queryLeaks(header.value)) found.add(Find(Kind.TOKEN, Place.RequestHeader("Referer")))
                else -> if (name !in PLAIN_HEADERS) field(header.name, header.value) { Place.RequestHeader(it) }?.let(found::add)
            }
        }
        for (header in exchange.responseHeaders) {
            if (header.name.equals("Location", true) && queryLeaks(header.value)) found.add(Find(Kind.TOKEN, Place.ResponseHeader("Location")))
        }
        for (item in exchange.parameters) field(item.name, item.value) { Place.RequestBody(it) }?.let(found::add)
        for (message in exchange.messages.take(50)) {
            val preview = message.preview ?: continue
            val kind = kindOfValue(preview)
            if (kind != null || jsonFinds(preview) { Place.Message }.isNotEmpty()) found.add(Find(kind ?: Kind.TOKEN, Place.Message))
        }
        return found.toList()
    }

    /** Secrets in a stored body. Fields of JSON and of a form by name; anything else by its look. */
    fun inBody(body: ByteArray, contentType: String?, request: Boolean): List<Find> {
        val data = if (body.size > BODY_LIMIT) body.copyOf(BODY_LIMIT) else body
        val type = contentType?.lowercase().orEmpty()
        if (listOf("image/", "video/", "audio/", "font/").any(type::startsWith)) return emptyList()
        val place: (String) -> Place = { if (request) Place.RequestBody(it) else Place.ResponseBody(it) }
        val text = String(data, Charsets.UTF_8)
        val found = LinkedHashSet<Find>()
        if ("x-www-form-urlencoded" in type) {
            for (item in formFields(data)) field(item.name, item.value, place)?.let(found::add)
        } else {
            found.addAll(jsonFinds(text, place))
        }
        if (found.isEmpty() && '�' !in text) kindOfValue(text)?.let { found.add(Find(it, place(""))) }
        return found.toList()
    }

    private fun jsonFinds(text: String, place: (String) -> Place): List<Find> {
        val first = text.firstOrNull { it.code > 32 }
        if (first != '{' && first != '[') return emptyList()
        val json = runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: return emptyList()
        val found = LinkedHashSet<Find>()
        var left = 2000
        fun walk(value: Any?, depth: Int) {
            if (depth >= 8 || left <= 0) return
            when (value) {
                is JSONObject -> for (name in value.keys().asSequence().toList().sorted()) {
                    left -= 1
                    when (val inner = value.opt(name)) {
                        is JSONObject, is JSONArray -> walk(inner, depth + 1)
                        null, JSONObject.NULL -> Unit
                        else -> field(name, inner.toString(), place)?.let(found::add)
                    }
                }
                is JSONArray -> for (index in 0 until minOf(value.length(), 200)) walk(value.opt(index), depth + 1)
            }
        }
        walk(json, 0)
        return found.toList()
    }
}
