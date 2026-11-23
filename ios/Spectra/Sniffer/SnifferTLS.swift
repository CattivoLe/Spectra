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
    func makeServerContext(for host: String, protocols: [String] = ["http/1.1"]) throws -> NIOSSLContext {
        let leaf = try NIOSSLCertificate(bytes: leafDER(for: host), format: .der)
        let ca = try NIOSSLCertificate(bytes: certificateDER, format: .der)
        let key = try NIOSSLPrivateKey(bytes: Array(pemString(label: "RSA PRIVATE KEY", der: leafPrivateKeyDER()).utf8), format: .pem)
        var config = TLSConfiguration.makeServerConfiguration(
            certificateChain: [.certificate(leaf), .certificate(ca)],
            privateKey: .privateKey(key)
        )
        config.applicationProtocols = protocols
        return try NIOSSLContext(configuration: config)
    }

    /// A TLS **client** context for the real origin leg of a MITM.
    ///
    /// Verification is **disabled**. With the default trust roots swift-nio-ssl on
    /// Apple platforms asks Security.framework, whatever `certificateVerification`
    /// says, and gets the answer on another thread: the `EmbeddedEventLoop` of
    /// `TLSBridge` is touched off its thread, nothing drains it afterwards, and the
    /// origin's handshake never ends. So the roots are an empty list, not the
    /// default. For a local debugging sniffer on the user's own device not verifying
    /// the origin is an acceptable trade-off (the same as a proxy's "don't verify
    /// upstream" option); the phone still verifies *our* leaf against the installed CA.
    static func makeSystemClientContext(protocols: [String] = ["http/1.1"]) throws -> NIOSSLContext {
        var config = TLSConfiguration.makeClientConfiguration()
        config.certificateVerification = .none
        config.trustRoots = .certificates([])
        config.applicationProtocols = protocols
        return try NIOSSLContext(configuration: config)
    }

    /// A TLS **client** context that trusts exactly `caDER`. Production uses the
    /// system trust store (`.default`) toward the real origin; tests pin the
    /// origin's own CA here.
    static func makeClientContext(trusting caDER: [UInt8], fullVerification: Bool = true,
                                  protocols: [String] = ["http/1.1"]) throws -> NIOSSLContext {
        let ca = try NIOSSLCertificate(bytes: caDER, format: .der)
        var config = TLSConfiguration.makeClientConfiguration()
        config.trustRoots = .certificates([ca])
        config.certificateVerification = fullVerification ? .fullVerification : .noHostnameVerification
        config.applicationProtocols = protocols
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
