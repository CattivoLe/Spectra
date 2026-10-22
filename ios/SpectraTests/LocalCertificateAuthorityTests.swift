import Foundation
import Testing
import Security
@testable import Spectra

/// Phase 1 spike proof for the TLS-MITM cert machinery. Mirrors the Android
/// `authoritySignsLeavesAndReloads` test, but validates with `SecTrust` + an
/// SSL policy — the same check a TLS client makes — which proves in one shot
/// that the minted leaf is signed by the CA, carries the right SAN, has the
/// serverAuth EKU, and is inside its validity window.
struct LocalCertificateAuthorityTests {
    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("spectra-ca-\(UUID().uuidString)")
        return dir
    }

    /// Does `leaf`, anchored only by `ca`, satisfy an SSL server policy for `host`?
    private func trusts(leaf: SecCertificate, ca: SecCertificate, host: String) -> Bool {
        let policy = SecPolicyCreateSSL(true, host as CFString)
        var trust: SecTrust?
        guard SecTrustCreateWithCertificates([leaf, ca] as CFArray, policy, &trust) == errSecSuccess,
              let trust else { return false }
        SecTrustSetAnchorCertificates(trust, [ca] as CFArray)
        SecTrustSetAnchorCertificatesOnly(trust, true)
        var error: CFError?
        return SecTrustEvaluateWithError(trust, &error)
    }

    @Test func caSignsLeafThatValidatesForHost() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let ca = try LocalCertificateAuthority.open(dir)

        let caCert = try #require(ca.certificate())
        let leaf = try #require(ca.leafCertificate(for: "example.com"))

        #expect(trusts(leaf: leaf, ca: caCert, host: "example.com"))
        // A different host must NOT match the example.com leaf (SAN is enforced).
        #expect(!trusts(leaf: leaf, ca: caCert, host: "evil.test"))
        // The CN carries the authority name.
        let summary = SecCertificateCopySubjectSummary(caCert) as String?
        #expect(summary == LocalCertificateAuthority.authority)
    }

    @Test func leafForIPLiteralValidates() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let ca = try LocalCertificateAuthority.open(dir)
        let caCert = try #require(ca.certificate())
        let leaf = try #require(ca.leafCertificate(for: "1.2.3.4"))
        #expect(trusts(leaf: leaf, ca: caCert, host: "1.2.3.4"))
    }

    @Test func reloadIsStable() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let first = try LocalCertificateAuthority.open(dir)
        let firstDER = first.certificateDER
        let firstPrint = first.fingerprint()

        LocalCertificateAuthority.forget(dir)
        let again = try LocalCertificateAuthority.open(dir)

        #expect(again.certificateDER == firstDER)
        #expect(again.fingerprint() == firstPrint)

        // A leaf minted after reload still chains to the same CA.
        let caCert = try #require(again.certificate())
        let leaf = try #require(again.leafCertificate(for: "example.com"))
        #expect(trusts(leaf: leaf, ca: caCert, host: "example.com"))
    }

    @Test func fingerprintLooksRight() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let ca = try LocalCertificateAuthority.open(dir)
        let print = ca.fingerprint()
        // 32 bytes → 32 hex pairs joined by ':'.
        #expect(print.split(separator: ":").count == 32)
        #expect(print.allSatisfy { $0 == ":" || $0.isHexDigit })
    }
}
