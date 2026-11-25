import Foundation

/// Summary cards about the way out: the internet connection (Connection, Speed test, Traceroute screens) and one
/// link (Check a link). Same contract as `AssistantCards`: the findings and the buttons come from these rules, the
/// model only puts them into words. Mirrors InternetCards.kt; checked by `InternetCardsTests`.
nonisolated enum InternetCards {
    /// A connection check older than this is offered again first.
    static let staleSeconds: TimeInterval = 60 * 60
    static let slowDownMbps = 5.0
    static let highLatencyMs = 150
    static let highJitterMs = 30
    static let slowDNSMs = 300
    static let slowGatewayMs = 100

    // MARK: - The internet connection

    static func internet(_ s: AssistantSnapshot, now: Date = .now) -> CardFacts {
        var found: [CardFinding] = []
        // Steps in the order they matter; one button per kind of check, three at most.
        var steps: [CardStep] = []
        let link = s.link
        let checked = link?.status != nil
        let stale = link.map { now.timeIntervalSince($0.checkedAt) > staleSeconds } ?? false
        let online = link?.status == "online"
        // The user's own sites first: they are what the user cares about, ahead of the reference checks at the
        // same level. One that does not open is worth a trace to see where the way breaks.
        let failing = s.sites.filter { !$0.ok }
        let opening = s.sites.filter(\.ok)
        if !failing.isEmpty {
            let list = failing.prefix(4).map { AssistantDigest.clean($0.host, limit: 30) }.joined(separator: ", ")
            found.append(.init(risk: .warning, text: String(localized: "Your sites that do not open: \(list).")))
        }
        if !opening.isEmpty {
            let list = opening.prefix(4).map { AssistantDigest.clean($0.host, limit: 30) }.joined(separator: ", ")
            found.append(.init(risk: .ok, text: String(localized: "Your sites that open: \(list).")))
        }
        if let link, checked {
            connectionFindings(link, into: &found)
            if !online || stale { steps.append(AssistantCards.step(.checkConnection, label: String(localized: "Check the connection again"))) }
        } else {
            found.append(.init(risk: .info, text: String(localized: "The connection has not been checked yet: DNS, the provider, HTTPS interception and blocked services are unknown.")))
            steps.append(AssistantCards.step(.checkConnection, label: String(localized: "Check the connection")))
        }
        if let link, stale {
            found.append(.init(risk: .info, text: String(localized: "Checked \(age(now.timeIntervalSince(link.checkedAt))) ago: the results may be out of date.")))
        }
        if let first = failing.first { steps.append(trace(first.host)) }

        let slowPath = (link?.latencyMs).map { $0 >= highLatencyMs } == true || (link?.gatewayMs).map { $0 >= slowGatewayMs } == true
        if checked && (!online || slowPath) { steps.append(trace(s.route?.target ?? RouteTrace.defaultTarget)) }

        steps += speedFindings(link, into: &found)

        let filtered = link?.access == "filtered" || link?.access == "whitelist_only"
        if s.sites.isEmpty || !failing.isEmpty || filtered {
            steps.append(AssistantCards.step(.checkSites, label: s.sites.isEmpty ? String(localized: "Check access to your sites")
                                                                                   : String(localized: "Check your sites again")))
        }

        if let route = s.route {
            routeFindings(route, into: &found)
            if !route.running && !route.reached && !route.hops.isEmpty { steps.append(trace(route.target, again: true)) }
        }
        if checked { steps.append(AssistantCards.step(.checkConnection, label: String(localized: "Check the connection again"))) }
        // A port that answers from outside leads to some device: the router's table names it.
        let ties = CrossLinks.internet(s)
        found += CrossLinks.findings(ties)
        if CrossLinks.asksForwarding(s) {
            steps.insert(AssistantCards.step(.checkPortForwarding, label: String(localized: "Ask the router where the open ports lead")), at: 0)
        }

        let title = [String(localized: "Internet"), link?.provider.map { AssistantDigest.clean($0, limit: 30) }].compactMap { $0 }.joined(separator: " · ")
        let connection = AssistantDigest.section(.link, snapshot: s, now: now)
        let data = [connection, s.route.map { AssistantDigest.routeLines($0, compact: true).joined(separator: "\n") }].compactMap { $0 }.joined(separator: "\n")
        let deep = [connection, s.route.map { AssistantDigest.routeLines($0).joined(separator: "\n") }].compactMap { $0 }.joined(separator: "\n")
        return CardFacts(
            subject: .internet,
            title: title,
            findings: AssistantCards.ranked(found),
            steps: Array(AssistantCards.unique(steps, by: \.tool).prefix(3)),
            data: data + CrossLinks.block(ties),
            deepData: deep + CrossLinks.block(ties) + "\n\n" + AssistantCards.sections(s, now: now, .history),
            awaitsCheck: !checked && link == nil && s.sites.isEmpty && s.route == nil
        )
    }

    /// What the connection check says: whether it works, DNS, the router, HTTPS, open ports, the provider, access.
    private static func connectionFindings(_ link: AssistantSnapshot.Link, into found: inout [CardFinding]) {
        switch link.status {
        case "online":
            found.append(.init(risk: .ok, text: link.webMs.map { String(localized: "The internet works: a page answers in \($0) ms.") }
                               ?? String(localized: "The internet works.")))
        case "captive": found.append(.init(risk: .warning, text: String(localized: "The network asks for a sign-in page before it lets traffic out.")))
        case "lan": found.append(.init(risk: .critical, text: String(localized: "The router answers, but the internet does not.")))
        case "no_network": found.append(.init(risk: .critical, text: String(localized: "The phone is not connected to any network.")))
        default: found.append(.init(risk: .critical, text: String(localized: "No internet on this connection.")))
        }
        if link.intercepted == true {
            found.append(.init(risk: .critical, text: String(localized: "HTTPS certificates look re-signed on this connection: possible interception.")))
        }
        if !link.exposedPorts.isEmpty {
            let ports = link.exposedPorts.prefix(6).map(String.init).joined(separator: ", ")
            found.append(.init(risk: .critical, text: String(localized: "Ports reachable from the internet: \(ports).")))
        }
        switch link.access {
        case "whitelist_only":
            found.append(.init(risk: .critical, text: String(localized: "Only allow-listed sites open: the rest of the internet is cut off.")))
        case "none":
            if link.status == "online" { found.append(.init(risk: .critical, text: String(localized: "None of the reference sites answered."))) }
        case "filtered":
            if link.blocked.isEmpty {
                found.append(.init(risk: .warning, text: String(localized: "Some often-blocked services do not open.")))
            } else {
                let list = link.blocked.prefix(4).map { AssistantDigest.clean($0, limit: 30) }.joined(separator: ", ")
                found.append(.init(risk: .warning, text: String(localized: "Often-blocked services do not open: \(list).")))
            }
        case "full": found.append(.init(risk: .ok, text: String(localized: "Foreign sites and often-blocked services open.")))
        default: break
        }
        if link.dnsOk == false && link.status == "online" {
            found.append(.init(risk: .critical, text: String(localized: "Name lookups (DNS) fail: sites open only by their address.")))
        } else if let dns = link.dnsMs, dns >= slowDNSMs {
            found.append(.init(risk: .warning, text: String(localized: "Name lookups are slow: \(dns) ms.")))
        }
        if link.resolverLies == true {
            found.append(.init(risk: .warning, text: String(localized: "The DNS resolver answers for names that do not exist.")))
        }
        if let gateway = link.gatewayMs, gateway >= slowGatewayMs {
            found.append(.init(risk: .warning, text: String(localized: "The router answers slowly (\(gateway) ms): weak Wi‑Fi signal or a busy router.")))
        }
        if link.intercepted == false {
            found.append(.init(risk: .ok, text: String(localized: "HTTPS certificates are genuine: nothing re-signs them on the way.")))
        }
        if link.exposureChecked && link.exposedPorts.isEmpty {
            found.append(.init(risk: .ok, text: String(localized: "No usual port of your public address answers from the internet.")))
        }
        if let ip = link.publicIP {
            let parts = [ip, link.provider.map { AssistantDigest.clean($0, limit: 40) }, link.country].compactMap { $0 }.joined(separator: " · ")
            found.append(.init(risk: .info, text: String(localized: "Public address: \(parts).")))
        }
        if let name = link.resolverName {
            let who = AssistantDigest.clean(name, limit: 40) + (link.resolverCountry.map { " · \($0)" } ?? "")
            found.append(.init(risk: .info, text: String(localized: "Name lookups are answered by \(who).")))
        }
        if link.via == "vpn" {
            found.append(.init(risk: .info, text: String(localized: "Checked through the VPN: the address and provider are the VPN's.")))
        }
        if link.warp { found.append(.init(risk: .info, text: String(localized: "Traffic leaves through Cloudflare WARP."))) }
    }

    /// What the speed says; returns the speed-test button when one is worth pressing.
    private static func speedFindings(_ link: AssistantSnapshot.Link?, into found: inout [CardFinding]) -> [CardStep] {
        guard let link, let down = link.downMbps else {
            found.append(.init(risk: .info, text: link?.speedFailed == true ? String(localized: "The speed test did not finish.")
                                                                            : String(localized: "The speed has not been measured yet.")))
            return [AssistantCards.step(.speedTest, label: String(localized: "Measure the speed"))]
        }
        var poor = false
        if down < slowDownMbps {
            poor = true
            found.append(.init(risk: .warning, text: String(localized: "Slow download: \(mbps(down)) Mbit/s: video and calls may stutter.")))
        } else if let up = link.upMbps {
            found.append(.init(risk: .info, text: String(localized: "Speed: \(mbps(down)) Mbit/s down, \(mbps(up)) up.")))
        } else {
            found.append(.init(risk: .info, text: String(localized: "Speed: \(mbps(down)) Mbit/s down.")))
        }
        if let latency = link.latencyMs, latency >= highLatencyMs {
            poor = true
            found.append(.init(risk: .warning, text: String(localized: "High latency: \(latency) ms: calls and games will lag.")))
        }
        if let jitter = link.jitterMs, jitter >= highJitterMs {
            poor = true
            found.append(.init(risk: .warning, text: String(localized: "Latency jumps by \(jitter) ms: calls may break up.")))
        }
        if let grade = link.bufferbloat, grade == "D" || grade == "F" {
            poor = true
            found.append(.init(risk: .warning, text: String(localized: "Latency grows a lot under load (grade \(grade)): calls stutter while something downloads.")))
        }
        if poor { return [AssistantCards.step(.speedTest, label: String(localized: "Measure the speed again"))] }
        // The connection check samples the download only: the full test adds upload and latency under load.
        if link.upMbps == nil && link.bufferbloat == nil {
            return [AssistantCards.step(.speedTest, label: String(localized: "Measure the speed fully"))]
        }
        return []
    }

    /// What the last traceroute says: whether it got there and where the delay grows.
    private static func routeFindings(_ route: AssistantSnapshot.Route, into found: inout [CardFinding]) {
        let target = AssistantDigest.clean(route.target, limit: 40)
        if route.running {
            found.append(.init(risk: .info, text: String(localized: "The traceroute to \(target) is still running.")))
        } else if route.hops.isEmpty {
            if let failure = route.failure { found.append(.init(risk: .info, text: String(localized: "Traceroute to \(target): \(failure)"))) }
        } else if route.reached {
            found.append(.init(risk: .ok, text: String(localized: "The path to \(target) takes \(route.hops.count) hops: it answers.")))
        } else {
            let answered = route.hops.count - route.silentTail
            found.append(.init(risk: .info, text: String(localized: "The trace to \(target) stops after \(answered) hops: the rest do not answer, more often a firewall than a break.")))
        }
        if let slow = AssistantDigest.slowestHop(route.hops) {
            let hop = route.hops[slow.index]
            let place = [hop.address, hop.place.map { AssistantDigest.clean($0, limit: 40) }].compactMap { $0 }.joined(separator: " · ")
            found.append(.init(risk: .info, text: String(localized: "The delay grows most at hop \(slow.index + 1) (\(place)): +\(slow.jump) ms.")))
        }
    }

    private static func trace(_ host: String, again: Bool = false) -> CardStep {
        let quoted = AssistantCards.jsonString(host) ?? "\"\(host)\""
        return CardStep(call: "{\"tool\": \"\(AssistantActions.Name.traceRoute.rawValue)\", \"host\": \(quoted)}",
                        label: again ? String(localized: "Trace \(host) again") : String(localized: "Trace the route to \(host)"))
    }

    static func mbps(_ value: Double) -> String {
        value >= 100 ? String(Int(value.rounded())) : value.formatted(.number.precision(.fractionLength(1)))
    }

    /// "5 min", "3 h", "2 d": how long ago, in the reader's words.
    private static func age(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        if minutes < 60 { return String(localized: "\(minutes) min") }
        if minutes < 48 * 60 { return String(localized: "\(minutes / 60) h") }
        return String(localized: "\(minutes / 1440) d")
    }

    // MARK: - One link

    static func link(_ text: String, site: SiteReport?, redirects: RedirectTrace?, now: Date = .now) -> CardFacts {
        guard let inspection = LinkInspector.inspect(text) else {
            return CardFacts(
                subject: .link(text), title: String(localized: "Check a link"),
                findings: [.init(risk: .info, text: String(localized: "No link found in that text."))],
                steps: [], data: "No link found in the text.", deepData: "No link found in the text.", awaitsCheck: true
            )
        }
        var found: [CardFinding] = []
        switch inspection.verdict {
        case .dangerous: found.append(.init(risk: .critical, text: String(localized: "Looks like phishing: do not open it and do not enter anything on it.")))
        case .suspicious: found.append(.init(risk: .warning, text: String(localized: "Several things about this address are typical of phishing.")))
        case .caution: break // The one thing that stands out follows on its own.
        case .noFlags: found.append(.init(risk: .ok, text: String(localized: "The address itself shows no warning signs.")))
        }
        for finding in inspection.findings { found.append(card(finding)) }
        for inner in inspection.nested {
            let name = inner.site.isEmpty ? inner.link : inner.site
            for finding in inner.findings {
                found.append(.init(risk: risk(finding.severity), text: String(localized: "Inside the link (\(name)): ") + finding.text))
            }
        }
        let web = LinkShelf.isWeb(inspection)
        if let site { for finding in site.findings(now: now) { found.append(card(finding)) } }
        if let redirects { redirectFindings(inspection, redirects, into: &found) }
        var steps: [CardStep] = []
        if web && site == nil {
            found.append(.init(risk: .info, text: String(localized: "The certificate and the domain's age have not been checked yet.")))
            steps.append(CardStep(call: call(.checkSite, inspection.link), label: String(localized: "Check certificate and domain age")))
        }
        if web && redirects == nil {
            found.append(.init(risk: .info, text: String(localized: "Where the link really leads has not been checked yet.")))
            steps.append(CardStep(call: call(.expandRedirects, inspection.link), label: String(localized: "Expand redirects")))
        }
        let data = linkData(inspection, site: site, redirects: redirects, now: now)
        let title = !inspection.site.isEmpty ? inspection.site : !inspection.host.isEmpty ? inspection.host : inspection.link
        return CardFacts(subject: .link(inspection.link), title: title, findings: AssistantCards.ranked(found), steps: steps, data: data, deepData: data)
    }

    private static func redirectFindings(_ inspection: LinkInspection, _ trace: RedirectTrace, into found: inout [CardFinding]) {
        let moves = trace.hops.filter { (300..<400).contains($0.status) }.count
        let final = LinkInspector.inspect(trace.final.absoluteString)
        if moves == 0 && trace.stop == nil {
            found.append(.init(risk: .ok, text: String(localized: "No redirects: the link opens where it says.")))
        } else if moves > 0 {
            let end = final.flatMap { $0.site.isEmpty ? nil : $0.site } ?? AssistantDigest.clean(trace.final.absoluteString, limit: 60)
            found.append(.init(risk: .info, text: moves == 1 ? String(localized: "Redirects once and ends at \(end).")
                                                             : String(localized: "Redirects \(moves) times and ends at \(end).")))
        }
        if let final, !final.site.isEmpty, final.site.caseInsensitiveCompare(inspection.site) != .orderedSame {
            found.append(.init(risk: .warning, text: String(localized: "It leads to another site: \(final.site).")))
            switch final.verdict {
            case .dangerous: found.append(.init(risk: .critical, text: String(localized: "The final address looks like phishing: \(final.site).")))
            case .suspicious: found.append(.init(risk: .warning, text: String(localized: "The final address has warning signs: \(final.site).")))
            default: break
            }
        }
        switch trace.stop {
        case .tooManyHops: found.append(.init(risk: .warning, text: String(localized: "Stopped after 10 redirects.")))
        case .leftTheWeb(let scheme): found.append(.init(risk: .warning, text: String(localized: "The last redirect leaves the web for a \(scheme): link.")))
        case .failed(let reason): found.append(.init(risk: .info, text: String(localized: "Stopped: \(AssistantDigest.clean(reason, limit: 80))")))
        case nil: break
        }
    }

    /// The link in plain words for the model: the address, what stands out, the certificate, the registry, the way.
    static func linkData(_ inspection: LinkInspection, site: SiteReport?, redirects: RedirectTrace?, now: Date = .now) -> String {
        var lines = ["Link: \(AssistantDigest.clean(inspection.link, limit: 200))"]
        if !inspection.site.isEmpty { lines.append("- Site: \(AssistantDigest.clean(inspection.site, limit: 80))") }
        if !inspection.host.isEmpty && inspection.host != inspection.site { lines.append("- Full name: \(AssistantDigest.clean(inspection.host, limit: 120))") }
        if let ascii = inspection.asciiHost { lines.append("- In the address bar it reads: \(AssistantDigest.clean(ascii, limit: 120))") }
        lines.append("- The app's verdict on the address: \(inspection.verdict.name)")
        for finding in inspection.findings { lines.append("- [\(finding.severity.name)] \(AssistantDigest.clean(finding.text, limit: 200))") }
        for inner in inspection.nested {
            lines.append("- Inside it, another link: \(AssistantDigest.clean(inner.link, limit: 120)) (verdict \(inner.verdict.name))")
            for finding in inner.findings { lines.append("  - [\(finding.severity.name)] \(AssistantDigest.clean(finding.text, limit: 200))") }
        }
        lines += site.map { siteText($0, now: now).components(separatedBy: "\n") } ?? ["Certificate and domain registry: not checked yet."]
        lines += redirects.map { redirectText($0).components(separatedBy: "\n") } ?? ["Redirects: not followed yet."]
        return lines.joined(separator: "\n")
    }

    private static func call(_ name: AssistantActions.Name, _ link: String) -> String {
        "{\"tool\": \"\(name.rawValue)\", \"text\": \(AssistantCards.jsonString(link) ?? "\"\"")}"
    }

    private static func risk(_ severity: LinkInspection.Severity) -> CardRisk {
        switch severity {
        case .danger: .critical
        case .caution: .warning
        case .info: .info
        }
    }

    private static func card(_ finding: LinkInspection.Finding) -> CardFinding {
        CardFinding(risk: risk(finding.severity), text: finding.text)
    }

    // MARK: - Check results, in English for the model

    /// A certificate and registry check: what a "check_site" step returns.
    static func siteText(_ report: SiteReport, now: Date = .now) -> String {
        var lines = ["Certificate and domain of \(AssistantDigest.clean(report.host, limit: 80)), checked just now:"]
        if let certificate = report.certificate {
            let summary = certificate.summary
            lines.append("- Certificate from \(AssistantDigest.clean(summary.issuer ?? "unknown issuer", limit: 80)), valid "
                + "\(day(summary.notBefore)) to \(day(summary.notAfter)), issued \(days(from: summary.notBefore, to: now)) days ago; "
                + (certificate.trusted ? "the phone trusts it"
                   : "the phone does NOT trust it (\(AssistantDigest.clean(certificate.trustError ?? "unknown reason", limit: 80)))"))
        } else if let error = report.certificateError {
            lines.append("- No certificate was received: the site gave no usable HTTPS (\(AssistantDigest.clean(error, limit: 100)))")
        }
        switch report.domain {
        case .found(let record):
            let parts = [
                record.registered.map { "registered \(day($0)) (\(days(from: $0, to: now)) days ago)" } ?? "no registration date given",
                record.expires.map { "expires \(day($0))" },
                record.registrar.map { "registrar \(AssistantDigest.clean($0, limit: 60))" },
            ].compactMap { $0 }
            lines.append("- Domain \(record.domain): " + parts.joined(separator: ", "))
        case .notRegistered(let name): lines.append("- The registry has no record of \(name)")
        case .unsupportedZone(let zone): lines.append("- The .\(zone) registry does not publish registration dates")
        case .failed(let reason): lines.append("- The registry could not be reached: \(AssistantDigest.clean(reason, limit: 80))")
        case .skipped: lines.append("- Registry not asked (the address has no domain name of its own)")
        }
        for finding in report.findings(now: now) {
            lines.append("- [\(finding.severity.name)] \(AssistantDigest.clean(finding.text, limit: 200))")
        }
        return lines.joined(separator: "\n")
    }

    /// The redirects as followed: what an "expand_redirects" step returns.
    static func redirectText(_ trace: RedirectTrace) -> String {
        // Said outright: a small model reads "Final address" after a failed first request as a redirect.
        let moves = trace.hops.filter { (300..<400).contains($0.status) }.count
        var lines = [
            trace.hops.isEmpty ? "Redirects: the link could not be requested at all, so no redirect was seen."
                : moves == 0 ? "Redirects: none, the link answers by itself (headers only, the page was not loaded)."
                : "Redirects: \(moves), followed by their headers only:",
        ]
        for (index, hop) in trace.hops.enumerated() {
            lines.append("\(index + 1). \(AssistantDigest.clean(hop.url.absoluteString, limit: 160)) → HTTP \(hop.status)")
        }
        var leftTheWeb = false
        if case .leftTheWeb = trace.stop { leftTheWeb = true }
        if moves > 0 || leftTheWeb { lines.append("Final address: \(AssistantDigest.clean(trace.final.absoluteString, limit: 160))") }
        switch trace.stop {
        case .tooManyHops: lines.append("Stopped after 10 redirects.")
        case .leftTheWeb(let scheme): lines.append("The last redirect leaves the web for a \(scheme): link.")
        case .failed(let reason): lines.append("Stopped: \(AssistantDigest.clean(reason, limit: 100))")
        case nil: break
        }
        return lines.joined(separator: "\n")
    }

    static func day(_ date: Date) -> String {
        String(AssistantDigest.clock(date).prefix { $0 != " " })
    }

    static func days(from: Date, to: Date) -> Int {
        Int((to.timeIntervalSince(from) / 86_400).rounded(.down))
    }
}

extension LinkInspection.Verdict {
    /// The verdict in English, for the model.
    nonisolated var name: String {
        switch self {
        case .noFlags: "no flags"
        case .caution: "caution"
        case .suspicious: "suspicious"
        case .dangerous: "dangerous"
        }
    }
}

extension LinkInspection.Severity {
    /// The level in English, for the model.
    nonisolated var name: String {
        switch self {
        case .info: "info"
        case .caution: "caution"
        case .danger: "danger"
        }
    }
}
