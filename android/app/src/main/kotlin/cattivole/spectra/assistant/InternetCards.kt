package cattivole.spectra.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cattivole.spectra.core.DomainOutcome
import cattivole.spectra.core.LinkInspection
import cattivole.spectra.core.LinkInspector
import cattivole.spectra.core.SiteReport
import cattivole.spectra.platform.RedirectTrace
import cattivole.spectra.ui.tr
import org.json.JSONObject
import java.util.Locale

/*
 * Summary cards about the way out: the internet connection (Connection, Speed test, Traceroute screens) and one link
 * (Check a link). Same contract as [AssistantCards]: the findings and the buttons come from these rules, the model
 * only puts them into words. Checked by tests (InternetCardsTest).
 */
object InternetCards {
    /** A connection check older than this is offered again first. */
    const val STALE_MS = 60 * 60_000L
    const val SLOW_DOWN_MBPS = 5.0
    const val HIGH_LATENCY_MS = 150
    const val HIGH_JITTER_MS = 30
    const val SLOW_DNS_MS = 300
    const val SLOW_GATEWAY_MS = 100
    /** A delay that grows this much between two answering hops is where the path slows down. */
    const val HOP_JUMP_MS = 80

    // MARK: - The internet connection

    fun internet(s: AssistantSnapshot, now: Long = System.currentTimeMillis()): CardFacts {
        val found = mutableListOf<CardFinding>()
        // Steps in the order they matter; one button per kind of check, three at most.
        val steps = mutableListOf<CardStep>()
        val link = s.link
        val checked = link?.status != null
        val stale = link != null && now - link.checkedAt > STALE_MS
        val online = link?.status == "online"
        // The user's own sites first: they are what the user cares about, ahead of the reference checks at the
        // same level. One that does not open is worth a trace to see where the way breaks.
        val failing = s.sites.filter { !it.ok }
        val opening = s.sites.filter { it.ok }
        if (failing.isNotEmpty()) {
            found += CardFinding(CardRisk.WARNING, tr("Your sites that do not open: %s.", failing.take(4).joinToString(", ") { AssistantDigest.clean(it.host, 30) }))
        }
        if (opening.isNotEmpty()) {
            found += CardFinding(CardRisk.OK, tr("Your sites that open: %s.", opening.take(4).joinToString(", ") { AssistantDigest.clean(it.host, 30) }))
        }
        if (!checked) {
            found += CardFinding(CardRisk.INFO, tr("The connection has not been checked yet: DNS, the provider, HTTPS interception and blocked services are unknown."))
            steps += AssistantCards.step(AssistantActions.Name.CHECK_CONNECTION, tr("Check the connection"))
        } else {
            connectionFindings(link!!, found)
            if (!online || stale) steps += AssistantCards.step(AssistantActions.Name.CHECK_CONNECTION, tr("Check the connection again"))
        }
        if (link != null && stale) found += CardFinding(CardRisk.INFO, tr("Checked %s ago: the results may be out of date.", age(now - link.checkedAt)))
        failing.firstOrNull()?.let { steps += trace(it.host) }


        val slowPath = link?.latencyMs?.let { it >= HIGH_LATENCY_MS } == true || link?.gatewayMs?.let { it >= SLOW_GATEWAY_MS } == true
        if (checked && (!online || slowPath)) steps += trace(s.route?.target ?: cattivole.spectra.core.RouteTrace.DEFAULT_TARGET)

        val speedSteps = speedFindings(link, found)
        steps += speedSteps

        val filtered = link?.access == "filtered" || link?.access == "whitelist_only"
        if (s.sites.isEmpty() || failing.isNotEmpty() || filtered) {
            steps += AssistantCards.step(
                AssistantActions.Name.CHECK_SITES,
                if (s.sites.isEmpty()) tr("Check access to your sites") else tr("Check your sites again"),
            )
        }

        s.route?.let { route -> routeFindings(route, found) }
        s.route?.takeIf { !it.running && !it.reached && it.hops.isNotEmpty() }?.let { steps += trace(it.target, again = true) }
        if (checked) steps += AssistantCards.step(AssistantActions.Name.CHECK_CONNECTION, tr("Check the connection again"))

        val title = listOfNotNull(tr("Internet"), link?.provider?.let { AssistantDigest.clean(it, 30) }).joinToString(" · ")
        val data = (AssistantDigest.section(AssistantTopic.LINK, s, now).lines() + s.route?.let(AssistantDigest::routeLines).orEmpty()).joinToString("\n")
        return CardFacts(
            CardSubject.Internet,
            title,
            AssistantCards.ranked(found),
            steps.distinctBy { it.tool }.take(3),
            data,
            data + "\n\n" + AssistantCards.sections(s, now, AssistantTopic.HISTORY) + AssistantCards.memory(s),
            awaitsCheck = !checked && link == null && s.sites.isEmpty() && s.route == null,
        )
    }

    /** What the connection check says: whether it works, DNS, the router, HTTPS, open ports, the provider, access. */
    private fun connectionFindings(link: AssistantSnapshot.Link, found: MutableList<CardFinding>) {
        when (link.status) {
            "online" -> found += CardFinding(
                CardRisk.OK,
                link.webMs?.let { tr("The internet works: a page answers in %d ms.", it) } ?: tr("The internet works."),
            )
            "captive" -> found += CardFinding(CardRisk.WARNING, tr("The network asks for a sign-in page before it lets traffic out."))
            "lan" -> found += CardFinding(CardRisk.CRITICAL, tr("The router answers, but the internet does not."))
            "no_network" -> found += CardFinding(CardRisk.CRITICAL, tr("The phone is not connected to any network."))
            else -> found += CardFinding(CardRisk.CRITICAL, tr("No internet on this connection."))
        }
        if (link.intercepted == true) found += CardFinding(CardRisk.CRITICAL, tr("HTTPS certificates look re-signed on this connection: possible interception."))
        if (link.exposedPorts.isNotEmpty()) {
            found += CardFinding(CardRisk.CRITICAL, tr("Ports reachable from the internet: %s.", link.exposedPorts.take(6).joinToString(", ")))
        }
        when (link.access) {
            "whitelist_only" -> found += CardFinding(CardRisk.CRITICAL, tr("Only allow-listed sites open: the rest of the internet is cut off."))
            "none" -> if (link.status == "online") found += CardFinding(CardRisk.CRITICAL, tr("None of the reference sites answered."))
            "filtered" -> found += CardFinding(
                CardRisk.WARNING,
                if (link.blocked.isEmpty()) tr("Some often-blocked services do not open.")
                else tr("Often-blocked services do not open: %s.", link.blocked.take(4).joinToString(", ") { AssistantDigest.clean(it, 30) }),
            )
            "full" -> found += CardFinding(CardRisk.OK, tr("Foreign sites and often-blocked services open."))
        }
        if (link.dnsOk == false && link.status == "online") {
            found += CardFinding(CardRisk.CRITICAL, tr("Name lookups (DNS) fail: sites open only by their address."))
        } else if (link.dnsMs != null && link.dnsMs >= SLOW_DNS_MS) {
            found += CardFinding(CardRisk.WARNING, tr("Name lookups are slow: %d ms.", link.dnsMs))
        }
        if (link.resolverLies == true) found += CardFinding(CardRisk.WARNING, tr("The DNS resolver answers for names that do not exist."))
        if (link.gatewayMs != null && link.gatewayMs >= SLOW_GATEWAY_MS) {
            found += CardFinding(CardRisk.WARNING, tr("The router answers slowly (%d ms): weak Wi‑Fi signal or a busy router.", link.gatewayMs))
        }
        if (link.intercepted == false) found += CardFinding(CardRisk.OK, tr("HTTPS certificates are genuine: nothing re-signs them on the way."))
        if (link.exposureChecked && link.exposedPorts.isEmpty()) found += CardFinding(CardRisk.OK, tr("No usual port of your public address answers from the internet."))
        link.publicIp?.let { ip ->
            val parts = listOfNotNull(ip, link.provider?.let { AssistantDigest.clean(it, 40) }, link.country)
            found += CardFinding(CardRisk.INFO, tr("Public address: %s.", parts.joinToString(" · ")))
        }
        link.resolverName?.let { name ->
            found += CardFinding(CardRisk.INFO, tr("Name lookups are answered by %s.", AssistantDigest.clean(name, 40) + (link.resolverCountry?.let { " · $it" } ?: "")))
        }
        if (link.via == "vpn") found += CardFinding(CardRisk.INFO, tr("Checked through the VPN: the address and provider are the VPN's."))
        if (link.warp) found += CardFinding(CardRisk.INFO, tr("Traffic leaves through Cloudflare WARP."))
    }

    /** What the speed says; returns the speed-test button when one is worth pressing. */
    private fun speedFindings(link: AssistantSnapshot.Link?, found: MutableList<CardFinding>): List<CardStep> {
        val down = link?.downMbps
        if (down == null) {
            found += CardFinding(CardRisk.INFO, if (link?.speedFailed == true) tr("The speed test did not finish.") else tr("The speed has not been measured yet."))
            return listOf(AssistantCards.step(AssistantActions.Name.SPEED_TEST, tr("Measure the speed")))
        }
        var poor = false
        if (down < SLOW_DOWN_MBPS) {
            poor = true
            found += CardFinding(CardRisk.WARNING, tr("Slow download: %s Mbit/s: video and calls may stutter.", mbps(down)))
        } else {
            found += CardFinding(
                CardRisk.INFO,
                link.upMbps?.let { tr("Speed: %s Mbit/s down, %s up.", mbps(down), mbps(it)) } ?: tr("Speed: %s Mbit/s down.", mbps(down)),
            )
        }
        link.latencyMs?.takeIf { it >= HIGH_LATENCY_MS }?.let {
            poor = true
            found += CardFinding(CardRisk.WARNING, tr("High latency: %d ms: calls and games will lag.", it))
        }
        link.jitterMs?.takeIf { it >= HIGH_JITTER_MS }?.let {
            poor = true
            found += CardFinding(CardRisk.WARNING, tr("Latency jumps by %d ms: calls may break up.", it))
        }
        link.bufferbloat?.takeIf { it == "D" || it == "F" }?.let {
            poor = true
            found += CardFinding(CardRisk.WARNING, tr("Latency grows a lot under load (grade %s): calls stutter while something downloads.", it))
        }
        return if (poor) listOf(AssistantCards.step(AssistantActions.Name.SPEED_TEST, tr("Measure the speed again"))) else emptyList()
    }

    /** What the last traceroute says: whether it got there and where the delay grows. */
    private fun routeFindings(route: AssistantSnapshot.Route, found: MutableList<CardFinding>) {
        val target = AssistantDigest.clean(route.target, 40)
        when {
            route.running -> found += CardFinding(CardRisk.INFO, tr("The traceroute to %s is still running.", target))
            route.hops.isEmpty() -> route.failure?.let { found += CardFinding(CardRisk.INFO, tr("Traceroute to %s: %s", target, tr(it))) }
            route.reached -> found += CardFinding(CardRisk.INFO, tr("The path to %s takes %d hops.", target, route.hops.size))
            else -> found += CardFinding(
                CardRisk.INFO,
                tr("The trace to %s stops after %d hops: the rest do not answer, more often a firewall than a break.", target, route.hops.size - route.silentTail),
            )
        }
        slowestHop(route.hops)?.let { (index, jump) ->
            val hop = route.hops[index]
            found += CardFinding(
                CardRisk.INFO,
                tr("The delay grows most at hop %d (%s): +%d ms.", index + 1, listOfNotNull(hop.address, hop.place?.let { AssistantDigest.clean(it, 40) }).joinToString(" · "), jump),
            )
        }
    }

    /** The hop where the delay jumps the most over the previous answering hop, when the jump is large. */
    fun slowestHop(hops: List<AssistantSnapshot.RouteHop>): Pair<Int, Int>? {
        var previous: Int? = null
        var best: Pair<Int, Int>? = null
        hops.forEachIndexed { index, hop ->
            val ms = hop.millis ?: return@forEachIndexed
            previous?.let { before ->
                val jump = ms - before
                if (jump >= HOP_JUMP_MS && jump > (best?.second ?: 0)) best = index to jump
            }
            previous = ms
        }
        return best
    }

    private fun trace(host: String, again: Boolean = false): CardStep = CardStep(
        "{\"tool\": \"${AssistantActions.Name.TRACE_ROUTE.raw}\", \"host\": ${JSONObject.quote(host)}}",
        if (again) tr("Trace %s again", host) else tr("Trace the route to %s", host),
    )

    private fun mbps(value: Double): String =
        if (value >= 100) Math.round(value).toString() else String.format(Locale.getDefault(), "%.1f", value)

    /** "5 min", "3 h", "2 d": how long ago, in the reader's words. */
    private fun age(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 60 -> tr("%d min", minutes.toInt())
            minutes < 48 * 60 -> tr("%d h", (minutes / 60).toInt())
            else -> tr("%d d", (minutes / 1440).toInt())
        }
    }

    // MARK: - One link

    fun link(text: String, site: SiteReport?, redirects: RedirectTrace?, now: Long = System.currentTimeMillis()): CardFacts {
        val inspection = LinkInspector.inspect(text)
            ?: return CardFacts(
                CardSubject.Link(text),
                tr("Check a link"),
                listOf(CardFinding(CardRisk.INFO, tr("No link found in that text."))),
                emptyList(),
                "No link found in the text.",
                "No link found in the text.",
                awaitsCheck = true,
            )
        val found = mutableListOf<CardFinding>()
        when (inspection.verdict) {
            LinkInspection.Verdict.DANGEROUS -> found += CardFinding(CardRisk.CRITICAL, tr("Looks like phishing: do not open it and do not enter anything on it."))
            LinkInspection.Verdict.SUSPICIOUS -> found += CardFinding(CardRisk.WARNING, tr("Several things about this address are typical of phishing."))
            LinkInspection.Verdict.CAUTION -> Unit // The one thing that stands out follows on its own.
            LinkInspection.Verdict.NO_FLAGS -> found += CardFinding(CardRisk.OK, tr("The address itself shows no warning signs."))
        }
        for (finding in inspection.findings) found += finding.card()
        for (inner in inspection.nested) {
            for (finding in inner.findings) found += CardFinding(finding.severity.risk(), tr("Inside the link (%s): ", inner.site.ifEmpty { inner.link }) + finding.shown())
        }
        val web = LinkShelf.isWeb(inspection)
        if (site != null) for (finding in site.findings(now)) found += finding.card()
        redirects?.let { redirectFindings(inspection, it, found) }
        val steps = mutableListOf<CardStep>()
        if (web && site == null) {
            found += CardFinding(CardRisk.INFO, tr("The certificate and the domain's age have not been checked yet."))
            steps += CardStep(call(AssistantActions.Name.CHECK_SITE, inspection.link), tr("Check certificate and domain age"))
        }
        if (web && redirects == null) {
            found += CardFinding(CardRisk.INFO, tr("Where the link really leads has not been checked yet."))
            steps += CardStep(call(AssistantActions.Name.EXPAND_REDIRECTS, inspection.link), tr("Expand redirects"))
        }
        val data = linkData(inspection, site, redirects, now)
        return CardFacts(
            CardSubject.Link(inspection.link),
            inspection.site.ifEmpty { inspection.host.ifEmpty { inspection.link } },
            AssistantCards.ranked(found),
            steps,
            data,
            data,
        )
    }

    private fun redirectFindings(inspection: LinkInspection, trace: RedirectTrace, found: MutableList<CardFinding>) {
        val moves = trace.hops.count { it.status in 300..399 }
        val final = LinkInspector.inspect(trace.final)
        when {
            moves == 0 && trace.stop == null -> found += CardFinding(CardRisk.OK, tr("No redirects: the link opens where it says."))
            moves > 0 -> {
                val end = final?.site?.ifEmpty { null } ?: AssistantDigest.clean(trace.final, 60)
                found += CardFinding(CardRisk.INFO, if (moves == 1) tr("Redirects once and ends at %s.", end) else tr("Redirects %d times and ends at %s.", moves, end))
            }
        }
        if (final != null && final.site.isNotEmpty() && !final.site.equals(inspection.site, ignoreCase = true)) {
            found += CardFinding(CardRisk.WARNING, tr("It leads to another site: %s.", final.site))
            when (final.verdict) {
                LinkInspection.Verdict.DANGEROUS -> found += CardFinding(CardRisk.CRITICAL, tr("The final address looks like phishing: %s.", final.site))
                LinkInspection.Verdict.SUSPICIOUS -> found += CardFinding(CardRisk.WARNING, tr("The final address has warning signs: %s.", final.site))
                else -> Unit
            }
        }
        when (val stop = trace.stop) {
            RedirectTrace.Stop.TooManyHops -> found += CardFinding(CardRisk.WARNING, tr("Stopped after 10 redirects."))
            is RedirectTrace.Stop.LeftTheWeb -> found += CardFinding(CardRisk.WARNING, tr("The last redirect leaves the web for a %s: link.", stop.scheme))
            is RedirectTrace.Stop.Failed -> found += CardFinding(CardRisk.INFO, tr("Stopped: %s", AssistantDigest.clean(stop.reason, 80)))
            null -> Unit
        }
    }

    /** The link in plain English for the model: the address, what stands out, the certificate, the registry, the way. */
    fun linkData(inspection: LinkInspection, site: SiteReport?, redirects: RedirectTrace?, now: Long = System.currentTimeMillis()): String {
        val lines = mutableListOf("Link: ${AssistantDigest.clean(inspection.link, 200)}")
        if (inspection.site.isNotEmpty()) lines += "- Site: ${AssistantDigest.clean(inspection.site, 80)}"
        if (inspection.host.isNotEmpty() && inspection.host != inspection.site) lines += "- Full name: ${AssistantDigest.clean(inspection.host, 120)}"
        inspection.asciiHost?.let { lines += "- In the address bar it reads: ${AssistantDigest.clean(it, 120)}" }
        lines += "- The app's verdict on the address: ${inspection.verdict.name.lowercase().replace('_', ' ')}"
        for (finding in inspection.findings) lines += "- [${finding.severity.name.lowercase()}] ${finding.english()}"
        for (inner in inspection.nested) {
            lines += "- Inside it, another link: ${AssistantDigest.clean(inner.link, 120)} (verdict ${inner.verdict.name.lowercase().replace('_', ' ')})"
            for (finding in inner.findings) lines += "  - [${finding.severity.name.lowercase()}] ${finding.english()}"
        }
        lines += if (site != null) siteText(site, now).lines() else listOf("Certificate and domain registry: not checked yet.")
        lines += if (redirects != null) redirectText(redirects).lines() else listOf("Redirects: not followed yet.")
        return lines.joinToString("\n")
    }

    /** A certificate and registry check, in English: what a "check_site" step returns. */
    fun siteText(report: SiteReport, now: Long = System.currentTimeMillis()): String {
        val lines = mutableListOf("Certificate and domain of ${AssistantDigest.clean(report.host, 80)}, checked just now:")
        val certificate = report.certificate
        when {
            certificate != null -> lines += "- Certificate from ${AssistantDigest.clean(certificate.issuer ?: "unknown issuer", 80)}, valid " +
                "${day(certificate.notBefore)} to ${day(certificate.notAfter)}, issued ${days(certificate.notBefore, now)} days ago; " +
                if (certificate.trusted) "the phone trusts it" else "the phone does NOT trust it (${AssistantDigest.clean(certificate.trustError ?: "unknown reason", 80)})"
            report.certificateError != null -> lines += "- No usable HTTPS: ${AssistantDigest.clean(report.certificateError, 100)}"
        }
        when (val domain = report.domain) {
            is DomainOutcome.Found -> {
                val record = domain.report
                lines += "- Domain ${record.domain}: " + listOfNotNull(
                    record.registered?.let { "registered ${day(it)} (${days(it, now)} days ago)" } ?: "no registration date given",
                    record.expires?.let { "expires ${day(it)}" },
                    record.registrar?.let { "registrar ${AssistantDigest.clean(it, 60)}" },
                ).joinToString(", ")
            }
            is DomainOutcome.NotRegistered -> lines += "- The registry has no record of ${domain.name}"
            is DomainOutcome.UnsupportedZone -> lines += "- The .${domain.zone} registry does not publish registration dates"
            is DomainOutcome.Failed -> lines += "- The registry could not be reached: ${AssistantDigest.clean(domain.reason, 80)}"
            DomainOutcome.Skipped -> lines += "- Registry not asked (the address has no domain name)"
        }
        for (finding in report.findings(now)) lines += "- [${finding.severity.name.lowercase()}] ${finding.english()}"
        return lines.joinToString("\n")
    }

    /** The redirects as followed, in English: what an "expand_redirects" step returns. */
    fun redirectText(trace: RedirectTrace): String {
        val lines = mutableListOf("Redirects, followed by their headers only:")
        trace.hops.forEachIndexed { index, hop -> lines += "${index + 1}. ${AssistantDigest.clean(hop.url, 160)} → HTTP ${hop.status}" }
        lines += "Final address: ${AssistantDigest.clean(trace.final, 160)}"
        when (val stop = trace.stop) {
            RedirectTrace.Stop.TooManyHops -> lines += "Stopped after 10 redirects."
            is RedirectTrace.Stop.LeftTheWeb -> lines += "The last redirect leaves the web for a ${stop.scheme}: link."
            is RedirectTrace.Stop.Failed -> lines += "Stopped: ${AssistantDigest.clean(stop.reason, 100)}"
            null -> Unit
        }
        return lines.joinToString("\n")
    }

    private fun call(name: AssistantActions.Name, link: String) = "{\"tool\": \"${name.raw}\", \"text\": ${JSONObject.quote(link)}}"

    private fun LinkInspection.Severity.risk(): CardRisk = when (this) {
        LinkInspection.Severity.DANGER -> CardRisk.CRITICAL
        LinkInspection.Severity.CAUTION -> CardRisk.WARNING
        LinkInspection.Severity.INFO -> CardRisk.INFO
    }

    private fun LinkInspection.Finding.card() = CardFinding(severity.risk(), shown())

    /** In the reader's words: brand names in the arguments are translated on their own, as on the screen. */
    private fun LinkInspection.Finding.shown(): String = tr(text, *args.map { tr(it) }.toTypedArray())

    private fun LinkInspection.Finding.english(): String =
        AssistantDigest.clean(runCatching { String.format(Locale.US, text, *args.toTypedArray()) }.getOrDefault(text), 200)

    private fun day(millis: Long): String = AssistantDigest.clock(millis).substringBefore(' ')

    private fun days(from: Long, to: Long): Long = Math.floorDiv(to - from, 86_400_000L)
}

/**
 * What was checked about recent links: the certificate and registry answer and the redirects, kept while the app
 * runs. The Check a link screen and the card's buttons both write here, so either one shows what the other found.
 */
class LinkShelf {
    data class Entry(val site: SiteReport? = null, val redirects: RedirectTrace? = null)

    private var entries by mutableStateOf(mapOf<String, Entry>())

    fun entry(link: String): Entry = entries[link] ?: Entry()

    fun keepSite(link: String, report: SiteReport) = update(link) { it.copy(site = report) }

    fun keepRedirects(link: String, trace: RedirectTrace) = update(link) { it.copy(redirects = trace) }

    private fun update(link: String, change: (Entry) -> Entry) {
        val next = entries - link + (link to change(entry(link)))
        entries = if (next.size > KEEP) next.entries.drop(next.size - KEEP).associate { it.key to it.value } else next
    }

    fun facts(link: String, now: Long = System.currentTimeMillis()): CardFacts {
        val key = LinkInspector.inspect(link)?.link ?: link
        val entry = entry(key)
        return InternetCards.link(link, entry.site, entry.redirects, now)
    }

    companion object {
        const val KEEP = 8

        /** Only a web link with a name has a certificate and a registry record to ask about. */
        fun isWeb(inspection: LinkInspection): Boolean =
            (inspection.scheme == "https" || inspection.scheme == "http") && inspection.host.isNotEmpty()

        /** Where its redirects start: the link with its scheme. */
        fun start(inspection: LinkInspection): String =
            inspection.link.let { if (it.contains("://")) it else "${inspection.scheme}://$it" }

        /** The name to ask the registry about, or null for a bare address (no domain to look up). */
        fun registryName(target: LinkInspection): String? {
            val named = target.site != target.host || target.site.any { it.isLetter() }
            if (!named) return null
            // An account on a free host is not in any registry: its platform is.
            return if (target.findings.any { it.kind == LinkInspection.Kind.FREE_HOSTING }) {
                target.site.split('.').takeLast(2).joinToString(".")
            } else if (target.asciiHost != null) {
                target.asciiHost.split('.').takeLast(target.site.count { it == '.' } + 1).joinToString(".")
            } else {
                target.site
            }
        }
    }
}
