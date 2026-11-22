import Foundation
import Testing
@testable import Spectra

/// Talking about a traffic capture: the write-up the assistant reads, when it gets one, and the 5 GHz limits.
@MainActor
struct CaptureAssistantTests {
    private static let ap: [UInt8] = [0x50, 0xC7, 0xBF, 0x1E, 0x00, 0xA0]
    private static let phone: [UInt8] = [0x02, 0x11, 0x22, 0x33, 0x44, 0x55]
    private static let broadcast: [UInt8] = Array(repeating: 0xFF, count: 6)

    /// A bare 802.11 frame: frame control, duration, three addresses, sequence, a little body.
    private static func frame(_ frameControl: UInt8, from source: [UInt8], to destination: [UInt8], at offset: TimeInterval,
                              channel: Int = 36) -> PcapWriter.Frame {
        let bytes: [UInt8] = [frameControl, 0x00, 0x00, 0x00] + destination + source + ap + [0x10, 0x00] + Array(repeating: 0x00, count: 12)
        return PcapWriter.Frame(timestamp: Date(timeIntervalSince1970: 1_800_000_000 + offset), channel: channel, rssi: -50,
                                originalLength: bytes.count, bytes: bytes)
    }

    private static var sample: CaptureAnalysis {
        get throws {
            var frames: [PcapWriter.Frame] = []
            for i in 0..<3 { frames.append(frame(0x80, from: ap, to: broadcast, at: Double(i) * 0.1)) }          // beacons
            for i in 0..<2 { frames.append(frame(0xC0, from: ap, to: phone, at: 0.5 + Double(i) * 0.01)) }      // deauth
            for i in 0..<4 { frames.append(frame(0x08, from: phone, to: ap, at: 1 + Double(i) * 0.2)) }         // data
            return try PcapAnalyzer.analyze(PcapWriter.pcap(frames))
        }
    }

    @Test func digestDescribesARadioCaptureCompactly() throws {
        let text = CaptureDigest.text(try Self.sample, name: "Home", origin: "taken by the Spectra sensor") { address in
            address.lowercased().hasPrefix("50:c7") ? "access point of \"Home\"" : nil
        }
        #expect(text.hasPrefix("Traffic capture \"Home\", taken by the Spectra sensor: 9 packets"))
        #expect(text.contains("802.11 + radiotap"))
        #expect(text.contains("encrypted"), "the model is told what a radio capture can and cannot show")
        #expect(text.contains("beacon 3") && text.contains("deauth 2"))
        #expect(text.contains("access point of \"Home\""))
        #expect(text.contains("First packets:"))
        #expect(text.count <= CaptureDigest.budget + 10)
    }

    @Test func emptyCaptureIsSaidPlainly() throws {
        let analysis = try PcapAnalyzer.analyze(PcapWriter.pcap([]))
        #expect(CaptureDigest.text(analysis, name: "x", origin: "y").contains("holds no packets"))
    }

    @Test func theCaptureSectionReadsTheCapture() throws {
        var snapshot = AssistantSnapshot()
        snapshot.capture = "Traffic capture \"Home\", taken by the Spectra sensor: 9 packets."
        #expect(AssistantDigest.section(.capture, snapshot: snapshot).contains("Traffic capture \"Home\""))
        #expect(AssistantDigest.section(.capture, snapshot: AssistantSnapshot()).contains("none yet"), "with no capture the model can say where to take one")
    }

    @Test func decodedCaptureReachesTheAssistant() throws {
        let store = NetworkStore()
        let history = HistoryStore()
        let shelf = CaptureShelf()
        let sources = AssistantSources(
            store: store, lan: LANScanner(), ble: BLEScanner(), history: history,
            link: SensorLink(store: store, history: history), probe: LinkProbe(), joiner: WiFiJoiner(), speed: SpeedTester(),
            captures: shelf, memory: AssistantMemory()
        )
        #expect(sources.snapshot().capture == nil)
        shelf.keep(name: "office.pcap", analysis: try Self.sample, fromSensor: false)
        let text = try #require(sources.snapshot().capture)
        #expect(text.contains("office.pcap") && text.contains("a file opened on the phone"))
    }

    @Test func fiveGHzCaptureNeedsTheBandAndNewFirmware() {
        #expect(SensorLink.captureSupport(channel: 6, bands: ["2.4"], firmware: "0.9.0") == .ok)
        #expect(SensorLink.captureSupport(channel: 36, bands: ["2.4"], firmware: "0.10.1") == .bandNotHeard)
        #expect(SensorLink.captureSupport(channel: 36, bands: ["2.4", "5"], firmware: "0.10.0") == .firmwareTooOld(version: "0.10.0"))
        #expect(SensorLink.captureSupport(channel: 149, bands: ["2.4", "5"], firmware: "0.9.9") == .firmwareTooOld(version: "0.9.9"))
        #expect(SensorLink.captureSupport(channel: 36, bands: ["2.4", "5"], firmware: "0.10.1") == .ok)
        #expect(SensorLink.captureSupport(channel: 36, bands: ["2.4", "5"], firmware: "0.11.0") == .ok)
    }

    @Test func firmwareImageNamesItsChip() {
        func header(chip: UInt16) -> Data {
            var bytes = [UInt8](repeating: 0, count: 32)
            bytes[0] = FirmwareUpdate.magic
            bytes[12] = UInt8(chip & 0xFF)
            bytes[13] = UInt8(chip >> 8)
            return Data(bytes)
        }
        #expect(FirmwareUpdate.chipName(header(chip: 0x0009)) == "ESP32-S3")
        #expect(FirmwareUpdate.chipName(header(chip: 0x0017)) == "ESP32-C5")
        #expect(FirmwareUpdate.chipName(header(chip: 0x0000)) == "ESP32")
        #expect(FirmwareUpdate.chipName(header(chip: 0x0042)) == nil)
        #expect(FirmwareUpdate.chipName(Data([0x00, 0x01])) == nil)
    }
}
