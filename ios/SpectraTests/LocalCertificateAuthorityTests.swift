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
        let ca = try LocalCertificateAuthority.open(dir, guard: PlainKeyGuard())

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
        let ca = try LocalCertificateAuthority.open(dir, guard: PlainKeyGuard())
        let caCert = try #require(ca.certificate())
        let leaf = try #require(ca.leafCertificate(for: "1.2.3.4"))
        #expect(trusts(leaf: leaf, ca: caCert, host: "1.2.3.4"))
    }

    @Test func reloadIsStable() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let first = try LocalCertificateAuthority.open(dir, guard: PlainKeyGuard())
        let firstDER = first.certificateDER
        let firstPrint = first.fingerprint()

        LocalCertificateAuthority.forget(dir)
        let again = try LocalCertificateAuthority.open(dir, guard: PlainKeyGuard())

        #expect(again.certificateDER == firstDER)
        #expect(again.fingerprint() == firstPrint)

        // A leaf minted after reload still chains to the same CA.
        let caCert = try #require(again.certificate())
        let leaf = try #require(again.leafCertificate(for: "example.com"))
        #expect(trusts(leaf: leaf, ca: caCert, host: "example.com"))
    }

    // MARK: Keys on disk

    /// Stands in for the keychain: each guard opens only what it sealed itself.
    private struct TestGuard: CAKeyGuard {
        let id: UInt8
        func seal(_ plain: Data) throws -> Data { Data([id]) + Data(plain.map { $0 ^ 0x5A }) }
        func open(_ sealed: Data) throws -> Data {
            guard sealed.first == id else { throw CocoaError(.coderInvalidValue) }
            return Data(sealed.dropFirst().map { $0 ^ 0x5A })
        }
    }

    /// A keychain that is still locked after a reboot.
    private struct LockedGuard: CAKeyGuard {
        func seal(_ plain: Data) throws -> Data { throw CAKeyGuardError.unavailable(errSecInteractionNotAllowed) }
        func open(_ sealed: Data) throws -> Data { throw CAKeyGuardError.unavailable(errSecInteractionNotAllowed) }
    }

    private func names(_ dir: URL) -> Set<String> {
        Set((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? [])
    }

    @Test func keysAreSealedAndKeptOutOfBackups() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let ca = try LocalCertificateAuthority.open(dir, guard: TestGuard(id: 1))
        #expect(names(dir) == ["ca.crt", "ca.sealed", "leaf.sealed"])
        #expect(try Data(contentsOf: dir.appendingPathComponent("ca.sealed")).first == 1)
        let excluded = try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        #expect(excluded == true)

        LocalCertificateAuthority.forget(dir)
        let again = try LocalCertificateAuthority.open(dir, guard: TestGuard(id: 1))
        #expect(again.certificateDER == ca.certificateDER)
    }

    @Test func clearKeysFromEarlierVersionsAreSealedAndKept() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let original = try LocalCertificateAuthority.open(dir, guard: PlainKeyGuard())
        // Lay the directory out as earlier versions did: PKCS#1 keys in the clear.
        let fm = FileManager.default
        try fm.moveItem(at: dir.appendingPathComponent("ca.sealed"), to: dir.appendingPathComponent("ca.key"))
        try fm.moveItem(at: dir.appendingPathComponent("leaf.sealed"), to: dir.appendingPathComponent("leaf.key"))
        LocalCertificateAuthority.forget(dir)

        let migrated = try LocalCertificateAuthority.open(dir, guard: TestGuard(id: 2))
        #expect(migrated.certificateDER == original.certificateDER) // the certificate the phone trusts survives
        #expect(names(dir) == ["ca.crt", "ca.sealed", "leaf.sealed"])
        #expect(try Data(contentsOf: dir.appendingPathComponent("ca.sealed")).first == 2)

        LocalCertificateAuthority.forget(dir)
        #expect(try LocalCertificateAuthority.open(dir, guard: TestGuard(id: 2)).certificateDER == original.certificateDER)
    }

    @Test func keysSealedElsewhereAreReplaced() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let foreign = try LocalCertificateAuthority.open(dir, guard: TestGuard(id: 3))
        LocalCertificateAuthority.forget(dir)

        // As after a restore on another phone: the files are there, the sealing key is not.
        let fresh = try LocalCertificateAuthority.open(dir, guard: TestGuard(id: 4))
        #expect(fresh.certificateDER != foreign.certificateDER)
        let caCert = try #require(fresh.certificate())
        let leaf = try #require(fresh.leafCertificate(for: "example.com"))
        #expect(trusts(leaf: leaf, ca: caCert, host: "example.com"))
    }

    @Test func aLockedKeychainLeavesTheKeysAlone() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let original = try LocalCertificateAuthority.open(dir, guard: TestGuard(id: 5))
        LocalCertificateAuthority.forget(dir)

        #expect(throws: CAKeyGuardError.self) { try LocalCertificateAuthority.open(dir, guard: LockedGuard()) }
        // Once the phone is unlocked, the same authority comes back.
        LocalCertificateAuthority.forget(dir)
        #expect(try LocalCertificateAuthority.open(dir, guard: TestGuard(id: 5)).certificateDER == original.certificateDER)
    }

    @Test func fingerprintLooksRight() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let ca = try LocalCertificateAuthority.open(dir, guard: PlainKeyGuard())
        let print = ca.fingerprint()
        // 32 bytes → 32 hex pairs joined by ':'.
        #expect(print.split(separator: ":").count == 32)
        #expect(print.allSatisfy { $0 == ":" || $0.isHexDigit })
    }
}
