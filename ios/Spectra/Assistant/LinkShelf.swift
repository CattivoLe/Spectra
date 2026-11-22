import Foundation
import Network
import Observation

/// What was checked about recent links: the certificate and registry answer and the redirects, kept while the app
/// runs. The Check a link screen and the link card's buttons both write here, so either one shows what the other
/// found. Mirrors LinkShelf in InternetCards.kt.
@MainActor
@Observable
final class LinkShelf {
    struct Entry: Equatable {
        var site: SiteReport?
        var redirects: RedirectTrace?
    }

    /// How many links are remembered; the oldest goes first.
    static let keep = 8

    private var entries: [(link: String, entry: Entry)] = []

    func entry(_ link: String) -> Entry {
        entries.first { $0.link == link }?.entry ?? Entry()
    }

    func keep(site report: SiteReport, for link: String) {
        update(link) { $0.site = report }
    }

    func keep(redirects trace: RedirectTrace, for link: String) {
        update(link) { $0.redirects = trace }
    }

    private func update(_ link: String, _ change: (inout Entry) -> Void) {
        var entry = entry(link)
        change(&entry)
        entries.removeAll { $0.link == link }
        entries.append((link, entry))
        if entries.count > Self.keep { entries.removeFirst(entries.count - Self.keep) }
    }

    /// The link card for `link` with whatever either side found about it.
    func facts(_ link: String, now: Date = .now) -> CardFacts {
        let key = LinkInspector.inspect(link)?.link ?? link
        let found = entry(key)
        return InternetCards.link(link, site: found.site, redirects: found.redirects, now: now)
    }

    /// Only a web link with a name has a certificate and a registry record to ask about.
    nonisolated static func isWeb(_ inspection: LinkInspection) -> Bool {
        (inspection.scheme == "https" || inspection.scheme == "http") && !inspection.host.isEmpty
    }

    /// Where its redirects start: the link with its scheme.
    nonisolated static func start(_ inspection: LinkInspection) -> URL? {
        inspection.url ?? URL(string: inspection.link.contains("://") ? inspection.link : "\(inspection.scheme)://\(inspection.link)")
    }

    /// The name to ask the registry about, or `nil` for a bare address or an account on a free host (its platform's
    /// registration says nothing about the account). Same as the Check a link screen.
    nonisolated static func registryName(_ inspection: LinkInspection) -> String? {
        let ascii = inspection.asciiHost ?? inspection.host
        guard IPv4Address(ascii) == nil, IPv6Address(ascii) == nil else { return nil }
        let labels = ascii.lowercased().split(separator: ".").map(String.init)
        guard labels.count >= 2 else { return nil }
        let (site, platform) = LinkInspector.siteOf(labels)
        return platform == nil ? site : nil
    }
}
