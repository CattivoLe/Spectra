import Foundation
import CryptoKit
import Security

/// The certificate this app signs for itself, and a per-host leaf signed by the
/// same key. When the phone trusts the CA, a TLS connection opened with one of
/// these leaves can be read. Mirrors the Android `core/LocalCa.kt`.
///
/// Spike note (Phase 1): this is the Swift port of the Android DER/X.509 minter,
/// with the Java `KeyPairGenerator`/`Signature` swapped for Security.framework
/// (`SecKeyCreateRandomKey`, `SecKeyCreateSignature`). It is proven by
/// `LocalCertificateAuthorityTests` via `SecTrust`, which checks the whole
/// chain the way a TLS client would. `secIdentity(for:)` is what the TLS bridge
/// will present as the server identity.
final class LocalCertificateAuthority {
    static let authority = "Spectra"

    /// The CA certificate in DER, as the phone must install and trust.
    let certificateDER: [UInt8]
    private let caPrivate: SecKey
    private let caPublicSPKI: [UInt8]
    private let leafPrivate: SecKey
    private let leafPublicSPKI: [UInt8]

    private var leafCache: [String: [UInt8]] = [:]
    private let lock = NSLock()

    private init(
        certificateDER: [UInt8],
        caPrivate: SecKey,
        caPublicSPKI: [UInt8],
        leafPrivate: SecKey,
        leafPublicSPKI: [UInt8]
    ) {
        self.certificateDER = certificateDER
        self.caPrivate = caPrivate
        self.caPublicSPKI = caPublicSPKI
        self.leafPrivate = leafPrivate
        self.leafPublicSPKI = leafPublicSPKI
    }

    /// `SHA256:..` of the CA certificate, to show in the install screen.
    func fingerprint() -> String {
        SHA256.hash(data: Data(certificateDER))
            .map { String(format: "%02X", $0) }
            .joined(separator: ":")
    }

    /// The CA as a `SecCertificate` (for trust anchors / export).
    func certificate() -> SecCertificate? {
        SecCertificateCreateWithData(nil, Data(certificateDER) as CFData)
    }

    /// A leaf certificate (DER) for `host`, signed by this authority. Cached per host.
    func leafDER(for host: String) -> [UInt8] {
        let name = host.isEmpty ? "spectra" : host
        lock.lock(); defer { lock.unlock() }
        if let cached = leafCache[name] { return cached }
        let now = Date()
        let der = Self.makeCertificate(
            serial: Self.randomSerial(),
            issuer: Self.derName(Self.authority),
            subject: Self.derName(String(name.prefix(64))),
            subjectPublicKeySPKI: leafPublicSPKI,
            notBefore: now.addingTimeInterval(-3600),
            notAfter: now.addingTimeInterval(366 * 24 * 3600),
            extensions: [
                Self.ext("2.5.29.19", critical: false, Der.seq(Der.bool(false))),            // basicConstraints CA:FALSE
                Self.ext("2.5.29.15", critical: false, Der.bitString([0xA0])),                // keyUsage digitalSignature+keyEncipherment
                Self.ext("2.5.29.37", critical: false, Der.seq(Der.oid("1.3.6.1.5.5.7.3.1"))),// extKeyUsage serverAuth
                Self.ext("2.5.29.14", critical: false, Der.octet(Self.ski(leafPublicSPKI))),  // subjectKeyId
                Self.ext("2.5.29.35", critical: false, Der.seq(Der.raw(0x80, Self.ski(caPublicSPKI)))), // authorityKeyId
                Self.ext("2.5.29.17", critical: false, Self.san(name)),                        // subjectAltName
            ],
            issuerKey: caPrivate
        )
        leafCache[name] = der
        return der
    }

    func leafCertificate(for host: String) -> SecCertificate? {
        SecCertificateCreateWithData(nil, Data(leafDER(for: host)) as CFData)
    }

    /// The leaf private key as PKCS#1 DER, for building a TLS private key
    /// (swift-nio-ssl reads it directly — no keychain needed).
    func leafPrivateKeyDER() -> [UInt8] {
        guard let data = SecKeyCopyExternalRepresentation(leafPrivate, nil) as Data? else { return [] }
        return [UInt8](data)
    }

    /// A `SecIdentity` (leaf key + cert) for `host`, for a SecureTransport server
    /// endpoint to present. iOS has no in-memory identity API, so the key and
    /// cert are placed in the keychain and the matching identity is read back.
    /// Spike probe: this is the one remaining concrete iOS unknown for the
    /// dependency-free (SecureTransport) TLS-bridge path.
    func secIdentity(for host: String) throws -> SecIdentity {
        guard let cert = leafCertificate(for: host) else { throw CAError.spki }
        let tag = Data("cattivole.spectra.sniffer.leaf.\(host)".utf8)

        // Place the private key (idempotent; a duplicate is fine).
        let keyAdd: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecValueRef as String: leafPrivate,
            kSecAttrApplicationTag as String: tag,
        ]
        let keyStatus = SecItemAdd(keyAdd as CFDictionary, nil)
        guard keyStatus == errSecSuccess || keyStatus == errSecDuplicateItem else { throw CAError.keychain(keyStatus) }

        // Place the certificate.
        let certAdd: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: cert,
            kSecAttrLabel as String: "SpectraLeaf \(host)",
        ]
        let certStatus = SecItemAdd(certAdd as CFDictionary, nil)
        guard certStatus == errSecSuccess || certStatus == errSecDuplicateItem else { throw CAError.keychain(certStatus) }

        // Read back every identity and match ours by certificate bytes.
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let identities = result as? [SecIdentity] else {
            throw CAError.keychain(status)
        }
        let wantedDER = leafDER(for: host)
        for identity in identities {
            var identCert: SecCertificate?
            guard SecIdentityCopyCertificate(identity, &identCert) == errSecSuccess,
                  let identCert else { continue }
            if [UInt8](SecCertificateCopyData(identCert) as Data) == wantedDER {
                return identity
            }
        }
        throw CAError.keychain(errSecItemNotFound)
    }

    // MARK: Persistence

    private func persist(to dir: URL) throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data(SecKeyCopyExternalRepresentation(caPrivate, nil)! as Data)
            .write(to: dir.appendingPathComponent("ca.key"))
        try Data(certificateDER).write(to: dir.appendingPathComponent("ca.crt"))
        try Data(SecKeyCopyExternalRepresentation(leafPrivate, nil)! as Data)
            .write(to: dir.appendingPathComponent("leaf.key"))
    }

    // MARK: Construction

    private static let openLock = NSLock()
    private static var openByDir: [String: LocalCertificateAuthority] = [:]

    /// Loads the authority kept in `dir`, creating it the first time.
    static func open(_ dir: URL) throws -> LocalCertificateAuthority {
        openLock.lock(); defer { openLock.unlock() }
        let key = dir.standardizedFileURL.path
        if let existing = openByDir[key] { return existing }
        let loaded = FileManager.default.fileExists(atPath: dir.appendingPathComponent("ca.crt").path)
            ? try read(dir) : try create(dir)
        openByDir[key] = loaded
        return loaded
    }

    /// Drops the in-memory copy so the next `open` reads the files again (tests).
    static func forget(_ dir: URL) {
        openLock.lock(); defer { openLock.unlock() }
        openByDir.removeValue(forKey: dir.standardizedFileURL.path)
    }

    private static func create(_ dir: URL) throws -> LocalCertificateAuthority {
        let ca = try generateRSAKeyPair()
        let leaf = try generateRSAKeyPair()
        let caSPKI = try spki(of: ca.publicKey)
        let leafSPKI = try spki(of: leaf.publicKey)
        let now = Date()
        let subject = derName(authority)
        let caDER = makeCertificate(
            serial: randomSerial(),
            issuer: subject,
            subject: subject,
            subjectPublicKeySPKI: caSPKI,
            notBefore: now.addingTimeInterval(-86_400),
            notAfter: now.addingTimeInterval(10 * 366 * 24 * 3600),
            extensions: [
                ext("2.5.29.19", critical: true, Der.seq(Der.bool(true))),     // basicConstraints CA:TRUE
                ext("2.5.29.15", critical: true, Der.bitString([0x06], unused: 1)), // keyCertSign+cRLSign
                ext("2.5.29.14", critical: false, Der.octet(ski(caSPKI))),     // subjectKeyId
            ],
            issuerKey: ca.privateKey
        )
        let authority = LocalCertificateAuthority(
            certificateDER: caDER,
            caPrivate: ca.privateKey, caPublicSPKI: caSPKI,
            leafPrivate: leaf.privateKey, leafPublicSPKI: leafSPKI
        )
        try authority.persist(to: dir)
        return authority
    }

    private static func read(_ dir: URL) throws -> LocalCertificateAuthority {
        let caKeyData = try Data(contentsOf: dir.appendingPathComponent("ca.key"))
        let leafKeyData = try Data(contentsOf: dir.appendingPathComponent("leaf.key"))
        let caDER = [UInt8](try Data(contentsOf: dir.appendingPathComponent("ca.crt")))
        let caPriv = try importRSAPrivate(caKeyData)
        let leafPriv = try importRSAPrivate(leafKeyData)
        return LocalCertificateAuthority(
            certificateDER: caDER,
            caPrivate: caPriv, caPublicSPKI: try spki(of: SecKeyCopyPublicKey(caPriv)!),
            leafPrivate: leafPriv, leafPublicSPKI: try spki(of: SecKeyCopyPublicKey(leafPriv)!)
        )
    }

    // MARK: Crypto primitives (Security.framework)

    enum CAError: Error { case keygen, export, sign, spki, keychain(OSStatus) }

    private static func generateRSAKeyPair() throws -> (privateKey: SecKey, publicKey: SecKey) {
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
        ]
        var error: Unmanaged<CFError>?
        guard let priv = SecKeyCreateRandomKey(attrs as CFDictionary, &error),
              let pub = SecKeyCopyPublicKey(priv) else { throw CAError.keygen }
        return (priv, pub)
    }

    private static func importRSAPrivate(_ data: Data) throws -> SecKey {
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrKeySizeInBits as String: 2048,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(data as CFData, attrs as CFDictionary, &error) else {
            throw CAError.keygen
        }
        return key
    }

    /// Wraps a key's PKCS#1 representation (`SecKeyCopyExternalRepresentation`)
    /// into a DER SubjectPublicKeyInfo, which is what `publicKey.encoded` is on
    /// the Java side.
    private static func spki(of publicKey: SecKey) throws -> [UInt8] {
        guard let pkcs1 = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw CAError.spki
        }
        let algorithm = Der.seq(Der.oid("1.2.840.113549.1.1.1") + Der.nullBytes()) // rsaEncryption
        return Der.seq(algorithm + Der.bitString([UInt8](pkcs1)))
    }

    private static func sign(_ tbs: [UInt8], with key: SecKey) throws -> [UInt8] {
        var error: Unmanaged<CFError>?
        guard let sig = SecKeyCreateSignature(
            key, .rsaSignatureMessagePKCS1v15SHA256, Data(tbs) as CFData, &error
        ) as Data? else { throw CAError.sign }
        return [UInt8](sig)
    }

    // MARK: Certificate assembly (ports tbsCertificate + helpers)

    private static func makeCertificate(
        serial: [UInt8],
        issuer: [UInt8],
        subject: [UInt8],
        subjectPublicKeySPKI: [UInt8],
        notBefore: Date,
        notAfter: Date,
        extensions: [[UInt8]],
        issuerKey: SecKey
    ) -> [UInt8] {
        let tbs = Der.seq(
            Der.explicit(0, Der.integer([0x02]))   // version v3
            + Der.integerBytes(serial)
            + sha256WithRSA()
            + issuer
            + Der.seq(Der.utcTime(notBefore) + Der.utcTime(notAfter))
            + subject
            + subjectPublicKeySPKI
            + Der.explicit(3, Der.seq(extensions.flatMap { $0 }))
        )
        let signature = (try? sign(tbs, with: issuerKey)) ?? []
        return Der.seq(tbs + sha256WithRSA() + Der.bitString(signature))
    }

    private static func sha256WithRSA() -> [UInt8] {
        Der.seq(Der.oid("1.2.840.113549.1.1.11") + Der.nullBytes())
    }

    private static func ski(_ spki: [UInt8]) -> [UInt8] {
        [UInt8](Insecure.SHA1.hash(data: Data(spki)))
    }

    private static func derName(_ cn: String) -> [UInt8] {
        Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3") + Der.printable(cn))))
    }

    private static func ext(_ oid: String, critical: Bool, _ value: [UInt8]) -> [UInt8] {
        Der.seq(Der.oid(oid) + (critical ? Der.bool(true) : []) + Der.octet(value))
    }

    private static func san(_ host: String) -> [UInt8] {
        if let ip = numericAddress(host) {
            return Der.seq(Der.raw(0x87, ip))        // iPAddress
        }
        return Der.seq(Der.raw(0x82, [UInt8](host.utf8))) // dNSName
    }

    /// Returns the packed address bytes when `host` is a numeric IPv4/IPv6 literal.
    private static func numericAddress(_ host: String) -> [UInt8]? {
        var v4 = in_addr()
        if host.contains(".") && inet_pton(AF_INET, host, &v4) == 1 {
            return withUnsafeBytes(of: &v4) { Array($0) }
        }
        var v6 = in6_addr()
        if host.contains(":") && inet_pton(AF_INET6, host, &v6) == 1 {
            return withUnsafeBytes(of: &v6) { Array($0) }
        }
        return nil
    }

    private static func randomSerial() -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        bytes[0] &= 0x7F          // keep positive
        if bytes.allSatisfy({ $0 == 0 }) { bytes[7] = 1 }
        return bytes
    }
}

/// Minimal DER encoder, a direct port of the Android `Der` object. Everything is
/// `[UInt8]`; callers concatenate the pieces.
private enum Der {
    static func seq(_ body: [UInt8]) -> [UInt8] { tl(0x30, body) }
    static func set(_ body: [UInt8]) -> [UInt8] { tl(0x31, body) }
    static func bool(_ value: Bool) -> [UInt8] { tl(0x01, [value ? 0xFF : 0x00]) }
    static func octet(_ body: [UInt8]) -> [UInt8] { tl(0x04, body) }
    static func nullBytes() -> [UInt8] { [0x05, 0x00] }
    static func printable(_ text: String) -> [UInt8] { tl(0x13, Array(text.utf8)) }
    static func bitString(_ body: [UInt8], unused: Int = 0) -> [UInt8] { tl(0x03, [UInt8(unused)] + body) }
    static func explicit(_ tag: Int, _ body: [UInt8]) -> [UInt8] { tl(0xA0 | tag, body) }
    static func raw(_ tag: Int, _ body: [UInt8]) -> [UInt8] { tl(tag, body) }

    /// INTEGER from already-normalized magnitude bytes (big-endian, positive).
    static func integerBytes(_ bytes: [UInt8]) -> [UInt8] {
        var value = bytes
        while value.count > 1 && value[0] == 0x00 && (value[1] & 0x80) == 0 { value.removeFirst() }
        if value.first.map({ $0 & 0x80 != 0 }) == true { value.insert(0x00, at: 0) }
        return tl(0x02, value)
    }

    /// INTEGER from a small literal (already a single minimal byte here).
    static func integer(_ bytes: [UInt8]) -> [UInt8] { tl(0x02, bytes) }

    static func utcTime(_ date: Date) -> [UInt8] {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        return tl(0x17, Array(formatter.string(from: date).utf8))
    }

    static func oid(_ dotted: String) -> [UInt8] {
        let parts = dotted.split(separator: ".").map { Int($0)! }
        var body: [UInt8] = [UInt8(parts[0] * 40 + parts[1])]
        for part in parts.dropFirst(2) { writeBase128(&body, part) }
        return tl(0x06, body)
    }

    private static func writeBase128(_ out: inout [UInt8], _ value: Int) {
        var shift = 28
        while shift > 0 && (value >> shift) == 0 { shift -= 7 }
        while shift > 0 {
            out.append(UInt8(((value >> shift) & 0x7F) | 0x80))
            shift -= 7
        }
        out.append(UInt8(value & 0x7F))
    }

    private static func tl(_ tag: Int, _ body: [UInt8]) -> [UInt8] {
        [UInt8(tag)] + length(body.count) + body
    }

    private static func length(_ size: Int) -> [UInt8] {
        switch size {
        case ..<0x80: return [UInt8(size)]
        case ..<0x100: return [0x81, UInt8(size)]
        case ..<0x10000: return [0x82, UInt8(size >> 8), UInt8(size & 0xFF)]
        default: return [0x83, UInt8(size >> 16), UInt8((size >> 8) & 0xFF), UInt8(size & 0xFF)]
        }
    }
}
