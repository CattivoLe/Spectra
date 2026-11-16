import Darwin
import Foundation
import Network

/// Asks a few well-known sites for their certificates and judges whether
/// anything between the phone and the internet re-signs HTTPS. A corporate
/// proxy, an antivirus or an attacker on the Wi-Fi has to present its own
/// certificate, issued by an authority no public site uses.
nonisolated enum TLSInspector {
    struct Reference: Sendable {
        var host: String
        /// Substrings the issuer's name is expected to contain. Empty means
        /// any public authority will do.
        var issuers: [String]
    }

    static let references: [Reference] = [
        Reference(host: "www.apple.com", issuers: ["apple"]),
        Reference(host: "www.google.com", issuers: ["google"]),
        Reference(host: "www.cloudflare.com", issuers: []),
        Reference(host: "www.wikipedia.org", issuers: []),
    ]

    /// Authorities public sites buy certificates from. A proxy's own root
    /// ("Zscaler", "Fortinet", "Kaspersky", the company's name) is not here.
    static let publicAuthorities = [
        "digicert", "let's encrypt", "google trust services", "sectigo", "globalsign", "amazon", "godaddy",
        "entrust", "identrust", "cloudflare", "microsoft", "apple", "buypass", "ssl corporation", "ssl.com",
        "certum", "actalis", "zerossl", "comodo", "geotrust", "thawte", "rapidssl", "starfield", "trustwave",
        "swisssign", "quovadis", "d-trust", "telia", "harica", "izenpe", "certigna", "twca", "hongkong post",
        "netlock", "e-tugra", "cybertrust", "secom", "atos", "deutsche telekom", "t-systems", "unizeto",
    ]

    /// Whether an issuer is one the reference host would use.
    static func isExpected(issuer: String?, for reference: Reference) -> Bool {
        guard let issuer = issuer?.lowercased() else { return false }
        if !reference.issuers.isEmpty {
            return reference.issuers.contains { issuer.contains($0) }
        }
        return publicAuthorities.contains { issuer.contains($0) }
    }

    static func observe(_ reference: Reference, report: Result<CertificateReport, Error>) -> TLSObservation {
        switch report {
        case .success(let found):
            let summary = found.summary
            let issuer = summary.issuer
            var observation = TLSObservation(host: reference.host, issuer: issuer, trusted: found.trusted,
                                             expected: isExpected(issuer: issuer, for: reference), error: nil)
            observation.subject = summary.subject
            observation.names = summary.names
            observation.issuerCommonName = summary.issuerCommonName
            observation.notBefore = summary.notBefore
            observation.notAfter = summary.notAfter
            observation.chain = found.chain
            observation.fingerprint = found.fingerprint
            observation.trustError = found.trustError
            return observation
        case .failure(let error):
            return TLSObservation(host: reference.host, issuer: nil, trusted: false, expected: true, error: error.localizedDescription)
        }
    }

    static func check(interface: NWInterface.InterfaceType?) async -> TLSCheck {
        let observations = await withTaskGroup(of: TLSObservation.self) { group in
            for reference in references {
                group.addTask {
                    observe(reference, report: await SiteProbe.certificate(host: reference.host, timeout: 6, interfaceType: interface))
                }
            }
            var found: [String: TLSObservation] = [:]
            for await observation in group { found[observation.host] = observation }
            return references.compactMap { found[$0.host] }
        }
        return TLSCheck(observations: observations)
    }
}

/// Finds out who answers this network's lookups: the resolvers the phone is
/// told to use, the address the world sees the lookups come from, whether
/// that resolver invents answers, and whether encrypted DNS gets through.
nonisolated enum DNSInspector {
    /// Akamai answers this name with the address of whoever asked, which is
    /// the recursive resolver, not the phone.
    static let whoami = "whoami.akamai.net"

    static func check(interface: NWInterface.InterfaceType?) async -> DNSReport {
        var report = DNSReport()
        report.configured = configuredResolvers()
        async let egress = firstAddress(of: whoami)
        async let lie = liesAboutMissingNames()
        async let doh = dohReachable(interface: interface)
        async let dot = LinkHTTP.connectMillis(host: "1.1.1.1", port: 853, timeout: 3, interfaceType: interface)
        report.resolverIP = await egress
        report.liesAboutMissingNames = await lie
        report.dohReachable = await doh
        report.dotReachable = await dot != nil
        if let ip = report.resolverIP,
           let whois = try? await LinkHTTP.send(host: "ipwho.is", path: "/\(ip)", timeout: 8, maxBytes: 64_000, interfaceType: interface),
           whois.status == 200 {
            let merged = LinkParse.mergeWhois(whois.body, into: ExternalAddress(ip: ip))
            report.resolverName = merged.isp
            report.resolverCountry = merged.countryCode
        }
        return report
    }

    /// The phone's resolver list, from the resolver configuration file.
    static func configuredResolvers() -> [String] {
        guard let text = try? String(contentsOfFile: "/etc/resolv.conf", encoding: .utf8) else { return [] }
        return LinkParse.nameservers(in: text)
    }

    /// Two names that cannot exist. Any address for them is the resolver lying,
    /// as some providers and captive networks do to show their own pages.
    static func liesAboutMissingNames() async -> Bool? {
        let token = String(UInt64.random(in: 0 ... .max), radix: 36)
        let names = ["probe-\(token).example.com", "spectra-\(token)-\(token.prefix(4)).net"]
        var answered = 0
        var failed = 0
        for name in names {
            switch await lookup(name) {
            case .some(let address) where !address.isEmpty: answered += 1
            case .some: break
            case .none: failed += 1
            }
        }
        if failed == names.count { return nil }
        return answered > 0
    }

    static func dohReachable(interface: NWInterface.InterfaceType?) async -> Bool? {
        guard let result = try? await LinkHTTP.send(
            host: "cloudflare-dns.com",
            path: "/dns-query?name=example.com&type=A",
            accept: "application/dns-json",
            timeout: 6,
            maxBytes: 16_000,
            interfaceType: interface
        ) else { return false }
        return result.status == 200
    }

    static func firstAddress(of name: String) async -> String? {
        switch await lookup(name) {
        case .some(let address) where !address.isEmpty: return address
        default: return nil
        }
    }

    /// The first address of a name, IPv4 preferred, IPv6 otherwise (an
    /// IPv6-only network's resolver may answer only AAAA): "" when the name
    /// does not exist, nil when the lookup itself failed or timed out.
    private static func lookup(_ name: String) async -> String? {
        await withTaskGroup(of: String??.self) { group in
            group.addTask {
                await Task.detached { () -> String? in
                    var hints = addrinfo()
                    hints.ai_family = AF_UNSPEC
                    hints.ai_socktype = SOCK_STREAM
                    var info: UnsafeMutablePointer<addrinfo>?
                    let code = getaddrinfo(name, nil, &hints, &info)
                    defer { if let info { freeaddrinfo(info) } }
                    if code == EAI_NONAME || code == EAI_NODATA { return "" }
                    guard code == 0 else { return nil }
                    var v6: String?
                    var cursor = info
                    while let entry = cursor {
                        if let address = entry.pointee.ai_addr {
                            if entry.pointee.ai_family == AF_INET {
                                var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                                var sin = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
                                inet_ntop(AF_INET, &sin, &text, socklen_t(text.count))
                                return String(cString: text)
                            }
                            if entry.pointee.ai_family == AF_INET6, v6 == nil {
                                var text = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
                                var sin6 = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee.sin6_addr }
                                inet_ntop(AF_INET6, &sin6, &text, socklen_t(text.count))
                                v6 = String(cString: text)
                            }
                        }
                        cursor = entry.pointee.ai_next
                    }
                    return v6 ?? ""
                }.value
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(4))
                return .some(nil)
            }
            let first = await group.next() ?? .some(nil)
            group.cancelAll()
            return first ?? nil
        }
    }
}

/// How the DNS findings read to a person.
extension DNSReport {
    enum Path: Equatable {
        /// The lookups leave from this network's own public address: the
        /// router or a local server resolves names itself.
        case local
        /// A configured private resolver (the router) hands lookups to someone else.
        case forwarded
        /// A public resolver, asked directly.
        case direct
        case unknown
    }

    func path(publicIP: String?) -> Path {
        guard let resolverIP else { return .unknown }
        if let publicIP, resolverIP == publicIP { return .local }
        let privateConfigured = configured.contains(where: Self.isPrivate)
        return privateConfigured ? .forwarded : .direct
    }

    static func isPrivate(_ address: String) -> Bool {
        if let v4 = IPv4Address(address) {
            let bytes = [UInt8](v4.rawValue)
            return bytes[0] == 10 || (bytes[0] == 172 && (16...31).contains(bytes[1])) || (bytes[0] == 192 && bytes[1] == 168)
                || (bytes[0] == 169 && bytes[1] == 254) || bytes[0] == 127
        }
        if let v6 = IPv6Address(address) {
            let first = [UInt8](v6.rawValue).first ?? 0
            return first == 0xFD || first == 0xFC || first == 0xFE || address == "::1"
        }
        return false
    }
}
