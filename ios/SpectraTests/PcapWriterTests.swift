import Foundation
import Testing
@testable import Spectra

/// The writer is the reverse of `PcapParser`, so the strongest check is a
/// round trip: write frames, parse them back, and confirm the bytes and link
/// type survive and the decoder can still read them.
struct PcapWriterTests {
    /// A tiny 802.11 beacon, enough for the dissector to name it.
    private func beacon(ssid: String) -> [UInt8] {
        var frame: [UInt8] = [0x80, 0x00, 0x00, 0x00]
        frame += [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]
        frame += [0x02, 0x00, 0x5E, 0x10, 0x00, 0x01]
        frame += [0x02, 0x00, 0x5E, 0x10, 0x00, 0x01]
        frame += [0x00, 0x00]
        frame += [UInt8](repeating: 0, count: 8)
        frame += [0x64, 0x00, 0x01, 0x04]
        let name = Array(ssid.utf8)
        frame += [0x00, UInt8(name.count)] + name
        return frame
    }

    @Test func roundTripsThroughParser() throws {
        let frames = [
            PcapWriter.Frame(timestamp: Date(timeIntervalSince1970: 1_700_000_000.5), channel: 6, rssi: -42, originalLength: 0, bytes: beacon(ssid: "Alpha")),
            PcapWriter.Frame(timestamp: Date(timeIntervalSince1970: 1_700_000_001.25), channel: 11, rssi: -77, originalLength: 0, bytes: beacon(ssid: "Beta")),
        ]
        // originalLength 0 should fall back to the captured length.
        let data = PcapWriter.pcap(frames.map {
            PcapWriter.Frame(timestamp: $0.timestamp, channel: $0.channel, rssi: $0.rssi, originalLength: $0.bytes.count, bytes: $0.bytes)
        })

        let parsed = try PcapParser.parse(data)
        #expect(parsed.format == .pcap)
        #expect(parsed.packets.count == 2)

        let first = parsed.packets[0]
        #expect(first.linkType == .ieee802_11_radiotap)
        // 13-byte radiotap header, then the original frame.
        let radiotapLen = Int(first.data[2]) | (Int(first.data[3]) << 8)
        #expect(radiotapLen == 13)
        #expect(Array(first.data[radiotapLen...]) == beacon(ssid: "Alpha"))

        // The decoder reads the file Spectra wrote and names the frame.
        let info = PacketDissector.dissect(linkType: first.linkType, first.data)
        #expect(info.info.localizedCaseInsensitiveContains("beacon"))
    }

    @Test func truncatedFrameKeepsOriginalLength() throws {
        let full = 300
        let captured = beacon(ssid: "Trunc") // shorter than `full`
        let data = PcapWriter.pcap([
            PcapWriter.Frame(timestamp: Date(timeIntervalSince1970: 10), channel: 1, rssi: -50, originalLength: full, bytes: captured)
        ])
        let parsed = try PcapParser.parse(data)
        let packet = try #require(parsed.packets.first)
        #expect(packet.data.count == 13 + captured.count)        // what we stored
        #expect(packet.originalLength == 13 + full)              // what was on the air
    }

    @Test func radiotapHeaderEncodesChannelAndSignal() {
        let header = PcapWriter.radiotapHeader(channel: 6, rssi: -58)
        #expect(header.count == 13)
        let freq = Int(header[8]) | (Int(header[9]) << 8)
        #expect(freq == 2437)                                     // channel 6
        #expect(Int8(bitPattern: header[12]) == -58)
    }
}
