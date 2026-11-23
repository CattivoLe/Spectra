package cattivole.spectra.assistant

import cattivole.spectra.core.Endpoint
import cattivole.spectra.core.EndpointGroup
import cattivole.spectra.core.Http2Assembler
import cattivole.spectra.core.HttpExchange
import cattivole.spectra.core.MapLocal
import cattivole.spectra.core.RequestHit
import cattivole.spectra.core.TrackerHosts
import cattivole.spectra.core.TrafficSecrets
import cattivole.spectra.core.isStream
import cattivole.spectra.core.isWebSocket
import cattivole.spectra.ui.byteSize
import cattivole.spectra.ui.tr
import kotlin.math.max
import kotlin.math.roundToInt

/** One thing the rules noticed about a request, an endpoint or a connection. */
data class TrafficIssue(
    val kind: Kind,
    val risk: CardRisk,
    /** What the card shows: kinds, places and numbers, never a value from the request. */
    val text: String,
) {
    enum class Kind {
        SECRET_UNENCRYPTED, SECRET_IN_ADDRESS, PRIVATE_KEY, PERSONAL, CLEARTEXT, DOWNGRADE,
        SERVER_ERROR, CLIENT_ERROR, NO_ANSWER, SLOW, LARGE, REPEATED,
        UNCOMPRESSED, UNCACHED, SHARED_CACHE, COOKIE, TRACKER, REFUSED, MAPPED,
    }

    val finding: CardFinding get() = CardFinding(risk, text)
}

/**
 * The rules behind the ✨ cards of recorded requests: what is wrong is found here, without the model, which only puts
 * it into words. Nothing that comes out of here holds a token, a cookie, a parameter's value or a body.
 * Mirrors TrafficAudit.swift; checked by `TrafficAuditTest` with the same cases.
 */
object TrafficAudit {
    const val SLOW_MS = 2000L
    const val SLOW_TYPICAL_MS = 1000L
    const val SLOW_WORST_MS = 5000L
    const val LARGE_BYTES = 1_048_576L
    const val COMPRESSIBLE_BYTES = 10 * 1024L
    const val REPEAT_WINDOW_MS = 10_000L
    const val REPEAT_COUNT = 3
    const val MANY_REPEATS = 5

    /** Of an endpoint's requests, how many of the newest have their bodies searched. */
    const val BODIES_READ = 5

    /** Reads a stored body: the request it belongs to and whether it is the request's own. */
    fun interface Bodies {
        fun read(hit: RequestHit, request: Boolean): ByteArray?
    }

    /** A request with the secrets found in it. */
    class Scan(val hit: RequestHit, val secrets: List<TrafficSecrets.Find>) {
        val exchange: HttpExchange get() = hit.exchange
        val cleartext: Boolean get() = isCleartext(hit)
    }

    /** Looks for secrets in each request, newest first; the bodies of the first [BODIES_READ] are read too. */
    fun scan(hits: List<RequestHit>, bodies: Bodies? = null): List<Scan> = hits.mapIndexed { number, hit ->
        val found = LinkedHashSet(TrafficSecrets.inExchange(hit.exchange))
        if (bodies != null && number < BODIES_READ) {
            for (request in listOf(true, false)) {
                if (!(if (request) hit.exchange.requestBodyStored else hit.exchange.responseBodyStored)) continue
                val body = bodies.read(hit, request) ?: continue
                found.addAll(TrafficSecrets.inBody(body, hit.exchange.header("Content-Type", response = !request), request))
            }
        }
        Scan(hit, found.toList())
    }

    // What a request is.

    fun isCleartext(hit: RequestHit): Boolean = !hit.flow.tls && hit.exchange.header(":scheme", response = false) != "https"

    private val PLAIN_PREFIXES = listOf("ocsp.", "ocsp2.", "crl.", "crl3.", "crl4.", "connectivitycheck.", "detectportal.")
    private val PLAIN_HOSTS = setOf("captive.apple.com", "www.msftconnecttest.com", "cp.cloudflare.com")

    /** Certificate status checks and sign-in page probes go over plain HTTP on purpose. */
    fun isPlainByDesign(host: String): Boolean =
        PLAIN_PREFIXES.any(host::startsWith) || host in PLAIN_HOSTS || host.endsWith(".ocsp.sectigo.com") || host.endsWith(".lencr.org")

    /** Answered by a Map Local rule: it says nothing about the server. */
    fun isMapped(exchange: HttpExchange): Boolean = exchange.header(MapLocal.HEADER, response = true) != null

    fun contentType(exchange: HttpExchange, response: Boolean = true): String? =
        exchange.header("Content-Type", response)?.substringBefore(';')?.trim()?.lowercase()

    private fun isText(type: String?): Boolean = type != null &&
        (type.startsWith("text/") || listOf("json", "xml", "javascript", "svg", "x-www-form-urlencoded", "graphql").any { it in type })

    private fun isStatic(type: String?): Boolean = type != null &&
        (type.startsWith("image/") || type.startsWith("font/") || "css" in type || "javascript" in type || "font" in type)

    /**
     * The app asked for a compressed answer and the server did not give one. Inside decrypted HTTP/1.1 Android's
     * recorder asks the server for plain text itself (`AcceptEncodingStrip`), so nothing can be said there.
     */
    fun isUncompressed(hit: RequestHit): Boolean {
        val exchange = hit.exchange
        if (hit.flow.tls && exchange.httpVersion != Http2Assembler.VERSION) return false
        val status = exchange.status ?: return false
        if (status !in 200..299 || isMapped(exchange) || exchange.isStream || !isText(contentType(exchange)) ||
            (exchange.responseBodyBytes ?: 0) < COMPRESSIBLE_BYTES
        ) {
            return false
        }
        val encoding = exchange.header("Content-Encoding", response = true)?.lowercase().orEmpty()
        val accepts = exchange.header("Accept-Encoding", response = false)?.lowercase().orEmpty()
        return (encoding.isEmpty() || encoding == "identity") && listOf("gzip", "br", "deflate", "zstd").any { it in accepts }
    }

    /** An answer that says nothing about how long it may be kept. */
    fun saysNothingAboutCaching(exchange: HttpExchange): Boolean =
        exchange.method.uppercase() == "GET" && exchange.status == 200 && !exchange.isStream && !isMapped(exchange) &&
            listOf("Cache-Control", "Expires", "ETag", "Last-Modified", "Pragma").all { exchange.header(it, response = true) == null }

    /** An answer made for one person that any cache on the way may keep and hand to the next. */
    fun isPersonalInSharedCache(exchange: HttpExchange): Boolean {
        val control = exchange.header("Cache-Control", response = true)?.lowercase().orEmpty()
        if (("public" !in control && "s-maxage" !in control) || "no-store" in control || "private" in control) return false
        return exchange.header("Set-Cookie", response = true) != null || exchange.header("Authorization", response = false) != null
    }

    fun redirectsToPlain(hit: RequestHit): Boolean {
        val status = hit.exchange.status ?: return false
        if (isCleartext(hit) || status !in 300..399) return false
        return hit.exchange.header("Location", response = true)?.startsWith("http://", ignoreCase = true) == true
    }

    fun noAnswer(hit: RequestHit): Boolean = hit.exchange.status == null && hit.flow.closed && !hit.exchange.isStream

    /** A cookie the answer sets with a flag missing that protects it. [signsIn]: its name says it keeps somebody signed in. */
    data class WeakCookie(val name: String, val missing: List<String>, val signsIn: Boolean)

    fun weakCookies(hit: RequestHit): List<WeakCookie> =
        hit.exchange.responseHeaders.filter { it.name.equals("Set-Cookie", true) }.mapNotNull { header ->
            val parts = header.value.split(';').map { it.trim() }
            val first = parts.first()
            val equals = first.indexOf('=')
            if (equals < 0 || equals == first.lastIndex) return@mapNotNull null
            val name = first.substring(0, equals)
            val flags = parts.drop(1).map { it.lowercase().replace(" ", "") }
            // Setting it to have expired is how a server deletes one.
            if ("max-age=0" in flags || flags.any { it.startsWith("expires=thu,01jan1970") || it.startsWith("expires=thu,01-jan-1970") }) return@mapNotNull null
            val lower = name.lowercase()
            val signsIn = TrafficSecrets.kindOfName(name) != null ||
                (listOf("sess", "auth", "token", "login", "sid").any { it in lower } && "csrf" !in lower && "xsrf" !in lower)
            val missing = buildList {
                if ("secure" !in flags) add("Secure")
                if (signsIn && "httponly" !in flags) add("HttpOnly")
            }
            if (missing.isEmpty()) null else WeakCookie(TrafficSecrets.safeName(name), missing, signsIn)
        }

    /**
     * How often the very same request went out: in all, and at most within [REPEAT_WINDOW_MS]. Only requests that ask
     * for something: the same POST twice may carry different bodies.
     */
    fun repeats(hits: List<RequestHit>): Pair<Int, Int> {
        val byTarget = HashMap<String, MutableList<Long>>()
        for (hit in hits) {
            if (hit.exchange.method.uppercase() !in setOf("GET", "HEAD") || hit.exchange.isWebSocket) continue
            byTarget.getOrPut(Endpoint.of(hit.flow, hit.exchange).host + (hit.exchange.target ?: hit.exchange.path)) { mutableListOf() }.add(hit.at)
        }
        var total = 0
        var burst = 0
        for (times in byTarget.values) {
            val sorted = times.sorted()
            total = max(total, sorted.size)
            var start = 0
            for (end in sorted.indices) {
                while (sorted[end] - sorted[start] > REPEAT_WINDOW_MS) start += 1
                burst = max(burst, end - start + 1)
            }
        }
        return total to burst
    }

    // Issues.

    /**
     * What is wrong with [scans]: one request, or all those of an endpoint or a connection. [same]: the requests among
     * which repeats are counted, when that is more than [scans].
     */
    fun issues(scans: List<Scan>, same: List<RequestHit>? = null): List<TrafficIssue> {
        if (scans.isEmpty()) return emptyList()
        val hits = scans.map { it.hit }
        val one = scans.size == 1
        val found = mutableListOf<TrafficIssue>()
        fun add(kind: TrafficIssue.Kind, risk: CardRisk, text: String) {
            found += TrafficIssue(kind, risk, text)
        }
        fun share(count: Int): String = if (one || count == scans.size) "" else " " + tr("(%d of %d requests)", count, scans.size)

        secrets(scans, ::share, ::add)
        errors(hits, one, ::add)
        speed(hits, one, ::add)

        val large = hits.filter(::isLarge)
        if (large.isNotEmpty()) {
            val biggest = byteSize(large.maxOf { it.exchange.responseBodyBytes ?: 0 })
            val text = large.any { isText(contentType(it.exchange)) }
            add(
                TrafficIssue.Kind.LARGE, if (text) CardRisk.WARNING else CardRisk.INFO,
                (if (one) tr("Large answer: %s.", biggest) else tr("Large answers: up to %s.", biggest)) + share(large.size),
            )
        }
        val (total, burst) = repeats(same ?: hits)
        if (burst >= REPEAT_COUNT) {
            add(
                TrafficIssue.Kind.REPEATED, if (burst >= MANY_REPEATS) CardRisk.WARNING else CardRisk.INFO,
                tr("The same request went out %d times within %d s: repeats nobody needed, or a retry loop.", burst, REPEAT_WINDOW_MS / 1000),
            )
        }
        val uncompressed = hits.filter(::isUncompressed)
        if (uncompressed.isNotEmpty()) {
            val most = byteSize(uncompressed.maxOf { it.exchange.responseBodyBytes ?: 0 })
            add(TrafficIssue.Kind.UNCOMPRESSED, CardRisk.INFO, tr("Came uncompressed although the app accepts compression: %s of text.", most) + share(uncompressed.size))
        }
        val uncached = hits.filter { saysNothingAboutCaching(it.exchange) && (isStatic(contentType(it.exchange)) || total >= REPEAT_COUNT) }
        if (uncached.isNotEmpty()) {
            add(TrafficIssue.Kind.UNCACHED, CardRisk.INFO, tr("The answer says nothing about caching, so it is downloaded again every time.") + share(uncached.size))
        }
        val shared = hits.count { isPersonalInSharedCache(it.exchange) }
        if (shared > 0) {
            add(TrafficIssue.Kind.SHARED_CACHE, CardRisk.WARNING, tr("A personal answer may be kept by any cache on the way (Cache-Control: public).") + share(shared))
        }
        val cookies = hits.flatMap(::weakCookies).distinct()
        if (cookies.isNotEmpty()) {
            val list = cookies.take(3).joinToString(", ") { "“${it.name}” (${tr("without")} ${it.missing.joinToString(", ")})" }
            add(TrafficIssue.Kind.COOKIE, if (cookies.any { it.signsIn }) CardRisk.WARNING else CardRisk.INFO, tr("Cookies set with weak protection: %s.", list))
        }
        val trackers = hits.mapNotNull { hit ->
            val host = Endpoint.of(hit.flow, hit.exchange).host
            TrackerHosts.serviceOf(host)?.let { host to it }
        }.distinctBy { it.second.name }
        if (trackers.isNotEmpty()) {
            val names = trackers.take(3).joinToString(", ") { "${it.second.name} (${kindName(it.second.kind)})" }
            add(
                TrafficIssue.Kind.TRACKER, CardRisk.INFO,
                if (trackers.size == 1) tr("%s belongs to %s: it collects data about the app's use.", AssistantDigest.clean(trackers[0].first), names)
                else tr("Services that collect data about the app's use: %s.", names),
            )
        }
        val mapped = hits.count { isMapped(it.exchange) }
        if (mapped > 0) add(TrafficIssue.Kind.MAPPED, CardRisk.INFO, tr("Answered by this phone's own Map Local rule, not by the server.") + share(mapped))
        return found
    }

    private fun passedOn(find: TrafficSecrets.Find): Boolean = find.place.inAddress ||
        find.place == TrafficSecrets.Place.RequestHeader("Referer") || find.place == TrafficSecrets.Place.ResponseHeader("Location")

    private fun secrets(scans: List<Scan>, share: (Int) -> String, add: (TrafficIssue.Kind, CardRisk, String) -> Unit) {
        val open = scans.filter { it.cleartext }
        val bare = open.flatMap { it.secrets }.distinct()
        if (bare.isNotEmpty()) {
            add(
                TrafficIssue.Kind.SECRET_UNENCRYPTED, CardRisk.CRITICAL,
                tr("Sent unencrypted, readable by anyone on the way: %s. Values are hidden here.", list(bare)) + share(open.count { it.secrets.isNotEmpty() }),
            )
        } else {
            val plain = open.count { !isPlainByDesign(Endpoint.of(it.hit.flow, it.exchange).host) }
            if (plain > 0) {
                add(TrafficIssue.Kind.CLEARTEXT, CardRisk.WARNING, tr("Unencrypted HTTP: anyone on the way can read and change it.") + share(plain))
            } else if (open.isNotEmpty()) {
                add(TrafficIssue.Kind.CLEARTEXT, CardRisk.INFO, tr("Unencrypted HTTP, as usual for this kind of service check.") + share(open.size))
            }
        }
        val closed = scans.filter { !it.cleartext }
        fun leaks(find: TrafficSecrets.Find) = passedOn(find) && find.kind != TrafficSecrets.Kind.EMAIL
        val address = closed.flatMap { it.secrets.filter(::leaks) }.distinct()
        if (address.isNotEmpty()) {
            add(
                TrafficIssue.Kind.SECRET_IN_ADDRESS, CardRisk.WARNING,
                tr("Secret in the address: %s. Addresses stay in server logs and histories. The value is hidden here.", list(address)) +
                    share(closed.count { it.secrets.any(::leaks) }),
            )
        }
        fun mails(find: TrafficSecrets.Find) = find.kind == TrafficSecrets.Kind.EMAIL && find.place.inAddress
        val emails = closed.flatMap { it.secrets.filter(::mails) }.distinct()
        if (emails.isNotEmpty()) {
            add(TrafficIssue.Kind.PERSONAL, CardRisk.INFO, tr("Personal data in the address: %s.", list(emails)) + share(closed.count { it.secrets.any(::mails) }))
        }
        if (closed.any { scan -> scan.secrets.any { it.kind == TrafficSecrets.Kind.PRIVATE_KEY && it.place is TrafficSecrets.Place.ResponseBody } }) {
            add(TrafficIssue.Kind.PRIVATE_KEY, CardRisk.WARNING, tr("The answer carries a private key. The value is hidden here."))
        }
        val downgraded = scans.count { redirectsToPlain(it.hit) }
        if (downgraded > 0) add(TrafficIssue.Kind.DOWNGRADE, CardRisk.WARNING, tr("Redirects from HTTPS to unencrypted HTTP.") + share(downgraded))
    }

    private fun errors(hits: List<RequestHit>, one: Boolean, add: (TrafficIssue.Kind, CardRisk, String) -> Unit) {
        val statuses = hits.mapNotNull { it.exchange.status }
        val server = statuses.filter { it >= 500 }
        val client = statuses.filter { it in 400..499 }
        val silent = hits.count(::noAnswer)
        if (one) {
            server.firstOrNull()?.let { add(TrafficIssue.Kind.SERVER_ERROR, CardRisk.WARNING, tr("The server failed: status %d.", it)) }
            client.firstOrNull()?.let { add(TrafficIssue.Kind.CLIENT_ERROR, CardRisk.WARNING, tr("Refused: status %d, %s.", it, meaning(it))) }
            if (silent > 0) add(TrafficIssue.Kind.NO_ANSWER, CardRisk.WARNING, tr("No answer: the connection closed before one came."))
            return
        }
        val failed = server.size + client.size
        if (failed > 0) {
            val percent = max(1, (failed * 100.0 / hits.size).roundToInt())
            add(
                if (server.isEmpty()) TrafficIssue.Kind.CLIENT_ERROR else TrafficIssue.Kind.SERVER_ERROR,
                if (server.isNotEmpty() || percent >= 5) CardRisk.WARNING else CardRisk.INFO,
                tr("Errors: %d of %d requests (%d%%): %s.", failed, hits.size, percent, tally(server + client)),
            )
        }
        if (silent > 0) add(TrafficIssue.Kind.NO_ANSWER, CardRisk.WARNING, tr("No answer: %d of %d requests.", silent, hits.size))
    }

    private fun speed(hits: List<RequestHit>, one: Boolean, add: (TrafficIssue.Kind, CardRisk, String) -> Unit) {
        val timed = hits.filter { !it.exchange.isStream && !isMapped(it.exchange) }
        val times = timed.mapNotNull { it.exchange.durationMs }.sorted()
        val worst = times.lastOrNull() ?: return
        val typical = EndpointGroup.median(times) ?: return
        if (one) {
            if (worst < SLOW_MS) return
            val wait = timed.first().exchange.waitMs
            add(
                TrafficIssue.Kind.SLOW, CardRisk.WARNING,
                if (wait != null) tr("Slow: %s, %s of it waiting for the server.", duration(worst), duration(wait)) else tr("Slow: %s.", duration(worst)),
            )
        } else if (typical >= SLOW_TYPICAL_MS) {
            add(TrafficIssue.Kind.SLOW, CardRisk.WARNING, tr("Slow: usually %s, at worst %s.", duration(typical), duration(worst)))
        } else if (worst >= SLOW_WORST_MS) {
            add(TrafficIssue.Kind.SLOW, CardRisk.INFO, tr("Usually %s, but once it took %s.", duration(typical), duration(worst)))
        }
    }

    private fun isLarge(hit: RequestHit): Boolean {
        val type = contentType(hit.exchange)
        return (hit.exchange.responseBodyBytes ?: 0) >= LARGE_BYTES && !hit.exchange.isStream && !isMapped(hit.exchange) &&
            (isText(type) || type?.startsWith("image/") == true)
    }

    // Words.

    /** `token — parameter “access_token”, password — header “Authorization”`: kinds and places, never values. */
    fun list(finds: List<TrafficSecrets.Find>, limit: Int = 3): String {
        val named = finds.take(limit).joinToString(", ") { "${name(it.kind)} — ${name(it.place)}" }
        return if (finds.size > limit) named + " " + tr("and %d more", finds.size - limit) else named
    }

    /** The same in English, for the model. */
    fun englishList(finds: List<TrafficSecrets.Find>, limit: Int = 4): String =
        finds.take(limit).joinToString("; ") { "${englishName(it.kind)} in ${englishName(it.place)}" }

    fun name(kind: TrafficSecrets.Kind): String = when (kind) {
        TrafficSecrets.Kind.PASSWORD -> tr("password")
        TrafficSecrets.Kind.TOKEN -> tr("access token")
        TrafficSecrets.Kind.KEY -> tr("secret key")
        TrafficSecrets.Kind.SESSION -> tr("session cookie or identifier")
        TrafficSecrets.Kind.CARD -> tr("card number")
        TrafficSecrets.Kind.PRIVATE_KEY -> tr("private key")
        TrafficSecrets.Kind.EMAIL -> tr("e-mail address")
    }

    fun name(place: TrafficSecrets.Place): String = when (place) {
        TrafficSecrets.Place.Path -> tr("the path")
        is TrafficSecrets.Place.Query -> tr("parameter “%s”", place.name)
        is TrafficSecrets.Place.RequestHeader -> tr("header “%s”", place.name)
        is TrafficSecrets.Place.ResponseHeader -> tr("header “%s” of the answer", place.name)
        is TrafficSecrets.Place.RequestBody -> if (place.name.isEmpty()) tr("the request body") else tr("body field “%s”", place.name)
        is TrafficSecrets.Place.ResponseBody -> if (place.name.isEmpty()) tr("the answer's body") else tr("field “%s” of the answer", place.name)
        TrafficSecrets.Place.Message -> tr("a WebSocket message")
    }

    private fun englishName(kind: TrafficSecrets.Kind): String = when (kind) {
        TrafficSecrets.Kind.PASSWORD -> "password"
        TrafficSecrets.Kind.TOKEN -> "access token"
        TrafficSecrets.Kind.KEY -> "secret key"
        TrafficSecrets.Kind.SESSION -> "session cookie or identifier"
        TrafficSecrets.Kind.CARD -> "card number"
        TrafficSecrets.Kind.PRIVATE_KEY -> "private key"
        TrafficSecrets.Kind.EMAIL -> "e-mail address"
    }

    private fun englishName(place: TrafficSecrets.Place): String = when (place) {
        TrafficSecrets.Place.Path -> "the URL path"
        is TrafficSecrets.Place.Query -> "the URL parameter `${place.name}`"
        is TrafficSecrets.Place.RequestHeader -> "the request header `${place.name}`"
        is TrafficSecrets.Place.ResponseHeader -> "the response header `${place.name}`"
        is TrafficSecrets.Place.RequestBody -> if (place.name.isEmpty()) "the request body" else "the request body field `${place.name}`"
        is TrafficSecrets.Place.ResponseBody -> if (place.name.isEmpty()) "the response body" else "the response body field `${place.name}`"
        TrafficSecrets.Place.Message -> "a WebSocket message"
    }

    fun kindName(kind: TrackerHosts.Kind): String = when (kind) {
        TrackerHosts.Kind.ANALYTICS -> tr("analytics")
        TrackerHosts.Kind.ADS -> tr("advertising")
        TrackerHosts.Kind.ATTRIBUTION -> tr("install tracking")
        TrackerHosts.Kind.CRASH -> tr("crash reports")
        TrackerHosts.Kind.PUSH -> tr("push messages")
    }

    fun englishKind(kind: TrackerHosts.Kind): String = when (kind) {
        TrackerHosts.Kind.ANALYTICS -> "analytics"
        TrackerHosts.Kind.ADS -> "advertising"
        TrackerHosts.Kind.ATTRIBUTION -> "install tracking"
        TrackerHosts.Kind.CRASH -> "crash reports"
        TrackerHosts.Kind.PUSH -> "push messages"
    }

    /** What a 4xx status says, in a word. */
    fun meaning(status: Int): String = when (status) {
        400, 422 -> tr("the request itself is wrong")
        401 -> tr("not signed in, or the sign-in has expired")
        403 -> tr("no permission")
        404, 410 -> tr("there is nothing at this address")
        408 -> tr("the request took too long to arrive")
        429 -> tr("too many requests")
        else -> tr("the server did not accept it")
    }

    /** `429 ×10, 500 ×2`, the most frequent first. */
    fun tally(statuses: List<Int>, limit: Int = 4): String = statuses.groupingBy { it }.eachCount().toList()
        .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first }).take(limit).joinToString(", ") { "${it.first} ×${it.second}" }

    fun duration(ms: Long): String = if (ms < 1000) tr("%d ms", ms) else tr("%.2f s", ms / 1000.0)
}
