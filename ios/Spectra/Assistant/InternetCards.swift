import Foundation

/// Summary cards about the way out: the internet connection (Connection, Speed test, Traceroute screens) and one
/// link (Check a link). Same contract as `AssistantCards`: the findings and the buttons come from these rules, the
/// model only puts them into words. Mirrors InternetCards.kt; checked by `InternetCardsTests`.
nonisolated enum InternetCards {
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
