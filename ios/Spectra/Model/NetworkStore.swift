import Foundation
import Observation

/// An access point followed across scans.
struct TrackedNetwork: Identifiable, Equatable {
    struct Sample: Equatable {
        var date: Date
        var rssi: Int
    }

    var latest: AccessPointReading
    var firstSeen: Date
    var lastSeen: Date
    var samples: [Sample]
    /// Scans in a row that finished without this network in them.
    var missedScans = 0

    var id: BSSID { latest.bssid }
    var isInRange: Bool { missedScans < NetworkStore.scansBeforeOutOfRange }

    var strongestRSSI: Int { samples.map(\.rssi).max() ?? latest.rssi }
    var weakestRSSI: Int { samples.map(\.rssi).min() ?? latest.rssi }
    var averageRSSI: Int {
        samples.isEmpty ? latest.rssi : samples.map(\.rssi).reduce(0, +) / samples.count
    }
}

@Observable
final class NetworkStore {
    static let scansBeforeOutOfRange = 3
    static let samplesKept = 120

    private(set) var networks: [BSSID: TrackedNetwork] = [:]
    private(set) var isScanning = false
    private(set) var lastScanFinished: Date?
    private(set) var lastScanDuration: Duration?
    private(set) var scanCount = 0

    private var currentScanID: UInt16?
    private var seenInCurrentScan: Set<BSSID> = []

    func handle(_ message: SensorProtocol.Message, at date: Date = .now) {
        switch message {
        case .scanStarted(let scanID):
            currentScanID = scanID
            seenInCurrentScan = []
            isScanning = true

        case .accessPoint(_, let reading):
            record(reading, at: date)

        case .scanFinished(let scanID, _, let duration):
            // A finish without its start (the app connected mid-scan) still
            // delivered readings, but cannot say who was missed.
            if scanID == currentScanID {
                for id in networks.keys where !seenInCurrentScan.contains(id) {
                    networks[id]?.missedScans += 1
                }
            }
            currentScanID = nil
            isScanning = false
            lastScanFinished = date
            lastScanDuration = duration
            scanCount += 1
        }
    }

    func clear() {
        networks = [:]
        lastScanFinished = nil
        lastScanDuration = nil
        scanCount = 0
    }

    /// Called when the link drops mid-scan, so the UI does not spin forever.
    func abandonScan() {
        currentScanID = nil
        isScanning = false
    }

    private func record(_ reading: AccessPointReading, at date: Date) {
        seenInCurrentScan.insert(reading.bssid)
        let sample = TrackedNetwork.Sample(date: date, rssi: reading.rssi)
        if var network = networks[reading.bssid] {
            network.latest = reading
            network.lastSeen = date
            network.missedScans = 0
            network.samples.append(sample)
            if network.samples.count > Self.samplesKept {
                network.samples.removeFirst(network.samples.count - Self.samplesKept)
            }
            networks[reading.bssid] = network
        } else {
            networks[reading.bssid] = TrackedNetwork(
                latest: reading, firstSeen: date, lastSeen: date, samples: [sample]
            )
        }
    }
}
