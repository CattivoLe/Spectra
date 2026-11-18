import Foundation
import LocalChatKit

/// Reads the live scanners of Spectra into an `AssistantSnapshot`. Read-only: the assistant can look at what the app
/// has found but cannot start scans, change labels or send anything.
@MainActor
struct AssistantSources {
    let store: NetworkStore
    let lan: LANScanner
    let ble: BLEScanner
    let history: HistoryStore
    let link: SensorLink
    let probe: LinkProbe
    let joiner: WiFiJoiner
    let speed: SpeedTester
    let captures: CaptureShelf
    let memory: AssistantMemory

    func attachment(for question: String) -> AppDataAttachment? {
        var current = snapshot()
        // The dossier is assembled per question: only what is in front of the user, or named in the question,
        // travels with it. See `AssistantMemory.digest(subjects:)`.
        current.memory = memory.digest(subjects: AssistantDigest.subjects(in: question, snapshot: current))
        return AssistantDigest.attachment(for: question, snapshot: current)
    }

    /// Folds what is on screen into the long-term baselines. Called on the app's own save tick, so it skips the
    /// expensive parts of a snapshot: no capture is decoded and no threat assessment runs.
    func recordBaselines(now: Date = Date()) {
        memory.observe(sightings(), now: now)
    }

    private func sightings() -> [AssistantMemory.Sighting] {
        var found: [AssistantMemory.Sighting] = []
        for device in lan.sortedDevices where !device.isLocalDevice {
            found.append(AssistantMemory.Sighting(
                subject: device.macAddress?.description ?? device.ipAddress,
                label: device.displayName,
                kind: .host,
                rssi: nil,
                ports: device.openPorts.map(\.number),
                portsKnown: device.portsScanned
            ))
        }
        for network in store.networks.values where network.isInRange {
            let reading = network.latest
            found.append(AssistantMemory.Sighting(
                subject: reading.bssid.description,
                label: network.revealedSSID ?? reading.ssid ?? reading.bssid.description,
                kind: .wifi,
                rssi: reading.rssi
            ))
        }
        for device in ble.devices.values where device.isNearby(at: Date()) {
            found.append(AssistantMemory.Sighting(
                subject: device.id.uuidString,
                label: device.displayName,
                kind: .bluetooth,
                rssi: device.rssi
            ))
        }
        return found
    }

    /// Just enough to choose the opening questions of an empty chat, without building a whole snapshot.
    func readiness() -> AssistantStarters.Readiness {
        AssistantStarters.Readiness(
            sensorConnected: link.isConnected,
            hasHosts: !lan.devices.isEmpty,
            hasBluetooth: !ble.devices.isEmpty,
            hasWifi: !store.networks.isEmpty,
            hasLinkReport: probe.report?.status != nil,
            hasCapture: captures.latest != nil || captures.currentRecording != nil || (!link.rawCapturing && link.rawFrameCount > 0)
        )
    }

    func snapshot(now: Date = Date()) -> AssistantSnapshot {
        var snapshot = AssistantSnapshot()
        snapshot.wifiName = joiner.currentSSID ?? lan.wifi.current?.ssid
        snapshot.networkLabel = lan.networkLabel
        snapshot.sensorConnected = link.isConnected
        snapshot.sensorName = link.info?.name
        snapshot.lastWifiScan = store.lastScanFinished

        let networks = Array(store.networks.values)
        snapshot.wifi = networks.map { network in
            let reading = network.latest
            let name = network.revealedSSID ?? reading.ssid
            return AssistantSnapshot.Wifi(
                name: name ?? "",
                bssid: reading.bssid.description,
                rssi: reading.rssi,
                channel: reading.channel,
                band: reading.band.rawValue,
                security: reading.auth.name,
                maker: reading.bssid.isLocallyAdministered ? nil : OUIDatabase.lookup(bytes: reading.bssid.bytes),
                isCurrent: joiner.isCurrent(network),
                isHidden: name == nil,
                inRange: network.isInRange,
                clients: network.clients.count,
                streaming: (network.traffic?.bytes ?? 0) >= Threats.streamingByteThreshold
            )
        }
        snapshot.findings = Threats.assess(networks: networks, deauths: store.deauthEvents).map {
            AssistantSnapshot.Finding(severity: $0.severity == .critical ? "critical" : "warning", title: $0.title, detail: $0.detail)
        }
        snapshot.deauthFrames = store.deauthEvents.reduce(0) { $0 + $1.count }
        snapshot.probingDevices = store.probingDevices.count

        snapshot.lanScanning = lan.isScanning
        snapshot.hosts = lan.sortedDevices.map { device in
            let risks = PortRisk.findings(for: device).filter { $0.level >= .medium }
            return AssistantSnapshot.Host(
                ip: device.ipAddress,
                mac: device.macAddress?.description,
                name: device.displayName,
                maker: device.identity.manufacturer ?? device.manufacturer,
                system: device.identity.operatingSystem,
                ports: device.openPorts.map { "\($0.number) \($0.service)" },
                portsScanned: device.portsScanned,
                risks: risks.map { "\($0.title) (\($0.level.label))" },
                isGateway: device.isGateway,
                isThisPhone: device.isLocalDevice,
                isNew: device.isNew,
                trust: device.trust.rawValue,
                note: device.note
            )
        }

        snapshot.bluetooth = ble.devices.values.map { device in
            let assessment = TrackerDetector.assess(device, now: now)
            return AssistantSnapshot.Bluetooth(
                name: device.displayName,
                company: device.companyName,
                rssi: device.rssi,
                nearby: device.isNearby(at: now),
                minutesNearby: Int(device.presentFor(at: now) / 60),
                tracker: assessment?.kind.label,
                trackerLevel: assessment.map { $0.level == .alert ? "alert" : ($0.level == .watch ? "watch" : "info") },
                away: assessment?.separated
            )
        }

        snapshot.sessions = history.entries.map {
            AssistantSnapshot.Session(kind: $0.kind.title, date: $0.savedAt, source: $0.source, place: $0.placeLabel, count: $0.itemCount)
        }

        if let report = probe.report, report.status != nil {
            var info = AssistantSnapshot.Link(checkedAt: report.checkedAt)
            info.status = report.status?.rawValue
            info.detail = report.detail
            info.gatewayMs = report.gateway?.millis
            info.dnsMs = report.dns?.millis
            info.webMs = report.web?.millis
            info.publicIP = report.external?.ip
            info.provider = report.external?.isp
            info.country = report.external?.countryCode
            let down: Double? = report.speed?.downBps ?? speed.result?.downBps
            let up: Double? = report.speed?.upBps ?? speed.result?.upBps
            info.downMbps = down.map { $0 / 1_000_000 }
            info.upMbps = up.map { $0 / 1_000_000 }
            info.latencyMs = report.speed?.latencyMs ?? speed.result?.idleLatencyMs
            info.jitterMs = report.speed?.jitterMs ?? speed.result?.jitterMs
            info.intercepted = report.tls?.intercepted
            info.resolverLies = report.resolver?.liesAboutMissingNames
            info.exposedPorts = report.exposure?.openPorts.map(\.port) ?? []
            info.access = report.access?.verdict.rawValue
            snapshot.link = info
        }
        snapshot.capture = captureText()
        if let read = ble.lastRead {
            let age = Int(now.timeIntervalSince(read.readAt) / 60)
            snapshot.gatt = GATTDigest.text(read, intro: age < 1 ? "Read just now from" : "Read \(age) min ago from")
        }
        return snapshot
    }

    /// Both kinds of recorded traffic the user may mean by "the capture", newest first: the Wi‑Fi capture (see
    /// `pcapText`) and the phone's own traffic from the Traffic screen.
    private func captureText() -> String? {
        var parts: [(date: Date, text: String)] = []
        if let pcap = pcapText() { parts.append(pcap) }
        if let recording = captures.currentRecording {
            parts.append((recording.keptAt, TrafficDigest.text(recording.flows, name: recording.name,
                                                                origin: "recorded on the phone (Traffic screen)")))
        }
        guard !parts.isEmpty else { return nil }
        return parts.sorted { $0.date > $1.date }.map(\.text).joined(separator: "\n\n")
    }

    /// The capture last decoded on the Decode pcap screen, or else the one the sensor still holds from its last run.
    private func pcapText() -> (date: Date, text: String)? {
        if let entry = captures.latest {
            return (entry.decodedAt, CaptureDigest.text(entry.analysis, name: entry.name, origin: entry.origin, describe: describe))
        }
        guard !link.rawCapturing, link.rawFrameCount > 0,
              let analysis = try? PcapAnalyzer.analyze(link.rawCapturePcap()) else { return nil }
        var name = "the sensor's last capture"
        if let bssid = link.rawCaptureNetwork, let network = store.networks[bssid] {
            name = network.revealedSSID ?? network.latest.ssid ?? bssid.description
        }
        return (.distantPast, CaptureDigest.text(analysis, name: name, origin: "taken by the Spectra sensor, not opened yet", describe: describe))
    }

    /// A name for an address seen in a capture: the Wi‑Fi network it belongs to, a LAN device, or the maker.
    func describe(_ address: String) -> String? {
        if let bssid = BSSID(string: address) {
            if let network = store.networks[bssid] {
                let name = network.revealedSSID ?? network.latest.ssid
                return name.map { "access point of \"\($0)\"" } ?? "hidden access point"
            }
            if bssid.isLocallyAdministered { return "random MAC" }
            return OUIDatabase.lookup(bytes: bssid.bytes)
        }
        if let device = lan.sortedDevices.first(where: { $0.ipAddress == address }) {
            return device.displayName
        }
        if let mac = MACAddress(string: address),
           let device = lan.sortedDevices.first(where: { $0.macAddress == mac }) {
            return "\(device.displayName) (\(device.ipAddress))"
        }
        return nil
    }
}
