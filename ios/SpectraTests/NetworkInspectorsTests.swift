import Foundation
import Testing
@testable import Spectra

struct NetworkInspectorsTests {
    private func observation(_ host: String, issuer: String?, trusted: Bool = true, expected: Bool = true, error: String? = nil) -> TLSObservation {
        TLSObservation(host: host, issuer: issuer, trusted: trusted, expected: expected, error: error)
    }

    @Test func expectedIssuersPerSite() {
        let apple = TLSInspector.references[0]
        #expect(TLSInspector.isExpected(issuer: "Apple Inc.", for: apple))
        #expect(!TLSInspector.isExpected(issuer: "DigiCert Inc", for: apple), "Apple signs its own site")
        let any = TLSInspector.references[2]
        #expect(TLSInspector.isExpected(issuer: "Let's Encrypt", for: any))
        #expect(TLSInspector.isExpected(issuer: "Google Trust Services", for: any))
        #expect(!TLSInspector.isExpected(issuer: "Zscaler Root CA", for: any))
        #expect(!TLSInspector.isExpected(issuer: nil, for: any))
    }

    @Test func interceptionIsRecognised() {
        let clean = TLSCheck(observations: [
            observation("www.apple.com", issuer: "Apple Inc."),
            observation("www.google.com", issuer: "Google Trust Services"),
            observation("www.cloudflare.com", issuer: "DigiCert Inc"),
            observation("www.wikipedia.org", issuer: "Let's Encrypt"),
        ])
        #expect(!clean.intercepted)
        #expect(clean.reachable == 4)

        let untrusted = TLSCheck(observations: [observation("www.apple.com", issuer: "Apple Inc.", trusted: false)])
        #expect(untrusted.intercepted)

        let proxy = TLSCheck(observations: [
            observation("www.apple.com", issuer: "Corp Proxy CA", expected: false),
            observation("www.google.com", issuer: "Corp Proxy CA", expected: false),
        ])
        #expect(proxy.intercepted)

        // One issuer for every site, even one with a reputable name, is a proxy.
        let sameIssuer = TLSCheck(observations: [
            observation("www.apple.com", issuer: "DigiCert Inc", expected: false),
            observation("www.google.com", issuer: "DigiCert Inc", expected: false),
            observation("www.cloudflare.com", issuer: "DigiCert Inc"),
        ])
        #expect(sameIssuer.intercepted)

        let unreachable = TLSCheck(observations: [observation("www.apple.com", issuer: nil, trusted: false, error: "timed out")])
        #expect(!unreachable.intercepted)
        #expect(unreachable.reachable == 0)
    }

    @Test func checkHostTCPResultsAreRead() {
        let open = Data(#"{"node1": [{"time": 0.051, "address": "203.0.113.17"}]}"#.utf8)
        let closed = Data(#"{"node1": [{"error": "Connection timed out"}]}"#.utf8)
        let pending = Data(#"{"node1": null}"#.utf8)
        #expect(LinkParse.tcpOpen(open, node: "node1") == true)
        #expect(LinkParse.tcpOpen(closed, node: "node1") == false)
        #expect(LinkParse.tcpOpen(pending, node: "node1") == nil)
        #expect(LinkParse.tcpOpen(open, node: "other") == nil)
    }

    @Test func resolvConfIsParsed() {
        let text = """
        #
        # macOS Notice
        nameserver 192.168.1.1
        nameserver fe80::1%en0
        search lan
        nameserver\t1.1.1.1
        """
        #expect(LinkParse.nameservers(in: text) == ["192.168.1.1", "fe80::1", "1.1.1.1"])
    }

    @Test func dnsPathIsExplained() {
        var report = DNSReport()
        #expect(report.path(publicIP: "203.0.113.17") == .unknown)
        report.resolverIP = "203.0.113.17"
        #expect(report.path(publicIP: "203.0.113.17") == .local)
        report.resolverIP = "172.68.1.1"
        report.configured = ["192.168.1.1"]
        #expect(report.path(publicIP: "203.0.113.17") == .forwarded)
        report.configured = ["1.1.1.1"]
        #expect(report.path(publicIP: "203.0.113.17") == .direct)
        #expect(DNSReport.isPrivate("10.0.0.1"))
        #expect(DNSReport.isPrivate("172.16.5.5"))
        #expect(!DNSReport.isPrivate("172.32.0.1"))
        #expect(DNSReport.isPrivate("fd00::1"))
        #expect(!DNSReport.isPrivate("8.8.8.8"))
    }

    @Test func exposureSurvivesTheHistoryFile() throws {
        var snapshot = LinkSnapshot()
        snapshot.status = .online
        snapshot.external = ExternalAddress(ip: "203.0.113.17")
        snapshot.tls = TLSCheck(observations: [observation("www.apple.com", issuer: "Apple Inc.")])
        snapshot.resolver = DNSReport(configured: ["192.168.1.1"], resolverIP: "172.68.1.1", resolverName: "Cloudflare", resolverCountry: "US", liesAboutMissingNames: false, dohReachable: true, dotReachable: false)
        snapshot.exposure = ExposureReport(ip: "203.0.113.17", checkedAt: Date(timeIntervalSince1970: 1_700_000_000), ports: [ExposedPort(port: 443, open: true), ExposedPort(port: 23, open: false)], note: .none)
        let entry = HistoryEntry(kind: .lan, savedAt: .now, source: "Test", connections: [SavedConnection(viaVpn: false, snapshot: snapshot)])
        let file = HistoryDocument.file([entry], from: "Test")
        let (_, decoded) = try #require(HistoryDocument.decode(Data(file.contents.utf8)))
        let back = try #require(decoded.first?.connections.first?.snapshot)
        #expect(back.tls == snapshot.tls)
        #expect(back.resolver == snapshot.resolver)
        #expect(back.exposure?.openPorts.map(\.port) == [443])

        // Older saved checks have none of this.
        let old = try JSONDecoder().decode(LinkSnapshot.self, from: Data(#"{"checkedAt":0,"speedFailed":false,"regions":[],"abroad":[],"abroadNote":"none","remoteTraces":[],"traceNote":"none"}"#.utf8))
        #expect(old.tls == nil && old.resolver == nil && old.exposure == nil)
    }
}
