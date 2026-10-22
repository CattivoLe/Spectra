import Foundation
import NIOSSL

/// Builds swift-nio-ssl contexts from a `LocalCertificateAuthority`. Kept apart
/// from the CA itself so the DER/crypto port stays free of the NIO dependency.
/// swift-nio-ssl reads the leaf cert + key directly (DER/PEM) — no keychain,
/// and TLS 1.3 — which is why it is the chosen bridge library (see the spike
/// results note).
extension LocalCertificateAuthority {
    /// A TLS **server** context that presents this CA's leaf for `host` to the
    /// phone. The chain is leaf → CA so a phone that trusts the CA accepts it.
    func makeServerContext(for host: String) throws -> NIOSSLContext {
        let leaf = try NIOSSLCertificate(bytes: leafDER(for: host), format: .der)
        let ca = try NIOSSLCertificate(bytes: certificateDER, format: .der)
        let key = try NIOSSLPrivateKey(bytes: Array(pemString(label: "RSA PRIVATE KEY", der: leafPrivateKeyDER()).utf8), format: .pem)
        var config = TLSConfiguration.makeServerConfiguration(
            certificateChain: [.certificate(leaf), .certificate(ca)],
            privateKey: .privateKey(key)
        )
        config.applicationProtocols = ["http/1.1"]
        return try NIOSSLContext(configuration: config)
    }

    /// A TLS **client** context for the real origin leg of a MITM.
    ///
    /// Verification is **disabled**: swift-nio-ssl on Apple platforms does not
    /// pick up the system trust store, so `.fullVerification` stalls/fails the
    /// origin handshake (no trusted roots) — the response never comes back. For
    /// a local debugging sniffer on the user's own device this is an acceptable
    /// trade-off (the same as a proxy's "don't verify upstream" option); the
    /// phone still verifies *our* leaf against the installed CA.
    static func makeSystemClientContext() throws -> NIOSSLContext {
        var config = TLSConfiguration.makeClientConfiguration()
        config.certificateVerification = .none
        config.applicationProtocols = ["http/1.1"]
        return try NIOSSLContext(configuration: config)
    }

    /// A TLS **client** context that trusts exactly `caDER`. Production uses the
    /// system trust store (`.default`) toward the real origin; tests pin the
    /// origin's own CA here.
    static func makeClientContext(trusting caDER: [UInt8], fullVerification: Bool = true) throws -> NIOSSLContext {
        let ca = try NIOSSLCertificate(bytes: caDER, format: .der)
        var config = TLSConfiguration.makeClientConfiguration()
        config.trustRoots = .certificates([ca])
        config.certificateVerification = fullVerification ? .fullVerification : .noHostnameVerification
        config.applicationProtocols = ["http/1.1"]
        return try NIOSSLContext(configuration: config)
    }
}

/// Wraps DER bytes in a PEM block.
func pemString(label: String, der: [UInt8]) -> String {
    let base64 = Data(der).base64EncodedString()
    var lines = ["-----BEGIN \(label)-----"]
    var index = base64.startIndex
    while index < base64.endIndex {
        let next = base64.index(index, offsetBy: 64, limitedBy: base64.endIndex) ?? base64.endIndex
        lines.append(String(base64[index..<next]))
        index = next
    }
    lines.append("-----END \(label)-----")
    return lines.joined(separator: "\n") + "\n"
}
