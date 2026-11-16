import Foundation
import Testing
@testable import Spectra

struct SiteProbeTests {
    /// example.com's leaf certificate, captured in November 2026.
    private let exampleCertificate = "MIID5jCCA42gAwIBAgIQAe7mqrtSHV4U/DFf2ZhWkDAKBggqhkjOPQQDAjBRMQswCQYDVQQGEwJVUzEYMBYGA1UECgwPU1NMIENvcnBvcmF0aW9uMSgwJgYDVQQDDB9DbG91ZGZsYXJlIFRMUyBJc3N1aW5nIEVDQyBDQSAzMB4XDTI2MDkyNjIyNDkxMVoXDTI2MTIyNTIyNTYzNVowFjEUMBIGA1UEAwwLZXhhbXBsZS5jb20wWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARqadExWzxTJ4S3LSzKIzAAeyF09q0Qt0F0XmLiZiaJSD0SQPhVK36pUKlM55TUstJnrU7f+7elE0kZESKzzcbto4ICgDCCAnwwDAYDVR0TAQH/BAIwADAfBgNVHSMEGDAWgBSDA/3n9vVKTRVB9O0iFtMyCj7KZjBsBggrBgEFBQcBAQRgMF4wOQYIKwYBBQUHMAKGLWh0dHA6Ly9pLmNmLWkuc3NsLmNvbS9DbG91ZGZsYXJlLVRMUy1JLUUzLmNlcjAhBggrBgEFBQcwAYYVaHR0cDovL28uY2YtaS5zc2wuY29tMCUGA1UdEQQeMByCC2V4YW1wbGUuY29tgg0qLmV4YW1wbGUuY29tMCMGA1UdIAQcMBowCAYGZ4EMAQIBMA4GDCsGAQQBgqkwAQMBATATBgNVHSUEDDAKBggrBgEFBQcDATBTBgNVHR8ETDBKMEigRqBEhkJodHRwOi8vYy5jZi1pLnNzbC5jb20vYWU4MDFlZDFjNTViYjU3OWQ3OTIwOGIwZDc3MmFjZmI4Y2MzYTIwOC5jcmwwDgYDVR0PAQH/BAQDAgeAMA8GCSsGAQQBgtpLLAQCBQAwggEEBgorBgEEAdZ5AgQCBIH1BIHyAPAAdgCUTkOH+uzB74HzGSQmqBhlAcfTXzgCAT9yZ31VNy4Z2AAAAaDf8a1iAAAEAwBHMEUCIQCuCUahRazwOcqj3A77y8eXdQBpLlGCjv9D/xvXd3X7hAIgbc0KMS+MsANY5WLGpHgTk3Uxk0g1VWTQB78vYRMga1EAdgDLOPcViXyEoURfW8Hd+8lu8ppZzUcKaQWFsMsUwxRY5wAAAaDf8a1+AAAEAwBHMEUCIB2YN/lZP6eSBMtV0BTrGD7KSyKVu/i68Zza97li96FcAiEA1mtvTnM8zbNKj6ArUpWmgTRURDDgOC4R1C2W5cSK6fIwCgYIKoZIzj0EAwIDRwAwRAIgNGlXxWLTTqn5+XQOkP06fTgaEbjDMAHUILmIapx8i5sCIAeTI/xpafGZNB9HwNfEeU05yh+aSv95pD8SNtIAWNr0"

    private func summary() throws -> CertificateSummary {
        let der = try #require(Data(base64Encoded: exampleCertificate))
        return try #require(X509.parse(der))
    }

    private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.timeZone = TimeZone(identifier: "UTC")
        components.year = year
        components.month = month
        components.day = day
        return components.date!
    }

    @Test func derCertificateIsRead() throws {
        let cert = try summary()
        #expect(cert.subject == "example.com")
        #expect(cert.names == ["example.com", "*.example.com"])
        #expect(cert.issuerOrganization == "SSL Corporation")
        #expect(cert.issuerCommonName == "Cloudflare TLS Issuing ECC CA 3")
        let calendar = Calendar(identifier: .gregorian)
        var utc = calendar
        utc.timeZone = TimeZone(identifier: "UTC")!
        #expect(utc.dateComponents([.year, .month, .day, .hour], from: cert.notBefore) == DateComponents(year: 2026, month: 9, day: 26, hour: 22))
        #expect(utc.dateComponents([.year, .month, .day], from: cert.notAfter) == DateComponents(year: 2026, month: 12, day: 25))
    }

    @Test func wildcardCoversOneLabel() throws {
        let cert = try summary()
        #expect(cert.covers(host: "example.com"))
        #expect(cert.covers(host: "WWW.example.com"))
        #expect(!cert.covers(host: "a.b.example.com"))
        #expect(!cert.covers(host: "example.org"))
    }

    @Test func garbageIsNotACertificate() {
        #expect(X509.parse(Data([0x30, 0x03, 0x02, 0x01, 0x01])) == nil)
        #expect(X509.parse(Data()) == nil)
    }

    @Test func rdapAnswerIsParsed() throws {
        let json = """
        {"ldhName":"EXAMPLE.COM","events":[
          {"eventAction":"registration","eventDate":"1995-08-14T04:00:00Z"},
          {"eventAction":"expiration","eventDate":"2027-08-13T04:00:00.000Z"},
          {"eventAction":"last changed","eventDate":"2026-08-14T08:01:43Z"}],
         "entities":[{"roles":["registrar"],"vcardArray":["vcard",[["version",{},"text","4.0"],["fn",{},"text","Example Registrar LLC"]]]}]}
        """
        let report = try #require(SiteProbe.parseRDAP(Data(json.utf8), domain: "example.com"))
        #expect(report.domain == "example.com")
        #expect(report.registered == date(1995, 8, 14).addingTimeInterval(4 * 3600))
        #expect(report.expires != nil)
        #expect(report.registrar == "Example Registrar LLC")
        #expect(SiteProbe.parseRDAP(Data("[]".utf8), domain: "x") == nil)
    }

    @Test func freshCertificateAndNewDomainAreWarnings() throws {
        let now = date(2026, 11, 7)
        var cert = try summary()
        cert.notBefore = now.addingTimeInterval(-3 * 86_400)
        cert.notAfter = now.addingTimeInterval(80 * 86_400)
        let report = SiteReport(
            host: "example.com",
            certificate: CertificateReport(summary: cert, trusted: true, trustError: nil),
            certificateError: nil,
            domain: .found(DomainReport(domain: "example.com", registered: now.addingTimeInterval(-5 * 86_400), expires: nil, registrar: nil))
        )
        let kinds = report.findings(now: now).map(\.kind)
        #expect(kinds == [.domainNew, .certificateNew])
        var inspection = try #require(LinkInspector.inspect("https://example.com/"))
        inspection.findings += report.findings(now: now)
        #expect(inspection.verdict == .dangerous)
    }

    @Test func oldTrustedSiteIsOnlyInformation() throws {
        let now = date(2026, 11, 7)
        let report = SiteReport(
            host: "www.example.com",
            certificate: CertificateReport(summary: try summary(), trusted: true, trustError: nil),
            certificateError: nil,
            domain: .found(DomainReport(domain: "example.com", registered: date(1995, 8, 14), expires: nil, registrar: "IANA"))
        )
        let findings = report.findings(now: now)
        #expect(findings.allSatisfy { $0.severity == .info })
        #expect(findings.map(\.kind) == [.certificateInfo, .domainInfo])
        #expect(findings.last?.text.contains("31") == true)
    }

    @Test func untrustedMismatchedAndMissingAreFlagged() throws {
        let now = date(2026, 11, 7)
        let untrusted = SiteReport(
            host: "evil.test",
            certificate: CertificateReport(summary: try summary(), trusted: false, trustError: "self signed"),
            certificateError: nil,
            domain: .notRegistered("evil.test")
        )
        let kinds = untrusted.findings(now: now).map(\.kind)
        #expect(kinds.first == .certificateUntrusted)
        #expect(kinds.contains(.domainMissing))

        let mismatched = SiteReport(
            host: "other.org",
            certificate: CertificateReport(summary: try summary(), trusted: true, trustError: nil),
            certificateError: nil,
            domain: .unsupportedZone("org")
        )
        #expect(mismatched.findings(now: now).map(\.kind) == [.certificateMismatch, .certificateInfo, .domainUnknown])

        let noTLS = SiteReport(host: "plain.test", certificate: nil, certificateError: "refused", domain: .skipped)
        #expect(noTLS.findings(now: now).map(\.kind) == [.certificateUnavailable])
    }
}

struct RDAPBootstrapTests {
    @Test func bundledSnapshotKnowsTheBigZones() {
        let bootstrap = RDAPBootstrap.bundled
        #expect(bootstrap.server(for: "example.com")?.absoluteString == "https://rdap.verisign.com/com/v1/")
        #expect(bootstrap.server(for: "www.example.co.uk")?.host == bootstrap.servers["uk"]?.host)
        #expect(bootstrap.server(for: "EXAMPLE.ORG") != nil)
        #expect(bootstrap.servers.count > 1000)
        #expect(bootstrap.publication == RDAPServers.publication)
    }

    @Test func ianaFileIsParsedAndPlainHTTPIsDropped() throws {
        let json = """
        {"version":"1.0","publication":"2026-10-28T22:00:04Z","services":[
          [["kg"],["http://rdap.cctld.kg/"]],
          [["com","net"],["https://rdap.verisign.com/com/v1/"]],
          [["xn--p1ai"],["http://rdap.example/","https://rdap.example/rf"]]]}
        """
        let bootstrap = try #require(RDAPBootstrap.parse(Data(json.utf8)))
        #expect(bootstrap.server(for: "example.kg") == nil, "no https server, so no lookup")
        #expect(bootstrap.server(for: "example.net")?.absoluteString == "https://rdap.verisign.com/com/v1/")
        #expect(bootstrap.server(for: "пример.xn--p1ai")?.absoluteString == "https://rdap.example/rf/")
        #expect(bootstrap.publication == "2026-10-28T22:00:04Z")
        #expect(RDAPBootstrap.parse(Data("{}".utf8)) == nil)
    }

    @Test func registryURLIsBuiltUnderTheBase() throws {
        let base = try #require(RDAPBootstrap.bundled.server(for: "example.com"))
        let url = try #require(URL(string: "domain/example.com", relativeTo: base)?.absoluteURL)
        #expect(url.absoluteString == "https://rdap.verisign.com/com/v1/domain/example.com")
    }
}
