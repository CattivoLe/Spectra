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

    func attachment(for question: String) -> AppDataAttachment? {
        AssistantDigest.attachment(for: question, snapshot: snapshot())
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
        return snapshot
    }
}
