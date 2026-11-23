package cattivole.spectra.assistant

import cattivole.spectra.core.Endpoint
import cattivole.spectra.core.EndpointGroup
import cattivole.spectra.core.Http2Assembler
import cattivole.spectra.core.HttpExchange
import cattivole.spectra.core.HttpField
import cattivole.spectra.core.LinkInspector
import cattivole.spectra.core.RepeatResult
import cattivole.spectra.core.RequestFilter
import cattivole.spectra.core.RequestHit
import cattivole.spectra.core.TrackerHosts
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TrafficKind
import cattivole.spectra.core.TrafficSecrets
import cattivole.spectra.core.TrafficSession
import cattivole.spectra.core.isStream
import cattivole.spectra.core.isWebSocket
import cattivole.spectra.core.kind
import cattivole.spectra.ui.byteSize
import cattivole.spectra.ui.tr
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import kotlin.math.max

/**
 * Where a recorded connection is: its recording (null is the live one), its number there and when it began. The start
 * tells a connection of this recording from the one that had the same number in another.
 */
data class TrafficRef(
    val session: String?,
    val flow: Long,
    val startedAt: Long,
    /** The request in it; null for the connection itself. */
    val index: Int? = null,
) {
    constructor(session: String?, flow: TrafficFlow, index: Int? = null) : this(session, flow.id, flow.startedAt, index)

    /** What a saved card keeps: numbers only, nothing of the request itself. */
    val text: String get() = "${session.orEmpty()}|$flow|$startedAt|${index ?: ""}"

    companion object {
        fun of(text: String): TrafficRef? {
            val parts = text.split('|')
            if (parts.size != 4) return null
            return TrafficRef(parts[0].ifEmpty { null }, parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null, parts[3].toIntOrNull())
        }
    }
}

/** What the cards of recorded traffic read it through: the live recording, the saved ones and their bodies. */
class TrafficLibrary(
    val live: () -> List<TrafficFlow> = { emptyList() },
    val saved: () -> List<TrafficSession> = { emptyList() },
    /** The recorder is on: a check made now would go through it and, with decryption, meet Spectra's own certificate. */
    val recording: () -> Boolean = { false },
    val body: (session: String?, flow: Long, index: Int, request: Boolean) -> ByteArray? = { _, _, _, _ -> null },
) {
    fun flows(session: String?): List<TrafficFlow>? = if (session == null) live() else saved().firstOrNull { it.id == session }?.flows

    /** The connection [ref] points to, and the recording it is in now: one that was live may have been saved since. */
    fun find(ref: TrafficRef): Pair<String?, TrafficFlow>? {
        fun same(flow: TrafficFlow) = flow.id == ref.flow && flow.startedAt == ref.startedAt
        flows(ref.session)?.firstOrNull(::same)?.let { return ref.session to it }
        live().firstOrNull(::same)?.let { return null to it }
        for (session in saved()) session.flows.firstOrNull(::same)?.let { return session.id to it }
        return null
    }

    fun bodies(session: String?) = TrafficAudit.Bodies { hit, request -> body(session, hit.flow.id, hit.index, request) }
}

/**
 * Summary cards of one recorded request, of an endpoint's requests and of one connection. Same contract as
 * [AssistantCards]: findings and buttons come from the rules ([TrafficAudit]), the model only puts them into words.
 * Everything a card keeps or hands the model is kinds, names, places and numbers: no value from a request, no body.
 * Mirrors TrafficCards.swift; checked by `TrafficCardsTest`.
 */
object TrafficCards {
    /** What the model reads about one subject, in characters. */
    const val BUDGET = 1800
    private const val HIDDEN = "Values of parameters, headers, cookies and bodies are hidden on purpose: only their names, kinds and sizes are given. Never guess a value."

    class Context(
        val session: String?,
        /** Every connection of the recording: where repeats and neighbours are counted. */
        val flows: List<TrafficFlow>,
        val bodies: TrafficAudit.Bodies? = null,
        val recording: Boolean = false,
    )

    // One request.

    fun request(flow: TrafficFlow, index: Int, context: Context): CardFacts? {
        val exchange = flow.exchanges.getOrNull(index) ?: return null
        val hit = RequestHit(flow, index)
        val endpoint = Endpoint.of(flow, exchange)
        val target = exchange.target ?: exchange.path
        val same = RequestHit.all(context.flows).filter {
            it.exchange.method == exchange.method && (it.exchange.target ?: it.exchange.path) == target && Endpoint.of(it.flow, it.exchange).host == endpoint.host
        }
        val scans = TrafficAudit.scan(listOf(hit), context.bodies)
        val issues = TrafficAudit.issues(scans, same)
        val found = issues.map { it.finding }.toMutableList()
        found += CardFinding(CardRisk.INFO, answerLine(hit))
        if (exchange.isWebSocket) {
            val sent = exchange.messages.count { it.fromClient }
            found += CardFinding(CardRisk.INFO, tr("WebSocket: %d messages sent, %d received.", sent, exchange.messages.size - sent))
        }
        if (!scans[0].cleartext) found += encrypted(issues)
        val ref = TrafficRef(context.session, flow, index)
        val data = text(requestLines(hit, scans[0], same))
        val group = EndpointGroup.find(endpoint.key, context.flows)
        val deep = listOfNotNull(data, group?.let { text(endpointLines(it, TrafficAudit.scan(it.hits))) }, text(connectionLines(flow, intro = false)))
        return CardFacts(
            CardSubject.Request(ref), title(endpoint), AssistantCards.ranked(found),
            steps(hit, issues, ref, endpoint.host, context).take(3), data, deep.joinToString("\n\n"),
        )
    }

    private fun encrypted(issues: List<TrafficIssue>): CardFinding {
        val clean = issues.none { it.kind == TrafficIssue.Kind.SECRET_IN_ADDRESS || it.kind == TrafficIssue.Kind.PERSONAL }
        return CardFinding(CardRisk.OK, if (clean) tr("Sent encrypted, with no secrets in the address.") else tr("Sent encrypted."))
    }

    private fun answerLine(hit: RequestHit): String {
        val exchange = hit.exchange
        val status = exchange.status ?: return if (hit.flow.closed) tr("No answer was recorded.") else tr("The answer has not come yet.")
        val parts = mutableListOf(status.toString())
        if (!exchange.isStream) exchange.durationMs?.let { parts += TrafficAudit.duration(it) }
        exchange.responseSize?.let { parts += byteSize(it) }
        TrafficAudit.contentType(exchange)?.let { parts += AssistantDigest.clean(it) }
        return tr("Answer: %s.", parts.joinToString(" · "))
    }

    private fun requestLines(hit: RequestHit, scan: TrafficAudit.Scan, same: List<RequestHit>): List<String> = buildList {
        val flow = hit.flow
        val exchange = hit.exchange
        val endpoint = Endpoint.of(flow, exchange)
        add("One HTTP request an app on the user's phone made. $HIDDEN")
        add("Request: ${title(endpoint)}${if (Endpoint.PLACEHOLDER in endpoint.path) " ({id} stands for a changing part)" else ""}")
        add("App: ${app(hit)}")
        add("Server: ${service(endpoint.host)}")
        add(if (scan.cleartext) "Encrypted: no, plain HTTP" else "Encrypted: yes; Spectra opened it on this phone to show it")
        val status = exchange.status
        if (status != null) {
            val meaning = if (status >= 400) " (an error)" else if (status >= 300) " (a redirect)" else " (success)"
            add("Answer: status $status$meaning, type ${TrafficAudit.contentType(exchange)?.let { AssistantDigest.clean(it) } ?: "not given"}")
            if (status in 300..399) {
                val to = exchange.header("Location", response = true)?.let { runCatching { URI(it).host }.getOrNull() }
                add("Redirects to: ${to?.let { AssistantDigest.clean(it) } ?: "another address on the same server"}")
            }
        } else {
            add(if (flow.closed) "Answer: none, the connection closed without one" else "Answer: not yet, the request is still under way")
        }
        val duration = exchange.durationMs
        when {
            exchange.isWebSocket -> add("This is a WebSocket: it stays open on purpose. Messages: ${exchange.messages.size + exchange.messagesDropped}")
            duration != null -> add(
                "Time: $duration ms${exchange.waitMs?.let { ", $it ms of it waiting for the server" } ?: ""} — ${if (duration >= TrafficAudit.SLOW_MS) "slow" else "not slow"}",
            )
            else -> add("Time: not recorded")
        }
        add("Size: request ${bytes(exchange.requestSize)}, answer ${bytes(exchange.responseSize)}")
        add("URL parameters: ${names(exchange.query)}")
        add("Body fields sent: ${names(exchange.parameters)}")
        add("Secrets: ${secrets(listOf(scan))}")
        add("Cookies set with missing protection: ${cookies(listOf(hit))}")
        add("Compression: ${compression(hit)}")
        add(
            "Caching: " + if (TrafficAudit.saysNothingAboutCaching(exchange)) "the answer does not say"
            else exchange.header("Cache-Control", response = true)?.let { AssistantDigest.clean(it, 60) } ?: "not applicable",
        )
        val (total, burst) = TrafficAudit.repeats(same)
        add(if (total > 1) "The same request in this recording: $total times, at most $burst within 10 s" else "The same request in this recording: only this one")
        if (TrafficAudit.isMapped(exchange)) add("The answer came from the user's own Map Local rule on the phone, not from the server.")
    }

    // An endpoint.

    fun endpoint(key: String, context: Context): CardFacts? {
        val group = EndpointGroup.find(key, context.flows) ?: return null
        val scans = TrafficAudit.scan(group.hits, context.bodies)
        val issues = TrafficAudit.issues(scans)
        val found = issues.map { it.finding }.toMutableList()
        var line = tr("Requests: %d.", group.hits.size)
        val typical = group.typicalMs
        val worst = group.worstMs
        if (typical != null && worst != null && issues.none { it.kind == TrafficIssue.Kind.SLOW }) {
            line += " " + if (typical == worst) tr("Usually %s.", TrafficAudit.duration(typical))
            else tr("Usually %s, at worst %s.", TrafficAudit.duration(typical), TrafficAudit.duration(worst))
        }
        if (group.bytes > 0) line += " " + tr("In all %s.", byteSize(group.bytes))
        found += CardFinding(CardRisk.INFO, line)
        if (group.errors == 0) found += CardFinding(CardRisk.OK, tr("No errors."))
        if (scans.none { it.cleartext }) found += encrypted(issues)
        // The button repeats a request that failed, when there is one: that is the one worth trying again.
        val example = group.hits.firstOrNull { RequestFilter.failed(it.exchange, it.flow) } ?: group.hits.first()
        val ref = TrafficRef(context.session, example.flow, example.index)
        val data = text(endpointLines(group, scans))
        val host = group.endpoint.host
        val neighbours = EndpointGroup.build(context.flows).filter { it.endpoint.host == host && it.endpoint != group.endpoint }
        val around = if (neighbours.isEmpty()) "Other endpoints of this server in the recording: none."
        else "Other endpoints of this server in the recording:\n" + neighbours.take(10).joinToString("\n") { "- " + brief(it) }
        return CardFacts(
            CardSubject.Endpoint(context.session, key), title(group.endpoint), AssistantCards.ranked(found),
            steps(example, issues, ref, host, context).take(3), data, data + "\n\n" + around,
        )
    }

    private fun endpointLines(group: EndpointGroup, scans: List<TrafficAudit.Scan>): List<String> = buildList {
        val hits = group.hits
        val newest = hits.firstOrNull() ?: return@buildList
        add("All requests of one endpoint (one method, server and path; {id} stands for the part that changes) in a recording of the user's phone. $HIDDEN")
        add("Endpoint: ${title(group.endpoint)}")
        add("App: ${app(newest)}")
        add("Server: ${service(group.endpoint.host)}")
        val plain = scans.count { it.cleartext }
        add(if (plain == 0) "Encrypted: yes, all requests" else "Encrypted: no, $plain of ${hits.size} requests went over plain HTTP")
        add("Requests: ${hits.size}, over ${max(0, group.lastAt - group.firstAt) / 1000} s")
        val statuses = group.statuses.take(5).map { (status, count) -> if (status == 0) "no answer ×$count" else "$status ×$count" }
        add("Answers by status: ${if (statuses.isEmpty()) "none yet" else statuses.joinToString(", ")}")
        add("Errors: ${if (group.errors == 0) "none" else "${group.errors} of ${hits.size}"}")
        val typical = group.typicalMs
        val worst = group.worstMs
        when {
            group.webSocket -> add("This is a WebSocket: it stays open on purpose. Messages: ${hits.sumOf { it.exchange.messages.size + it.exchange.messagesDropped }}")
            typical != null && worst != null -> add(
                "Time: usually $typical ms, at worst $worst ms — " +
                    if (typical >= TrafficAudit.SLOW_TYPICAL_MS) "slow" else if (worst >= TrafficAudit.SLOW_WORST_MS) "usually not slow" else "not slow",
            )
            else -> add("Time: not recorded")
        }
        add("Traffic in all: ${bytes(group.bytes)}")
        add("URL parameters: ${names(hits.flatMap { it.exchange.query }.distinctBy { it.name })}")
        add("Secrets: ${secrets(scans)}")
        add("Cookies set with missing protection: ${cookies(hits)}")
        val uncompressed = hits.count(TrafficAudit::isUncompressed)
        add("Answers that could have been compressed and were not: ${if (uncompressed == 0) "none" else "$uncompressed"}")
        val (total, burst) = TrafficAudit.repeats(hits)
        add(if (burst >= TrafficAudit.REPEAT_COUNT) "Identical requests: up to $total, at most $burst within 10 s" else "Needless repeats of an identical request: none")
        if (group.http2) add("Protocol: HTTP/2, several requests share one connection; that is normal")
    }

    // A connection.

    fun connection(flow: TrafficFlow, context: Context): CardFacts {
        val hits = flow.exchanges.indices.map { RequestHit(flow, it) }.filter { it.exchange.method.isNotEmpty() }.sortedByDescending { it.at }
        val scans = TrafficAudit.scan(hits, context.bodies)
        val issues = TrafficAudit.issues(scans).toMutableList()
        val host = flow.title.lowercase()
        val service = TrackerHosts.serviceOf(host)
        if (hits.isEmpty() && service != null) {
            issues += TrafficIssue(
                TrafficIssue.Kind.TRACKER, CardRisk.INFO,
                tr("%s belongs to %s: it collects data about the app's use.", AssistantDigest.clean(host), "${service.name} (${TrafficAudit.kindName(service.kind)})"),
            )
        }
        if (flow.refused) {
            issues += TrafficIssue(
                TrafficIssue.Kind.REFUSED, CardRisk.INFO,
                tr("The app refused Spectra's certificate and stayed unread: it accepts only its own, or the certificate is not trusted on this phone. That protects it and is not a fault."),
            )
        }
        val found = issues.map { it.finding }.toMutableList()
        when (flow.kind()) {
            TrafficKind.HTTP -> {
                val endpoints = hits.map { Endpoint.of(it.flow, it.exchange).key }.toSet().size
                found += CardFinding(CardRisk.INFO, tr("Requests in it: %d, to %d endpoints.", hits.size, endpoints))
                if (flow.tls) found += CardFinding(CardRisk.OK, tr("Encrypted (TLS); Spectra opened it on this phone to show it."))
                // Read before the request was split off: an old recording with a request line and nothing more.
                if (hits.isEmpty() && !flow.tls) found += CardFinding(CardRisk.WARNING, tr("Unencrypted HTTP: anyone on the way can read and change it."))
            }
            TrafficKind.TLS -> if (!flow.refused) {
                found += CardFinding(CardRisk.OK, tr("Encrypted (TLS), not opened: only the server's name and the amount of data are known."))
            }
            TrafficKind.DNS -> found += CardFinding(CardRisk.INFO, tr("A name lookup (DNS): the phone asks for a server's address."))
            TrafficKind.TCP, TrafficKind.UDP ->
                found += CardFinding(CardRisk.INFO, tr("%s, port %d: not HTTP and not TLS, so its content is not read.", flow.protocol, flow.destinationPort))
        }
        found += CardFinding(CardRisk.INFO, tr("Sent %s, received %s.", byteSize(flow.sent), byteSize(flow.received)))
        if (flow.sent >= TrafficAudit.LARGE_BYTES && flow.sent > flow.received * 4) {
            found += CardFinding(CardRisk.INFO, tr("Mostly upload: the phone sent far more than it received."))
        }
        val ref = TrafficRef(context.session, flow)
        val data = text(
            connectionLines(flow, intro = true) + listOf(
                "Secrets: ${if (hits.isEmpty()) "unknown, no request was read" else secrets(scans)}",
                "Errors: ${if (hits.isEmpty()) "unknown, no request was read" else tallyErrors(hits)}",
            ),
        )
        val same = context.flows.filter { it.title == flow.title && it.id != flow.id }
        val around = if (same.isEmpty()) "Other connections to this server in the recording: none."
        else "Other connections to this server in the recording: ${same.size}, ${bytes(same.sumOf { it.sent + it.received })} in all, refused the certificate: ${same.count { it.refused }}."
        return CardFacts(
            CardSubject.Connection(ref), AssistantDigest.clean(flow.title, 60), AssistantCards.ranked(found),
            steps(hits.firstOrNull(), issues, ref, host, context).take(3), data, data + "\n\n" + around,
        )
    }

    private fun connectionLines(flow: TrafficFlow, intro: Boolean): List<String> = buildList {
        if (intro) add("One connection an app on the user's phone opened, recorded on the phone. $HIDDEN")
        add("Connection to: ${AssistantDigest.clean(flow.title, 60)}, ${flow.protocol} port ${flow.destinationPort}")
        flow.app?.let { add("App: ${AssistantDigest.clean(it)}") }
        add("Server: ${service(flow.title.lowercase())}")
        add(
            when (flow.kind()) {
                TrafficKind.DNS -> "This is a DNS lookup; plain DNS is normal on most networks."
                TrafficKind.TLS -> if (flow.refused) "Encrypted: yes. The app refused Spectra's certificate (it accepts only its own, or the certificate is not trusted on this phone), so the content is unknown; that is a protection, not a fault."
                else "Encrypted: yes. Spectra did not open it, so the content is unknown."
                TrafficKind.HTTP -> if (flow.tls) "Encrypted: yes; Spectra opened it on this phone to show the requests." else "Encrypted: no, plain HTTP."
                TrafficKind.TCP, TrafficKind.UDP -> "Neither HTTP nor TLS was recognized: whether it is encrypted is unknown."
            },
        )
        add("Sent ${bytes(flow.sent)}, received ${bytes(flow.received)}, lasted ${max(0, flow.lastAt - flow.startedAt) / 1000} s, ${if (flow.closed) "closed" else "still open"}")
        val groups = EndpointGroup.build(listOf(flow))
        if (groups.isEmpty()) {
            add("Requests read in it: none")
        } else {
            val shared = flow.exchanges.any { it.httpVersion == Http2Assembler.VERSION }
            add("Requests read in it: ${flow.exchanges.size}${if (shared) " over HTTP/2, which shares one connection; that is normal" else ""}:")
            addAll(groups.take(6).map { "- " + brief(it) })
            if (groups.size > 6) add("- and ${groups.size - 6} more endpoints")
        }
    }

    // Buttons.

    /**
     * Steps in the order they help with what was found. What asks the server something is not offered while the
     * recorder is on: it would go through the recorder and, with decryption, meet Spectra's own certificate.
     */
    private fun steps(hit: RequestHit?, issues: List<TrafficIssue>, ref: TrafficRef, host: String, context: Context): List<CardStep> {
        val kinds = issues.map { it.kind }.toSet()
        val steps = mutableListOf<CardStep>()
        val wrong = listOf(TrafficIssue.Kind.SERVER_ERROR, TrafficIssue.Kind.CLIENT_ERROR, TrafficIssue.Kind.NO_ANSWER, TrafficIssue.Kind.SLOW).any { it in kinds }
        if (hit != null && ref.index != null && !hit.exchange.isWebSocket && hit.exchange.method.uppercase() != "CONNECT") {
            // Plain HTTP the system does not let the app send: a failure there would say nothing about the server.
            if (hit.exchange.method.uppercase() in setOf("GET", "HEAD") && !TrafficAudit.isCleartext(hit) && !context.recording &&
                (wrong || TrafficIssue.Kind.REPEATED in kinds)
            ) {
                steps += call(AssistantActions.Name.REPEAT_REQUEST, ref.text, tr("Repeat the request and compare"))
            }
            // A request that changes something is only prepared: the user looks at it and sends it.
            steps += call(AssistantActions.Name.OPEN_REPEAT, ref.text, tr("Edit and repeat…"))
            if (hit.flow.tls && (wrong || TrafficIssue.Kind.MAPPED in kinds)) {
                steps += call(AssistantActions.Name.OPEN_MAP_LOCAL, ref.text, tr("Answer it from the phone…"))
            }
        }
        if (TrafficIssue.Kind.REFUSED in kinds) steps += call(AssistantActions.Name.OPEN_TLS_HOSTS, "", tr("Choose the hosts to decrypt…"))
        if (isSite(host) && !context.recording) {
            val worth = listOf(TrafficIssue.Kind.CLEARTEXT, TrafficIssue.Kind.SECRET_UNENCRYPTED, TrafficIssue.Kind.TRACKER, TrafficIssue.Kind.DOWNGRADE)
            if (worth.any { it in kinds } || !wrong) {
                steps += call(AssistantActions.Name.CHECK_SITE, "https://$host", tr("Check the site's certificate and domain"))
            }
            if (TrafficIssue.Kind.SLOW in kinds || TrafficIssue.Kind.NO_ANSWER in kinds) {
                steps += CardStep(
                    JSONObject().put("tool", AssistantActions.Name.TRACE_ROUTE.raw).put("host", host).toString(),
                    tr("Trace the path to %s", AssistantDigest.clean(host, 30)),
                )
            }
        }
        return steps.distinctBy { it.tool }
    }

    private fun call(name: AssistantActions.Name, text: String, label: String) =
        CardStep(JSONObject().put("tool", name.raw).put("text", text).toString(), label)

    /** A public name the registry and a certificate can be asked about: not an address, a port or a local name. */
    private fun isSite(host: String): Boolean = '.' in host && ':' !in host && !host.endsWith(".local") && host.any(Char::isLetter) &&
        LinkInspector.inspect("https://$host")?.let(LinkShelf::isWeb) == true

    // Words for the model.

    fun title(endpoint: Endpoint): String = AssistantDigest.clean(endpoint.key, 90)

    /** `GET api.example.com/v1/users/{id} ×40, 3 errors, usually 240 ms`. */
    private fun brief(group: EndpointGroup): String = listOfNotNull(
        "${AssistantDigest.clean(group.key, 70)} ×${group.hits.size}",
        if (group.errors == 0) "no errors" else "errors: ${group.errors}",
        group.typicalMs?.let { "usually $it ms" },
    ).joinToString(", ")

    private fun service(host: String): String {
        val name = AssistantDigest.clean(host, 60)
        val service = TrackerHosts.serviceOf(host) ?: return "$name; not in the list of known analytics and advertising services"
        return "$name; belongs to ${service.name}, known for ${TrafficAudit.englishKind(service.kind)}"
    }

    private fun app(hit: RequestHit): String {
        hit.flow.app?.takeIf { it.isNotEmpty() }?.let { return AssistantDigest.clean(it) }
        // `Telegram/10.2 CFNetwork/…`: the product's name only.
        val product = hit.exchange.header("User-Agent", response = false)?.split('/', ' ', '(')?.firstOrNull { it.isNotEmpty() } ?: return "unknown"
        if (product == "Mozilla") return "a browser, or a page inside an app"
        return AssistantDigest.clean(product, 30) + " (by its User-Agent)"
    }

    private fun secrets(scans: List<TrafficAudit.Scan>): String {
        val found = scans.flatMap { it.secrets }.distinct()
        if (found.isEmpty()) return "none found"
        val open = scans.any { it.cleartext && it.secrets.isNotEmpty() }
        val (address, inside) = found.partition { it.place.inAddress }
        var text = when {
            open -> "sent UNENCRYPTED: ${TrafficAudit.englishList(found)}"
            address.isEmpty() -> "none in the address"
            else -> "in the address: ${TrafficAudit.englishList(address)}"
        }
        // Signing in with a header over TLS is how it is done: said outright, or the model calls it a leak.
        if (!open && inside.isNotEmpty()) text += "; sign-in data in headers or the body, sent encrypted, which is normal"
        return text
    }

    private fun cookies(hits: List<RequestHit>): String {
        val weak = hits.flatMap(TrafficAudit::weakCookies).distinct()
        return if (weak.isEmpty()) "none" else weak.take(4).joinToString("; ") { "`${it.name}` without ${it.missing.joinToString(" and ")}" }
    }

    private fun compression(hit: RequestHit): String {
        if (TrafficAudit.isUncompressed(hit)) return "none, although the app accepts it and the answer is text"
        return hit.exchange.header("Content-Encoding", response = true)?.let { AssistantDigest.clean(it, 20) } ?: "not applicable"
    }

    /** Names only, each safe to show; explicitly none when there are none. */
    private fun names(fields: List<HttpField>): String {
        val names = fields.map { TrafficSecrets.safeName(it.name) }.distinct()
        return if (names.isEmpty()) "none" else names.take(8).joinToString(", ") { "`$it`" } + if (names.size > 8) " and ${names.size - 8} more" else ""
    }

    private fun tallyErrors(hits: List<RequestHit>): String {
        val errors = hits.mapNotNull { it.exchange.status }.filter { it >= 400 }
        return if (errors.isEmpty()) "none" else "${errors.size} of ${hits.size} (${TrafficAudit.tally(errors)})"
    }

    private fun text(lines: List<String>): String {
        val text = lines.joinToString("\n")
        return if (text.length > BUDGET) text.take(BUDGET) + "\n…(cut)" else text
    }

    fun bytes(count: Long?): String = when {
        count == null -> "unknown"
        count >= 1_048_576 -> String.format(Locale.US, "%.1f MB", count / 1_048_576.0)
        count >= 1024 -> "${count / 1024} KB"
        else -> "$count B"
    }

    // Repeating a request.

    /** What a repeated request showed against the recorded one, for the model: statuses, times and sizes only. */
    fun compare(exchange: HttpExchange, again: RepeatResult): String {
        val before = "Recorded: ${exchange.status?.let { "status $it" } ?: "no answer"}, ${exchange.durationMs?.let { "$it ms" } ?: "time unknown"}, body ${bytes(exchange.responseBodyBytes)}."
        // The system's message can quote the whole address, so it is not passed on.
        val status = again.status ?: return "$before\nSent again just now: no answer."
        var text = "$before\nSent again just now: status $status, ${again.durationMs} ms, body ${bytes(again.body.size.toLong())} after decompression."
        text += "\nStatus: ${if (exchange.status == status) "the same" else "changed"}."
        if (status == 401 || status == 403) text += " The recorded sign-in may simply have expired since."
        return text
    }
}
