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

    /// The phone's own traffic recorded on the Traffic screen, handed over with its ✨ button.
    struct Recording: Sendable {
        var name: String
        var flows: [TrafficFlow]
        var keptAt: Date
    }

    private(set) var latest: Entry?
    private(set) var recording: Recording?
    /// The traffic the Traffic screen holds right now, read when the assistant is asked about recorded traffic
    /// without the button: the live list, or else the newest saved session. Set once at launch.
    var recordedTraffic: @MainActor () -> Recording? = { nil }

    func keep(name: String, analysis: CaptureAnalysis, fromSensor: Bool, at date: Date = Date()) {
        latest = Entry(name: name, analysis: analysis,
                       origin: fromSensor ? "taken by the Spectra sensor" : "a file opened on the phone",
                       decodedAt: date)
    }

    func keep(traffic flows: [TrafficFlow], name: String, at date: Date = Date()) {
        recording = Recording(name: name, flows: flows, keptAt: date)
    }

    /// What the assistant reads as the recorded traffic: the one handed over last, else whatever the screen holds.
    var currentRecording: Recording? { recording ?? recordedTraffic() }
}

extension TrafficSniffer {
    /// The traffic to hand the assistant: the live list while there is one, else the newest saved session.
    var assistantRecording: CaptureShelf.Recording? {
        if !flows.isEmpty {
            let last = flows.map(\.lastAt).max().map { Date(timeIntervalSince1970: Double($0) / 1000) } ?? Date()
            return CaptureShelf.Recording(name: running ? "recording in progress" : "current recording", flows: flows, keptAt: last)
        }
        guard let session = saved.first else { return nil }
        let stopped = Date(timeIntervalSince1970: Double(session.stoppedAt) / 1000)
        return CaptureShelf.Recording(name: "saved session of " + stopped.formatted(.dateTime.day().month().hour().minute()),
                                      flows: session.flows, keptAt: stopped)
    }
}
