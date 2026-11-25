import Foundation

/// Lifts WPA/WPA2 handshakes out of a capture and writes them as hashcat's 22000
/// hash lines — `WPA*02` for a 4-way handshake and `WPA*01` for a PMKID — the
/// format `hcxpcapngtool` produces and hashcat mode 22000 cracks. Deliberately
/// focused: it reads the EAPOL-Key frames and the beacons/association frames that
/// name the network, and pairs the messages the way the hash needs.
///
/// A line is only emitted when the network name (ESSID) is known, since the hash
/// is useless without it.
nonisolated enum Hc22000 {
    /// One 802.1X EAPOL-Key message, with the fields the hash needs.
    private struct KeyMessage {
        var ap: [UInt8]            // AP (BSSID) MAC, 6 bytes
        var sta: [UInt8]           // client MAC, 6 bytes
        var message: Int           // 1…4
        var replayCounter: UInt64
        var nonce: [UInt8]         // Key Nonce, 32 bytes (ANonce for M1/M3, SNonce for M2/M4)
        var mic: [UInt8]           // Key MIC, 16 bytes
        var eapol: [UInt8]         // the whole 802.1X frame
        var micOffset: Int         // where the MIC sits inside `eapol`
        var pmkid: [UInt8]?        // RSN PMKID from the key data, when present (M1)
    }

    /// The hashcat 22000 lines for every handshake and PMKID found, or an empty
    /// array when the capture holds none that can be cracked.
    static func lines(from file: CaptureFile) -> [String] {
        var ssidByBssid: [String: [UInt8]] = [:]
        var messages: [KeyMessage] = []

        for packet in file.packets {
            guard let frame = dot11Frame(linkType: packet.linkType, packet.data) else { continue }
            let frameControl = frame[0]
            let type = (frameControl >> 2) & 0x3
            let subtype = (frameControl >> 4) & 0xF
            switch type {
            case 0: // management: pick up the SSID a BSSID advertises or is asked for
                if let found = ssid(inManagement: frame, subtype: subtype), !found.ssid.isEmpty {
                    ssidByBssid[hex(found.bssid)] = found.ssid
                }
            case 2: // data: may carry an EAPOL-Key message
                if let message = keyMessage(inData: frame) { messages.append(message) }
            default:
                break
            }
        }

        // Group the messages by the pair of devices they belong to.
        var groups: [String: [KeyMessage]] = [:]
        var order: [String] = []
        for message in messages {
            let key = hex(message.ap) + "|" + hex(message.sta)
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(message)
        }

        var lines: [String] = []
        for key in order {
            let group = groups[key] ?? []
            guard let ap = group.first?.ap, let sta = group.first?.sta else { continue }
            guard let ssid = ssidByBssid[hex(ap)], !ssid.isEmpty else { continue } // the hash needs the ESSID
            let apHex = hex(ap), staHex = hex(sta), essidHex = hex(ssid)

            // A PMKID (WPA*01) stands on its own, when an M1 carried one.
            if let pmkid = group.compactMap(\.pmkid).first {
                lines.append("WPA*01*\(hex(pmkid))*\(apHex)*\(staHex)*\(essidHex)***")
            }

            // The 4-way handshake (WPA*02): the best message pair available.
            let m1 = group.first { $0.message == 1 }
            let m2 = group.first { $0.message == 2 }
            let m3 = group.first { $0.message == 3 }
            let m4 = group.first { $0.message == 4 }

            func eapolLine(mic: [UInt8], anonce: [UInt8], carrier: KeyMessage, pair: UInt8, matched: Bool) -> String {
                var eapol = carrier.eapol
                for index in 0..<16 where carrier.micOffset + index < eapol.count { eapol[carrier.micOffset + index] = 0 }
                let messagePair = pair | (matched ? 0 : 0x80) // 0x80: replay counter not verified
                return "WPA*02*\(hex(mic))*\(apHex)*\(staHex)*\(essidHex)*\(hex(anonce))*\(hex(eapol))*"
                    + String(format: "%02x", messagePair)
            }

            if let m1, let m2 {
                // ANonce from M1, MIC + EAPOL (with the SNonce) from M2.
                lines.append(eapolLine(mic: m2.mic, anonce: m1.nonce, carrier: m2, pair: 0x00,
                                       matched: m1.replayCounter == m2.replayCounter))
            } else if let m2, let m3 {
                // ANonce from M3, MIC + EAPOL from M2.
                lines.append(eapolLine(mic: m2.mic, anonce: m3.nonce, carrier: m2, pair: 0x02,
                                       matched: m3.replayCounter == m2.replayCounter &+ 1))
            } else if let m3, let m4, m4.nonce.contains(where: { $0 != 0 }) {
                // ANonce from M3, MIC + EAPOL from M4 (only when M4 still carries the SNonce).
                lines.append(eapolLine(mic: m4.mic, anonce: m3.nonce, carrier: m4, pair: 0x05,
                                       matched: m3.replayCounter == m4.replayCounter))
            }
        }
        return lines
    }

    // MARK: Frame access

    /// The bare 802.11 frame for a packet, stripping a radiotap header when present.
    private static func dot11Frame(linkType: LinkType, _ data: [UInt8]) -> [UInt8]? {
        switch linkType {
        case .ieee802_11:
            return data.count >= 24 ? data : nil
        case .ieee802_11_radiotap:
            guard data.count >= 4 else { return nil }
            let length = Int(data[2]) | (Int(data[3]) << 8) // radiotap it_len is little-endian
            guard length >= 4, length < data.count else { return nil }
            let frame = Array(data[length...])
            return frame.count >= 24 ? frame : nil
        default:
            return nil
        }
    }

    /// The BSSID and SSID a management frame carries, when it is one that names a network.
    private static func ssid(inManagement frame: [UInt8], subtype: UInt8) -> (bssid: [UInt8], ssid: [UInt8])? {
        guard frame.count >= 22 else { return nil }
        let tagStart: Int
        switch subtype {
        case 8, 5: tagStart = 36 // beacon / probe response: 24 header + 12 fixed
        case 0: tagStart = 28    // association request: 24 + 4 fixed
        case 2: tagStart = 34    // reassociation request: 24 + 10 fixed
        default: return nil
        }
        let bssid = Array(frame[16..<22]) // addr3 is the BSSID in these frames
        var index = tagStart
        while index + 2 <= frame.count {
            let id = frame[index]
            let length = Int(frame[index + 1])
            guard index + 2 + length <= frame.count else { break }
            if id == 0 { return (bssid, Array(frame[(index + 2)..<(index + 2 + length)])) }
            index += 2 + length
        }
        return (bssid, [])
    }

    /// Reads the EAPOL-Key message inside a data frame, when there is one.
    private static func keyMessage(inData frame: [UInt8]) -> KeyMessage? {
        let snap: [UInt8] = [0xAA, 0xAA, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8E]
        guard let snapAt = indexOf(snap, in: frame, limit: 48) else { return nil }
        let o = snapAt + snap.count // start of the 802.1X frame
        // Need the fixed EAPOL-Key fields through the key-data length (2 bytes at o+97).
        guard o + 99 <= frame.count, frame[o + 1] == 0x03 else { return nil }
        let keyInfo = be16(frame, o + 5)
        let pairwise = keyInfo & 0x0008 != 0
        let ack = keyInfo & 0x0080 != 0
        let mic = keyInfo & 0x0100 != 0
        let secure = keyInfo & 0x0200 != 0
        guard pairwise else { return nil }
        let message: Int
        if ack && !mic { message = 1 }
        else if ack && mic { message = 3 }
        else if !ack && mic && !secure { message = 2 }
        else if !ack && mic && secure { message = 4 }
        else { return nil }

        // The ACK bit tells apart the two directions: a message with ACK is from the AP.
        let addr1 = Array(frame[4..<10])  // receiver
        let addr2 = Array(frame[10..<16]) // transmitter
        let ap = ack ? addr2 : addr1
        let sta = ack ? addr1 : addr2

        let bodyLength = Int(be16(frame, o + 2))
        let eapolEnd = min(o + 4 + bodyLength, frame.count)
        guard eapolEnd - o >= 99 else { return nil }

        let nonce = Array(frame[(o + 17)..<(o + 49)])
        let micBytes = Array(frame[(o + 81)..<(o + 97)])
        let eapol = Array(frame[o..<eapolEnd])

        var pmkid: [UInt8]? = nil
        if message == 1 {
            let keyDataLength = Int(be16(frame, o + 97))
            let keyDataStart = o + 99
            let keyDataEnd = min(keyDataStart + keyDataLength, frame.count)
            if keyDataStart < keyDataEnd {
                let keyData = Array(frame[keyDataStart..<keyDataEnd])
                // RSN PMKID KDE: … 00 0F AC 04 <16-byte PMKID>.
                if let at = indexOf([0x00, 0x0F, 0xAC, 0x04], in: keyData, limit: keyData.count),
                   at + 4 + 16 <= keyData.count {
                    let candidate = Array(keyData[(at + 4)..<(at + 4 + 16)])
                    if candidate.contains(where: { $0 != 0 }) { pmkid = candidate }
                }
            }
        }

        return KeyMessage(ap: ap, sta: sta, message: message, replayCounter: be64(frame, o + 9),
                          nonce: nonce, mic: micBytes, eapol: eapol, micOffset: 81, pmkid: pmkid)
    }

    // MARK: Byte helpers

    private static func be16(_ p: [UInt8], _ o: Int) -> UInt16 {
        guard o >= 0, o + 2 <= p.count else { return 0 }
        return UInt16(p[o]) << 8 | UInt16(p[o + 1])
    }

    private static func be64(_ p: [UInt8], _ o: Int) -> UInt64 {
        guard o >= 0, o + 8 <= p.count else { return 0 }
        var value: UInt64 = 0
        for index in 0..<8 { value = value << 8 | UInt64(p[o + index]) }
        return value
    }

    private static func indexOf(_ needle: [UInt8], in haystack: [UInt8], limit: Int) -> Int? {
        guard !needle.isEmpty, haystack.count >= needle.count else { return nil }
        let last = min(haystack.count - needle.count, limit)
        guard last >= 0 else { return nil }
        var i = 0
        while i <= last {
            var j = 0
            while j < needle.count && haystack[i + j] == needle[j] { j += 1 }
            if j == needle.count { return i }
            i += 1
        }
        return nil
    }

    private static func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }
}
