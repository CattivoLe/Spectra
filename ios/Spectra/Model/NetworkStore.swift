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
    /// Clients associated with this AP in the latest scan.
    var clients: [WiFiClient] = []

    var id: BSSID { latest.bssid }
    var wasInLastScan: Bool { missedScans == 0 }
    /// Still counted as nearby after a few misses: weak networks drop out of
    /// single scans, and the list should not flicker with them.
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

    /// All clients seen in the latest scan, including those not associated with
    /// any known AP (probing clients).
    private(set) var clients: [WiFiClient] = []
    /// Clients that were only probing (not associated) in the latest scan.
    var probingClients: [WiFiClient] { clients.filter { !$0.isAssociated } }

    private var currentScanID: UInt16?
    private var seenInCurrentScan: Set<BSSID> = []
    /// Clients collected during the current scan, before they are assigned to APs.
    private var pendingClients: [WiFiClient] = []

    func handle(_ message: SensorProtocol.Message, at date: Date = .now) {
        switch message {
        case .scanStarted(let scanID):
            currentScanID = scanID
            seenInCurrentScan = []
            pendingClients = []
            isScanning = true

        case .accessPoint(_, let reading):
            record(reading, at: date)

        case .station(_, let client):
            recordClient(client)

        case .scanFinished(let scanID, _, let duration, _):
            // A finish without its start (the app connected mid-scan) still
            // delivered readings, but cannot say who was missed.
            if scanID == currentScanID {
                for id in networks.keys where !seenInCurrentScan.contains(id) {
                    networks[id]?.missedScans += 1
                }
                // Assign collected clients to their APs.
                assignClients()
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
        clients = []
        lastScanFinished = nil
        lastScanDuration = nil
        scanCount = 0
    }

    /// Called when the link drops mid-scan, so the UI does not spin forever.
    func abandonScan() {
        currentScanID = nil
        isScanning = false
    }

    /// Clients associated with a specific AP.
    func clients(for bssid: BSSID) -> [WiFiClient] {
        networks[bssid]?.clients ?? []
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

    private func recordClient(_ client: WiFiClient) {
        // Merge: keep the entry with more info (associated > probing).
        if let index = pendingClients.firstIndex(where: { $0.mac == client.mac }) {
            if client.isAssociated && !pendingClients[index].isAssociated {
                pendingClients[index] = client
            } else if client.rssi > pendingClients[index].rssi {
                pendingClients[index].rssi = client.rssi
            }
        } else {
            pendingClients.append(client)
        }
    }

    /// Distributes pending clients into their AP's TrackedNetwork, and keeps
    /// a flat list of all clients for the probing-clients view.
    private func assignClients() {
        clients = pendingClients.sorted { $0.rssi > $1.rssi }

        // Clear old clients from all networks.
        for id in networks.keys {
            networks[id]?.clients = []
        }

        // Assign associated clients to their AP.
        for client in pendingClients where client.isAssociated {
            if let bssid = client.apBSSID {
                networks[bssid]?.clients.append(client)
            }
        }

        // Sort each AP's clients by signal.
        for id in networks.keys {
            networks[id]?.clients.sort { $0.rssi > $1.rssi }
        }

        pendingClients = []
    }
}
