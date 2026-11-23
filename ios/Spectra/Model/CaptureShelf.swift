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

    /// Where the cards of recorded requests read the recordings. Set once at launch.
    @ObservationIgnored var library = TrafficLibrary()
    /// Cards worked out off the main thread just before they are shown, newest last.
    @ObservationIgnored private var prepared: [(subject: CardSubject, facts: CardFacts)] = []

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

    func keep(_ facts: CardFacts, for subject: CardSubject) {
        prepared.removeAll { $0.subject == subject }
        prepared.append((subject, facts))
        if prepared.count > 4 { prepared.removeFirst() }
    }

    func prepared(_ subject: CardSubject) -> CardFacts? { prepared.last { $0.subject == subject }?.facts }

    /// What the assistant reads as the recorded traffic: the one handed over last, else whatever the screen holds.
    var currentRecording: Recording? { recording ?? recordedTraffic() }
}

extension TrafficSniffer {
    /// What the cards of recorded requests read the recordings through.
    var library: TrafficLibrary {
        TrafficLibrary(
            live: { [weak self] in self?.flows ?? [] },
            saved: { [weak self] in self?.saved ?? [] },
            recording: { [weak self] in self?.running ?? false },
            body: { [weak self] session, flow, index, request in
                self?.body(sessionID: session, flowID: flow, index: index, request: request)
            }
        )
    }

    /// A Map Local rule that answers a recorded request with the response it got.
    func localRule(_ flow: TrafficFlow, _ index: Int, sessionID: String?) -> MapRule {
        let exchange = flow.exchanges[index]
        var text = exchange.responseJson ?? ""
        if exchange.responseBodyStored,
           let data = body(sessionID: sessionID, flowID: flow.id, index: index, request: false) {
            switch BodyPreview.of([UInt8](data), contentType: exchange.header("Content-Type", response: true)) {
            case .json(let value), .text(let value): text = value
            default: break
            }
        }
        let type = exchange.header("Content-Type", response: true)?.components(separatedBy: ";").first?
            .trimmingCharacters(in: .whitespaces) ?? "application/json"
        return MapRule(host: flow.host ?? flow.destination, path: exchange.path, status: exchange.status ?? 200,
                       contentType: type, body: text)
    }

    /// What a card about the live list is called.
    var recordingName: String { running ? String(localized: "Recording in progress") : String(localized: "Current recording") }

    static func name(of session: TrafficSession) -> String {
        let stopped = Date(timeIntervalSince1970: Double(session.stoppedAt) / 1000)
        return String(localized: "Saved session of \(stopped.formatted(.dateTime.day().month().hour().minute()))")
    }

    /// The traffic to hand the assistant: the live list while there is one, else the newest saved session.
    var assistantRecording: CaptureShelf.Recording? {
        if !flows.isEmpty {
            let last = flows.map(\.lastAt).max().map { Date(timeIntervalSince1970: Double($0) / 1000) } ?? Date()
            return CaptureShelf.Recording(name: recordingName, flows: flows, keptAt: last)
        }
        guard let session = saved.first else { return nil }
        let stopped = Date(timeIntervalSince1970: Double(session.stoppedAt) / 1000)
        return CaptureShelf.Recording(name: Self.name(of: session),
                                      flows: session.flows, keptAt: stopped)
    }
}
