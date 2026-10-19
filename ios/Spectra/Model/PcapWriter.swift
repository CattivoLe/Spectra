import Foundation

/// Builds a classic `.pcap` file from captured 802.11 frames — the reverse of
/// `PcapParser`. Each frame is written with a small radiotap header carrying its
/// channel and signal, so the file is link type 127 and opens the same in
/// Wireshark and in Spectra's own decoder.
nonisolated enum PcapWriter {
    /// One frame to write. `bytes` is what was captured (it may be shorter than
    /// `originalLength` when the sensor truncated the frame to its snap length).
    struct Frame: Equatable {
        var timestamp: Date
        var channel: Int
        var rssi: Int
        var originalLength: Int
        var bytes: [UInt8]
    }

    static let linkType = LinkType.ieee802_11_radiotap

    static func pcap(_ frames: [Frame]) -> Data {
        var out = [UInt8]()
        out.reserveCapacity(24 + frames.count * 64)

        // Global header, little-endian, microsecond timestamps.
        append32(&out, 0xA1B2_C3D4)   // magic
        append16(&out, 2)             // version major
        append16(&out, 4)             // version minor
        append32(&out, 0)             // thiszone
        append32(&out, 0)             // sigfigs
        append32(&out, 65535)         // snaplen
        append32(&out, linkType.rawValue)

        for frame in frames {
            let radiotap = radiotapHeader(channel: frame.channel, rssi: frame.rssi)
            let interval = frame.timestamp.timeIntervalSince1970
            let seconds = UInt32(truncatingIfNeeded: Int(interval.rounded(.down)))
            let micros = UInt32((interval - interval.rounded(.down)) * 1_000_000)
            let inclLen = radiotap.count + frame.bytes.count
            let origLen = radiotap.count + max(frame.originalLength, frame.bytes.count)
            append32(&out, seconds)
            append32(&out, micros)
            append32(&out, UInt32(inclLen))
            append32(&out, UInt32(origLen))
            out.append(contentsOf: radiotap)
            out.append(contentsOf: frame.bytes)
        }
        return Data(out)
    }

    /// A 13-byte radiotap header exposing the channel (frequency + band flags)
    /// and the dBm antenna signal — the two fields Spectra knows per frame.
    static func radiotapHeader(channel: Int, rssi: Int) -> [UInt8] {
        let freq = frequency(forChannel: channel)
        var header: [UInt8] = [
            0x00, 0x00,             // version, pad
            0x0D, 0x00,             // header length = 13, little-endian
            0x28, 0x00, 0x00, 0x00, // present: bit3 Channel, bit5 dBm antenna signal
        ]
        append16(&header, UInt16(truncatingIfNeeded: freq))
        let channelFlags: UInt16 = freq >= 5000 ? 0x0100 : 0x0080 // 5 GHz vs 2 GHz
        append16(&header, channelFlags)
        header.append(UInt8(bitPattern: Int8(clamping: rssi)))
        return header
    }

    /// Centre frequency in MHz for a Wi-Fi channel number.
    private static func frequency(forChannel channel: Int) -> Int {
        switch channel {
        case 14: return 2484
        case 1...13: return 2407 + channel * 5
        case 15...: return 5000 + channel * 5
        default: return 2412
        }
    }

    private static func append16(_ buffer: inout [UInt8], _ value: UInt16) {
        buffer.append(UInt8(value & 0xFF))
        buffer.append(UInt8(value >> 8))
    }

    private static func append32(_ buffer: inout [UInt8], _ value: UInt32) {
        buffer.append(UInt8(value & 0xFF))
        buffer.append(UInt8((value >> 8) & 0xFF))
        buffer.append(UInt8((value >> 16) & 0xFF))
        buffer.append(UInt8((value >> 24) & 0xFF))
    }
}

/// Reassembles the `0x24` fragments of a capture back into whole frames. BLE
/// delivers notifications in order on one connection, so a run tracks a single
/// in-flight frame and drops a partial if a fragment is lost or arrives out of
/// turn.
nonisolated struct RawFrameAssembler {
    /// A finished frame, ready to hand to `PcapWriter` once a timestamp is set.
    struct Completed: Equatable {
        var timestampMicros: UInt32
        var channel: Int
        var rssi: Int
        var originalLength: Int
        var bytes: [UInt8]
    }

    private var seq: UInt16 = 0
    private var next = 0
    private var count = 0
    private var meta: SensorProtocol.RawFrameFragment?
    private var bytes: [UInt8] = []

    /// How many fragments were dropped because one went missing.
    private(set) var dropped = 0

    mutating func reset() {
        meta = nil
        bytes = []
        next = 0
        count = 0
    }

    mutating func ingest(_ fragment: SensorProtocol.RawFrameFragment) -> Completed? {
        if fragment.fragmentIndex == 0 {
            if meta != nil { dropped += 1 } // a previous frame never finished
            seq = fragment.seq
            next = 1
            count = fragment.fragmentCount
            meta = fragment
            bytes = fragment.payload
        } else {
            guard let current = meta, fragment.seq == seq,
                  fragment.fragmentIndex == next, fragment.fragmentCount == count else {
                if meta != nil { dropped += 1 }
                reset()
                return nil
            }
            _ = current
            bytes.append(contentsOf: fragment.payload)
            next += 1
        }

        guard let current = meta, next == count else { return nil }
        let completed = Completed(
            timestampMicros: current.timestampMicros,
            channel: current.channel,
            rssi: current.rssi,
            originalLength: current.originalLength,
            bytes: bytes
        )
        reset()
        return completed
    }
}
