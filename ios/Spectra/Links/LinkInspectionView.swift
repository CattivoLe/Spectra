import Network
import SwiftUI

/// The result of a link check. Used in the app and in the share extension.
struct LinkInspectionView: View {
    let inspection: LinkInspection
    /// Off for the final address of an expanded link: its redirects are the
    /// ones just followed, and offering them again led round in a circle.
    var followsRedirects = true
    @State private var trace: RedirectTrace?
    @State private var tracing = false
    @State private var site: SiteReport?
    @State private var probing = false
    /// Shared with the link's summary card: what either side checked shows on both.
    @Environment(LinkShelf.self) private var shelf

    /// The address findings plus whatever the site check added.
    private var combinedVerdict: LinkInspection.Verdict {
        guard let site else { return inspection.verdict }
        var combined = inspection
        combined.findings += site.findings()
        return combined.verdict
    }

    var body: some View {
        List {
            Section {
                VerdictHeader(verdict: combinedVerdict)
            }
            if !inspection.site.isEmpty {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(inspection.site)
                            .font(.title3.weight(.semibold))
                            .textSelection(.enabled)
                        if inspection.host != inspection.site {
                            Text(inspection.host)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(.vertical, 2)
                } header: {
                    Text("Site")
                }
            }
            if !inspection.findings.isEmpty {
                Section("What stands out") {
                    ForEach(inspection.findings) { finding in
                        FindingRow(finding: finding)
                    }
                }
            }
            ForEach(inspection.nested, id: \.link) { nested in
                Section("Leads on to") {
                    NavigationLink {
                        LinkInspectionView(inspection: nested)
                    } label: {
                        Label {
                            Text(nested.site.isEmpty ? nested.link : nested.site)
                        } icon: {
                            Image(systemName: nested.verdict.symbol)
                                .foregroundStyle(nested.verdict.color)
                        }
                    }
                }
            }
            if inspection.url != nil, !inspection.host.isEmpty {
                siteSection
            }
            if followsRedirects, let url = inspection.url {
                redirectSection(url)
            }
            Section {
                EmptyView()
            } footer: {
                Text("The check reads the address only, on this iPhone, and does not open the site. No warnings is not a guarantee: a new phishing site can have a plain-looking address.")
            }
        }
        .navigationTitle("Link")
        .summaryButton(subject: { .link(inspection.link) })
        .onAppear {
            let kept = shelf.entry(inspection.link)
            if site == nil { site = kept.site }
            if trace == nil, followsRedirects { trace = kept.redirects }
        }
        .navigationBarTitleDisplayMode(.inline)
    }

    /// The registered name the registry should be asked about: none for an
    /// address or for an account on a shared platform, whose age is the
    /// platform's and says nothing about the account.
    private var registryDomain: String? { LinkShelf.registryName(inspection) }

    @ViewBuilder
    private var siteSection: some View {
        Section {
            if let site {
                ForEach(site.findings()) { finding in
                    FindingRow(finding: finding)
                }
                if let certificate = site.certificate {
                    detailRow("Issued to", value: issuedTo(certificate.summary))
                    if let issuer = certificate.summary.issuer {
                        detailRow("Issued by", value: issuer)
                    }
                    detailRow("Valid", value: "\(certificate.summary.notBefore.formatted(date: .abbreviated, time: .omitted)) – \(certificate.summary.notAfter.formatted(date: .abbreviated, time: .omitted))")
                }
                if case .found(let domain) = site.domain {
                    if let registrar = domain.registrar {
                        detailRow("Registrar", value: registrar)
                    }
                    if let expires = domain.expires {
                        detailRow("Registration ends", value: expires.formatted(date: .abbreviated, time: .omitted))
                    }
                }
                Button("Check again", systemImage: "arrow.clockwise") { runSiteCheck() }
                    .disabled(probing)
            } else if probing {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Asking the site and the registry…")
                        .foregroundStyle(.secondary)
                }
            } else {
                Button("Check certificate and domain age", systemImage: "checkmark.seal") { runSiteCheck() }
            }
        } header: {
            Text("Certificate and domain")
        } footer: {
            Text("Checking connects to the site's HTTPS port without loading a page, and asks the zone's own registry over RDAP; the list of registries comes from IANA. The site and the registry see your IP address. A certificate or a domain only days old is the usual sign of a site made for one campaign.")
        }
    }

    private func runSiteCheck() {
        probing = true
        let host = inspection.asciiHost ?? inspection.host
        let domain = registryDomain
        Task {
            let report = await SiteProbe.probe(host: host, domain: domain)
            site = report
            shelf.keep(site: report, for: inspection.link)
            probing = false
        }
    }

    private func issuedTo(_ summary: CertificateSummary) -> String {
        guard let name = summary.displayName else { return "?" }
        let others = summary.names.filter { $0.caseInsensitiveCompare(name) != .orderedSame }.count
        return others > 0 ? String(localized: "\(name) and \(others) more") : name
    }

    private func detailRow(_ title: LocalizedStringKey, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
            Spacer(minLength: 12)
            Text(value)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
        .font(.subheadline)
    }

    @ViewBuilder
    private func redirectSection(_ url: URL) -> some View {
        Section {
            if let trace {
                ForEach(Array(trace.hops.enumerated()), id: \.offset) { _, hop in
                    HStack(alignment: .firstTextBaseline) {
                        Text(hop.url.absoluteString)
                            .font(.caption.monospaced())
                            .lineLimit(2)
                            .truncationMode(.middle)
                        Spacer(minLength: 8)
                        Text(verbatim: "\(hop.status)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                if let stop = trace.stop {
                    Text(stopText(stop))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let final = LinkInspector.inspect(trace.final.absoluteString) {
                    let finalLabel = Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Final address")
                            Text(final.site.isEmpty ? final.link : final.site)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: final.verdict.symbol)
                            .foregroundStyle(final.verdict.color)
                    }
                    if Self.sameAddress(trace.final, url) {
                        // No redirect: the link already is its final address.
                        finalLabel
                    } else {
                        NavigationLink {
                            LinkInspectionView(inspection: final, followsRedirects: false)
                        } label: {
                            finalLabel
                        }
                    }
                }
            } else if tracing {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Following redirects…")
                        .foregroundStyle(.secondary)
                }
            } else {
                Button("Expand redirects", systemImage: "arrow.triangle.turn.up.right.diamond") {
                    tracing = true
                    Task {
                        let found = await RedirectTracer.trace(url)
                        trace = found
                        shelf.keep(redirects: found, for: inspection.link)
                        tracing = false
                    }
                }
            }
        } header: {
            Text("Redirects")
        } footer: {
            Text("Expanding requests the link without loading the page. The sites along the way see your IP address and may learn that the link was opened.")
        }
    }

    /// Equal up to a trailing slash and the case of scheme and host.
    static func sameAddress(_ a: URL, _ b: URL) -> Bool {
        func key(_ url: URL) -> String {
            var text = url.absoluteString
            if let scheme = url.scheme { text = scheme.lowercased() + text.dropFirst(scheme.count) }
            if let host = url.host, let range = text.range(of: host) { text.replaceSubrange(range, with: host.lowercased()) }
            while text.hasSuffix("/") { text.removeLast() }
            return text
        }
        return key(a) == key(b)
    }

    private func stopText(_ stop: RedirectTrace.Stop) -> String {
        switch stop {
        case .tooManyHops: String(localized: "Stopped after 10 redirects.")
        case .failed(let reason): String(localized: "Stopped: \(reason)")
        case .leftTheWeb(let scheme): String(localized: "The last redirect leaves the web for a \(scheme): link.")
        }
    }
}

private struct VerdictHeader: View {
    var verdict: LinkInspection.Verdict

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: verdict.symbol)
                .font(.system(size: 34))
                .foregroundStyle(verdict.color)
            VStack(alignment: .leading, spacing: 3) {
                Text(verdict.title)
                    .font(.headline)
                Text(verdict.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}

private struct FindingRow: View {
    var finding: LinkInspection.Finding

    var body: some View {
        Label {
            Text(finding.text)
        } icon: {
            Image(systemName: symbol)
                .foregroundStyle(color)
        }
    }

    private var symbol: String {
        switch finding.severity {
        case .danger: "xmark.octagon.fill"
        case .caution: "exclamationmark.triangle.fill"
        case .info: finding.kind == .official ? "checkmark.seal.fill" : "info.circle"
        }
    }

    private var color: Color {
        switch finding.severity {
        case .danger: .red
        case .caution: .orange
        case .info: finding.kind == .official ? .green : .secondary
        }
    }
}

extension LinkInspection.Verdict {
    var title: String {
        switch self {
        case .noFlags: String(localized: "No obvious warning signs")
        case .caution: String(localized: "Worth a closer look")
        case .suspicious: String(localized: "Suspicious link")
        case .dangerous: String(localized: "Looks like phishing")
        }
    }

    var subtitle: String {
        switch self {
        case .noFlags: String(localized: "Still make sure this is the site you expect.")
        case .caution: String(localized: "One thing in the address is unusual.")
        case .suspicious: String(localized: "Several things in the address are typical of phishing.")
        case .dangerous: String(localized: "Do not enter passwords, codes or card details there.")
        }
    }

    var symbol: String {
        switch self {
        case .noFlags: "checkmark.shield.fill"
        case .caution: "exclamationmark.triangle.fill"
        case .suspicious: "exclamationmark.shield.fill"
        case .dangerous: "xmark.shield.fill"
        }
    }

    var color: Color {
        switch self {
        case .noFlags: .green
        case .caution: .yellow
        case .suspicious: .orange
        case .dangerous: .red
        }
    }
}
