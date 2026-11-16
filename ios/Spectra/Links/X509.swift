import Foundation

/// The parts of a site's certificate a person can judge: who it was issued
/// to, by whom, and when.
nonisolated struct CertificateSummary: Equatable, Sendable {
    /// The subject's common name, when the certificate has one.
    var subject: String?
    /// DNS names the certificate covers (subjectAltName).
    var names: [String]
    var issuerOrganization: String?
    var issuerCommonName: String?
    var notBefore: Date
    var notAfter: Date

    /// The name shown for the certificate: the common name, else the first DNS name.
    var displayName: String? { subject ?? names.first }

    var issuer: String? { issuerOrganization ?? issuerCommonName }

    /// Whether the certificate is for this host. `*.example.com` covers one
    /// extra label, as browsers read it.
    func covers(host: String) -> Bool {
        let host = host.lowercased()
        let candidates = names.isEmpty ? [subject].compactMap { $0 } : names
        for name in candidates.map({ $0.lowercased() }) {
            if name == host { return true }
            if name.hasPrefix("*."), let dot = host.firstIndex(of: "."), host[host.index(after: dot)...] == name.dropFirst(2) {
                return true
            }
        }
        return false
    }
}

/// Just enough ASN.1 to read a DER certificate: iOS has no public API for
/// a certificate's dates or its issuer's name.
nonisolated enum X509 {
    static func parse(_ der: Data) -> CertificateSummary? {
        let bytes = [UInt8](der)
        guard let certificate = element(in: bytes, at: 0), certificate.tag == 0x30,
              let tbs = element(in: bytes, at: certificate.start), tbs.tag == 0x30 else { return nil }
        var cursor = tbs.start
        let end = tbs.end

        // Optional explicit version [0], then the serial number.
        guard var field = element(in: bytes, at: cursor), field.end <= end else { return nil }
        if field.tag == 0xA0 {
            cursor = field.end
            guard let serial = element(in: bytes, at: cursor) else { return nil }
            field = serial
        }
        cursor = field.end
        // Signature algorithm.
        guard let algorithm = element(in: bytes, at: cursor) else { return nil }
        cursor = algorithm.end
        // Issuer.
        guard let issuer = element(in: bytes, at: cursor), issuer.tag == 0x30 else { return nil }
        let issuerAttributes = name(bytes, issuer)
        cursor = issuer.end
        // Validity.
        guard let validity = element(in: bytes, at: cursor), validity.tag == 0x30,
              let first = element(in: bytes, at: validity.start),
              let second = element(in: bytes, at: first.end),
              let notBefore = time(bytes, first), let notAfter = time(bytes, second) else { return nil }
        cursor = validity.end
        // Subject.
        guard let subject = element(in: bytes, at: cursor), subject.tag == 0x30 else { return nil }
        let subjectAttributes = name(bytes, subject)
        cursor = subject.end
        // Public key, then optional [1], [2], and the extensions [3].
        guard let key = element(in: bytes, at: cursor) else { return nil }
        cursor = key.end
        var names: [String] = []
        while cursor < end, let next = element(in: bytes, at: cursor) {
            if next.tag == 0xA3, let list = element(in: bytes, at: next.start) {
                names = subjectAltNames(bytes, list)
            }
            cursor = next.end
        }

        return CertificateSummary(
            subject: subjectAttributes[[0x55, 0x04, 0x03]],
            names: names,
            issuerOrganization: issuerAttributes[[0x55, 0x04, 0x0A]],
            issuerCommonName: issuerAttributes[[0x55, 0x04, 0x03]],
            notBefore: notBefore,
            notAfter: notAfter
        )
    }

    // MARK: - DER

    struct Element {
        var tag: UInt8
        /// Offset of the first content byte.
        var start: Int
        /// Offset just past the content.
        var end: Int
    }

    static func element(in bytes: [UInt8], at offset: Int) -> Element? {
        guard offset + 2 <= bytes.count else { return nil }
        let tag = bytes[offset]
        var index = offset + 1
        var length = Int(bytes[index])
        index += 1
        if length & 0x80 != 0 {
            let count = length & 0x7F
            guard count >= 1, count <= 4, index + count <= bytes.count else { return nil }
            length = 0
            for _ in 0..<count {
                length = length << 8 | Int(bytes[index])
                index += 1
            }
        }
        guard index + length <= bytes.count else { return nil }
        return Element(tag: tag, start: index, end: index + length)
    }

    /// A Name: SEQUENCE of SET of SEQUENCE { OID, value }. Keyed by OID bytes.
    private static func name(_ bytes: [UInt8], _ sequence: Element) -> [[UInt8]: String] {
        var result: [[UInt8]: String] = [:]
        var cursor = sequence.start
        while cursor < sequence.end, let set = element(in: bytes, at: cursor) {
            var inner = set.start
            while inner < set.end, let pair = element(in: bytes, at: inner) {
                if let oid = element(in: bytes, at: pair.start), oid.tag == 0x06,
                   let value = element(in: bytes, at: oid.end),
                   let text = string(bytes, value) {
                    result[Array(bytes[oid.start..<oid.end])] = text
                }
                inner = pair.end
            }
            cursor = set.end
        }
        return result
    }

    /// dNSName entries of a subjectAltName extension, found by its OID 2.5.29.17.
    private static func subjectAltNames(_ bytes: [UInt8], _ extensions: Element) -> [String] {
        var cursor = extensions.start
        while cursor < extensions.end, let ext = element(in: bytes, at: cursor) {
            if let oid = element(in: bytes, at: ext.start), oid.tag == 0x06,
               Array(bytes[oid.start..<oid.end]) == [0x55, 0x1D, 0x11] {
                var after = oid.end
                if let critical = element(in: bytes, at: after), critical.tag == 0x01 { after = critical.end }
                if let octets = element(in: bytes, at: after), octets.tag == 0x04,
                   let list = element(in: bytes, at: octets.start), list.tag == 0x30 {
                    var names: [String] = []
                    var item = list.start
                    while item < list.end, let general = element(in: bytes, at: item) {
                        if general.tag == 0x82, let text = String(bytes: bytes[general.start..<general.end], encoding: .ascii) {
                            names.append(text)
                        }
                        item = general.end
                    }
                    return names
                }
            }
            cursor = ext.end
        }
        return []
    }

    private static func string(_ bytes: [UInt8], _ value: Element) -> String? {
        let slice = bytes[value.start..<value.end]
        switch value.tag {
        case 0x0C, 0x13, 0x16, 0x14, 0x1A: return String(bytes: slice, encoding: .utf8) ?? String(bytes: slice, encoding: .isoLatin1)
        case 0x1E: return String(bytes: slice, encoding: .utf16BigEndian)
        default: return nil
        }
    }

    /// UTCTime "YYMMDDHHMMSSZ" or GeneralizedTime "YYYYMMDDHHMMSSZ".
    static func time(_ bytes: [UInt8], _ value: Element) -> Date? {
        guard let text = String(bytes: bytes[value.start..<value.end], encoding: .ascii) else { return nil }
        var digits = text
        if digits.hasSuffix("Z") { digits.removeLast() }
        let year: Int
        var rest: Substring
        switch value.tag {
        case 0x17:
            guard digits.count >= 10, let two = Int(digits.prefix(2)) else { return nil }
            year = two < 50 ? 2000 + two : 1900 + two
            rest = digits.dropFirst(2)
        case 0x18:
            guard digits.count >= 12, let four = Int(digits.prefix(4)) else { return nil }
            year = four
            rest = digits.dropFirst(4)
        default:
            return nil
        }
        func take() -> Int? {
            guard rest.count >= 2, let number = Int(rest.prefix(2)) else { return nil }
            rest = rest.dropFirst(2)
            return number
        }
        guard let month = take(), let day = take(), let hour = take(), let minute = take() else { return nil }
        let second = take() ?? 0
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.timeZone = TimeZone(identifier: "UTC")
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        components.second = second
        return components.date
    }
}
