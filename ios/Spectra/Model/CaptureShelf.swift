import Foundation
import Observation

/// The traffic capture last decoded on the Decode pcap screen, kept in memory so the assistant can talk about it
/// ("what is in the capture I just took?"). Only the decoded summary is kept, never sent anywhere.
@MainActor
@Observable
final class CaptureShelf {
    struct Entry: Sendable {
        var name: String
        var analysis: CaptureAnalysis
        /// Where it came from, in words the assistant can repeat: the sensor or a file.
        var origin: String
        var decodedAt: Date
    }

    private(set) var latest: Entry?

    func keep(name: String, analysis: CaptureAnalysis, fromSensor: Bool, at date: Date = Date()) {
        latest = Entry(name: name, analysis: analysis,
                       origin: fromSensor ? "taken by the Spectra sensor" : "a file opened on the phone",
                       decodedAt: date)
    }
}
