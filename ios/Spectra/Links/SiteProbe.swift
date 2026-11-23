import CryptoKit
import Foundation
import Network
import Security

/// What the site's TLS certificate says, and whether iOS trusts it.
nonisolated struct CertificateReport: Equatable, Sendable {
    var summary: CertificateSummary
    var trusted: Bool
    var trustError: String?
    /// The chain above the site's own certificate, intermediate first, by name.
    var chain: [String] = []
    /// SHA-256 of the site's certificate, as hex pairs.
    var fingerprint: String? = nil
}

/// What the domain registry says about the registered name.
nonisolated struct DomainReport: Equatable, Sendable {
    var domain: String
    var registered: Date?
    var expires: Date?
    var registrar: String?
}

/// The on-demand look at a site: its certificate and its registration.
nonisolated struct SiteReport: Equatable, Sendable {
    enum DomainOutcome: Equatable, Sendable {
        case skipped
        case found(DomainReport)
        /// The registry of this zone publishes nothing over RDAP.
        case unsupportedZone(String)
        case notRegistered(String)
        case failed(String)
    }

    var host: String
    var certificate: CertificateReport?
    /// Why there is no certificate: no HTTPS, or the connection failed.
    var certificateError: String?
    var domain: DomainOutcome

    static let freshCertificateDays = 14
    static let newDomainDays = 30
    static let youngDomainDays = 180

    /// What a person should take from it, worst first, in the inspector's own
    /// finding shape so it joins the verdict.
    func findings(now: Date = .now) -> [LinkInspection.Finding] {
        var findings: [LinkInspection.Finding] = []
        let calendar = Calendar(identifier: .gregorian)

        if let report = certificate {
            let summary = report.summary
            if !report.trusted {
                findings.append(.init(kind: .certificateUntrusted, severity: .danger,
                                      text: String(localized: "iOS does not trust this site's certificate: \(report.trustError ?? String(localized: "unknown reason")).")))
            } else if !summary.covers(host: host) {
                findings.append(.init(kind: .certificateMismatch, severity: .danger,
                                      text: String(localized: "The certificate was issued to \(summary.displayName ?? "?"), not to this site.")))
            }
            if summary.notAfter < now {
                findings.append(.init(kind: .certificateExpired, severity: .danger,
                                      text: String(localized: "The certificate expired on \(summary.notAfter.formatted(date: .abbreviated, time: .omitted)).")))
            }
            let age = calendar.dateComponents([.day], from: summary.notBefore, to: now).day ?? 0
            if age >= 0, age < Self.freshCertificateDays {
                findings.append(.init(kind: .certificateNew, severity: .caution,
                                      text: age == 0
                                      ? String(localized: "The certificate was issued today. Phishing sites are usually only days old.")
                                      : String(localized: "The certificate was issued \(age) days ago. Phishing sites are usually only days old.")))
            } else if report.trusted {
                findings.append(.init(kind: .certificateInfo, severity: .info,
                                      text: String(localized: "Certificate from \(summary.issuer ?? "?"), issued \(summary.notBefore.formatted(date: .abbreviated, time: .omitted)).")))
            }
        } else if let certificateError {
            findings.append(.init(kind: .certificateUnavailable, severity: .caution,
                                  text: String(localized: "No usable HTTPS on this site: \(certificateError).")))
        }

        switch domain {
        case .found(let report):
            if let registered = report.registered {
                let days = calendar.dateComponents([.day], from: registered, to: now).day ?? 0
                let when = registered.formatted(date: .abbreviated, time: .omitted)
                if days < Self.newDomainDays {
                    findings.append(.init(kind: .domainNew, severity: .danger,
                                          text: days <= 0
                                          ? String(localized: "The domain was registered today.")
                                          : String(localized: "The domain was registered \(days) days ago, on \(when).")))
                } else if days < Self.youngDomainDays {
                    findings.append(.init(kind: .domainYoung, severity: .caution,
                                          text: String(localized: "The domain is only \(days) days old, registered on \(when).")))
                } else {
                    let years = calendar.dateComponents([.year], from: registered, to: now).year ?? 0
                    findings.append(.init(kind: .domainInfo, severity: .info,
                                          text: years >= 1
                                          ? String(localized: "The domain has existed since \(when), \(years) years.")
                                          : String(localized: "The domain was registered on \(when).")))
                }
            } else {
                findings.append(.init(kind: .domainInfo, severity: .info,
                                      text: String(localized: "The registry lists \(report.domain) but gives no registration date.")))
            }
        case .notRegistered(let name):
            findings.append(.init(kind: .domainMissing, severity: .caution,
                                  text: String(localized: "The registry has no record of \(name). The name may be free, or the link may be a decoy.")))
        case .unsupportedZone(let zone):
            findings.append(.init(kind: .domainUnknown, severity: .info,
                                  text: String(localized: "The .\(zone) registry does not publish registration dates.")))
        case .failed(let reason):
            findings.append(.init(kind: .domainUnknown, severity: .info,
                                  text: String(localized: "The registry could not be reached: \(reason).")))
        case .skipped:
            break
        }

        return findings.sorted { $0.severity > $1.severity }
    }
}

/// Asks the site for its certificate and the registry for the domain's
/// dates. Both are on demand: the site and the registry see the request.
nonisolated enum SiteProbe {
    /// `domain` is the registered name to look up; nil skips the registry,
    /// as for an IP address or an account on a shared platform.
    static func probe(host: String, domain: String?) async -> SiteReport {
        async let certificate = certificate(host: host)
        async let registry = lookup(domain)
        let (certificateResult, domainOutcome) = await (certificate, registry)
        var report = SiteReport(host: host, certificate: nil, certificateError: nil, domain: domainOutcome)
        switch certificateResult {
        case .success(let found): report.certificate = found
        case .failure(let error): report.certificateError = error.localizedDescription
        }
        return report
    }

    private static func lookup(_ domain: String?) async -> SiteReport.DomainOutcome {
        guard let domain else { return .skipped }
        return await registration(domain)
    }

    // MARK: Certificate

    private final class HandshakeBox: @unchecked Sendable {
        private let lock = NSLock()
        private var report: CertificateReport?
        func store(_ value: CertificateReport) { lock.lock(); report = value; lock.unlock() }
        func take() -> CertificateReport? { lock.lock(); defer { lock.unlock() }; return report }
    }

    /// Connects to port 443, reads the certificate chain and lets the
    /// handshake finish whatever iOS thinks of it, so an untrusted site is
    /// reported rather than hidden behind a connection error. Nothing is sent.
    static func certificate(host: String, timeout: TimeInterval = 8, interfaceType: NWInterface.InterfaceType? = nil) async -> Result<CertificateReport, Error> {
        let box = HandshakeBox()
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        if IPv4Address(host) == nil, IPv6Address(host) == nil {
            sec_protocol_options_set_tls_server_name(options, host)
        }
        let verifyQueue = DispatchQueue(label: "Spectra.SiteProbe.verify")
        sec_protocol_options_set_verify_block(options, { _, trustRef, complete in
            let trust = sec_trust_copy_ref(trustRef).takeRetainedValue()
            var error: CFError?
            let trusted = SecTrustEvaluateWithError(trust, &error)
            if let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first {
                let der = SecCertificateCopyData(leaf) as Data
                if let summary = X509.parse(der) {
                    let above = chain.dropFirst().compactMap { SecCertificateCopySubjectSummary($0) as String? }
                    let digest = SHA256.hash(data: der).map { String(format: "%02X", $0) }.joined(separator: ":")
                    box.store(CertificateReport(summary: summary, trusted: trusted,
                                                trustError: trusted ? nil : (error.map { CFErrorCopyDescription($0) as String }),
                                                chain: above, fingerprint: digest))
                }
            }
            complete(true)
        }, verifyQueue)
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = Int(timeout)
        let parameters = NWParameters(tls: tls, tcp: tcp)
        if let interfaceType { parameters.requiredInterfaceType = interfaceType }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: .https, using: parameters)

        return await withCheckedContinuation { (continuation: CheckedContinuation<Result<CertificateReport, Error>, Never>) in
            let done = Resumer()
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    connection.cancel()
                    done.resume {
                        if let report = box.take() {
                            continuation.resume(returning: .success(report))
                        } else {
                            continuation.resume(returning: .failure(ProbeError.noCertificate))
                        }
                    }
                case .failed(let error):
                    connection.cancel()
                    done.resume {
                        if let report = box.take() {
                            continuation.resume(returning: .success(report))
                        } else {
                            continuation.resume(returning: .failure(error))
                        }
                    }
                case .cancelled:
                    done.resume { continuation.resume(returning: .failure(ProbeError.noCertificate)) }
                default:
                    break
                }
            }
            connection.start(queue: DispatchQueue(label: "Spectra.SiteProbe.tls"))
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout + 2) {
                connection.cancel()
                done.resume { continuation.resume(returning: .failure(ProbeError.timeout)) }
            }
        }
    }

    enum ProbeError: LocalizedError {
        case noCertificate
        case timeout

        var errorDescription: String? {
            switch self {
            case .noCertificate: String(localized: "the site presented no certificate")
            case .timeout: String(localized: "the site did not answer in time")
            }
        }
    }

    /// Makes sure a continuation is resumed exactly once.
    private final class Resumer: @unchecked Sendable {
        private let lock = NSLock()
        private var resumed = false
        func resume(_ body: () -> Void) {
            lock.lock()
            let first = !resumed
            resumed = true
            lock.unlock()
            if first { body() }
        }
    }

    // MARK: Registry

    /// RDAP at the zone's own registry, found through IANA's bootstrap list.
    /// No third party sees which domain is being checked.
    static func registration(_ domain: String, session: URLSession = SiteProbe.session) async -> SiteReport.DomainOutcome {
        let zone = domain.split(separator: ".").last.map(String.init) ?? domain
        let bootstrap = await RDAPBootstrapCache.current(session: session)
        guard let base = bootstrap.server(for: domain),
              let url = URL(string: "domain/\(domain)", relativeTo: base)?.absoluteURL else {
            return .unsupportedZone(zone)
        }
        var request = URLRequest(url: url)
        request.setValue("application/rdap+json", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return .failed(String(localized: "no answer")) }
            switch http.statusCode {
            case 200:
                return parseRDAP(data, domain: domain).map { .found($0) } ?? .failed(String(localized: "unreadable answer"))
            case 404:
                return .notRegistered(domain)
            default:
                return .failed("HTTP \(http.statusCode)")
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.httpAdditionalHeaders = ["User-Agent": "Spectra"]
        return URLSession(configuration: configuration)
    }()

    /// Pulls the registration and expiry events and the registrar's name out
    /// of an RDAP domain object.
    static func parseRDAP(_ data: Data, domain: String) -> DomainReport? {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        var report = DomainReport(domain: (object["ldhName"] as? String)?.lowercased() ?? domain)
        for event in object["events"] as? [[String: Any]] ?? [] {
            guard let action = (event["eventAction"] as? String)?.lowercased(),
                  let date = (event["eventDate"] as? String).flatMap(parseDate) else { continue }
            switch action {
            case "registration": report.registered = date
            case "expiration": report.expires = date
            default: break
            }
        }
        for entity in object["entities"] as? [[String: Any]] ?? [] {
            let roles = (entity["roles"] as? [String] ?? []).map { $0.lowercased() }
            guard roles.contains("registrar") else { continue }
            if let vcard = entity["vcardArray"] as? [Any], vcard.count > 1, let items = vcard[1] as? [[Any]] {
                for item in items where (item.first as? String) == "fn" && item.count > 3 {
                    if let name = item[3] as? String, !name.isEmpty {
                        report.registrar = name
                    }
                }
            }
            if report.registrar != nil { break }
        }
        return report
    }

    private static func parseDate(_ text: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return fractional.date(from: text) ?? plain.date(from: text)
    }
}
