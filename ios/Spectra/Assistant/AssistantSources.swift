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
    /// The user's own sites (Internet → Resources) and their last answers.
    var resources = ResourceWatch()
    /// The Traceroute screen's last run.
    var tracer = RouteTracer()
    /// What the Check a link screen and the link card's buttons found about recent links.
    var links = LinkShelf()

    /// What a summary card shows: its findings and steps, and the data its model actions read. `nil` when there is
    /// nothing to summarize (no recorded traffic or capture handed over).
    func cardFacts(_ subject: CardSubject, now: Date = .now) -> CardFacts? {
        switch subject {
        case .traffic:
            guard let recording = captures.currentRecording else { return nil }
            return AssistantCards.traffic(recording.flows, name: recording.name,
                                          digest: TrafficDigest.text(recording.flows, name: recording.name,
                                                                     origin: "recorded on the phone (Traffic screen)"))
        case .capture:
            guard let entry = captures.latest else { return nil }
            return AssistantCards.capture(entry.analysis, name: entry.name,
                                          digest: CaptureDigest.text(entry.analysis, name: entry.name, origin: entry.origin, describe: describe))
        case .link(let link):
            return links.facts(link, now: now)
        default:
            return AssistantCards.facts(subject, snapshot: snapshot(now: now), now: now)
        }
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
                // In English whatever the app language: the card rules and the model read it ("Open", "WEP").
                security: reading.auth == .open ? "Open" : reading.auth.rawValue > 15 ? "Other" : reading.auth.name,
                maker: reading.bssid.isLocallyAdministered ? nil : OUIDatabase.lookup(bytes: reading.bssid.bytes),
                isCurrent: joiner.isCurrent(network),
                isHidden: name == nil,
                inRange: network.isInRange,
                clients: network.clients.count,
                streaming: (network.traffic?.bytes ?? 0) >= Threats.streamingByteThreshold
            )
        }
        snapshot.findings = Threats.assess(networks: networks, deauths: store.deauthEvents).map {
            AssistantSnapshot.Finding(severity: $0.severity == .critical ? "critical" : "warning", title: $0.title, detail: $0.detail,
                                      bssids: $0.networks.map(\.description))
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
                allPortsScanned: device.allPortsScanned,
                risks: risks.map { "\($0.title) (\($0.level.label))" },
                isGateway: device.isGateway,
                isThisPhone: device.isLocalDevice,
                isNew: device.isNew,
                trust: device.trust.rawValue,
                note: device.note,
                portRisks: risks.map { AssistantSnapshot.PortRiskItem(title: $0.title, high: $0.level >= .high) },
                forwarding: device.isGateway ? device.profile.portForwarding.map { table in
                    AssistantSnapshot.Forwarding(
                        supported: table.supported,
                        rules: table.mappings.filter(\.enabled).map { "\($0.proto) \($0.externalPort) → \($0.internalClient):\($0.internalPort)" }
                    )
                } : nil
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
                away: assessment?.separated,
                id: device.id.uuidString
            )
        }

        snapshot.sessions = history.entries.map {
            AssistantSnapshot.Session(kind: $0.kind.title, date: $0.savedAt, source: $0.source, place: $0.placeLabel, count: $0.itemCount)
        }

        let measured = speed.result
        if let report = probe.report, report.status != nil {
            var info = AssistantSnapshot.Link(checkedAt: report.checkedAt)
            // Spelled as on Android: the card rules and the model read "no_network", "whitelist_only".
            info.status = report.status.map { $0 == .noNetwork ? "no_network" : $0.rawValue }
            info.detail = report.detail
            info.gatewayMs = report.gateway?.millis
            info.dnsMs = report.dns?.millis
            info.webMs = report.web?.millis
            info.publicIP = report.external?.ip
            info.provider = report.external?.isp
            info.country = report.external?.countryCode
            // The connection check samples the download; a full speed test run after it is the fresher word.
            let fresher = measured.map { $0.measuredAt >= report.checkedAt } ?? false
            let down: Double? = fresher ? (measured?.downBps ?? report.speed?.downBps) : (report.speed?.downBps ?? measured?.downBps)
            let up: Double? = fresher ? (measured?.upBps ?? report.speed?.upBps) : (report.speed?.upBps ?? measured?.upBps)
            info.downMbps = down.map { $0 / 1_000_000 }
            info.upMbps = up.map { $0 / 1_000_000 }
            info.latencyMs = fresher ? (measured?.idleLatencyMs ?? report.speed?.latencyMs) : (report.speed?.latencyMs ?? measured?.idleLatencyMs)
            info.jitterMs = fresher ? (measured?.jitterMs ?? report.speed?.jitterMs) : (report.speed?.jitterMs ?? measured?.jitterMs)
            info.intercepted = report.tls?.intercepted
            info.resolverLies = report.resolver?.liesAboutMissingNames
            info.exposedPorts = report.exposure?.openPorts.map(\.port) ?? []
            info.access = report.access.map { $0.verdict == .whitelistOnly ? "whitelist_only" : $0.verdict.rawValue }
            info.gatewayOk = report.gateway?.ok
            info.dnsOk = report.dns?.ok
            info.webOk = report.web?.ok
            info.resolverName = report.resolver.flatMap { $0.resolverName ?? $0.resolverIP }
            info.resolverCountry = report.resolver?.resolverCountry
            info.blocked = report.access?.unreachable ?? []
            info.tlsReached = report.tls?.reachable
            info.exposureChecked = report.exposure != nil
            info.bufferbloat = measured?.bufferbloatGrade
            info.speedFailed = report.speedFailed
            info.via = ProbeRoute.current().rawValue
            info.warp = report.external?.warp == true
            snapshot.link = info
        } else if let measured {
            var info = AssistantSnapshot.Link(checkedAt: measured.measuredAt)
            info.downMbps = measured.downBps.map { $0 / 1_000_000 }
            info.upMbps = measured.upBps.map { $0 / 1_000_000 }
            info.latencyMs = measured.idleLatencyMs
            info.jitterMs = measured.jitterMs
            info.bufferbloat = measured.bufferbloatGrade
            snapshot.link = info
        }
        snapshot.sites = resources.hosts.compactMap { host in
            resources.results[host].map { AssistantSnapshot.SiteReach(host: host, ok: $0.ok, millis: $0.millis) }
        }
        if let target = tracer.target {
            snapshot.route = AssistantSnapshot.Route(
                target: target,
                address: tracer.address,
                hops: (tracer.trace?.hops ?? []).map { hop in
                    let place = hop.address.flatMap { tracer.places[$0] }
                    let where_ = place.map { [$0.city, $0.countryCode].compactMap { $0 }.joined(separator: ", ") }
                    let label = [where_?.isEmpty == false ? where_ : nil, place?.isp].compactMap { $0 }.joined(separator: " · ")
                    return AssistantSnapshot.RouteHop(address: hop.address, millis: hop.millis, place: label.isEmpty ? nil : label)
                },
                failure: tracer.failure,
                running: tracer.running
            )
        }
        snapshot.capture = captureText()
        if let read = ble.lastRead {
            let age = Int(now.timeIntervalSince(read.readAt) / 60)
            snapshot.gatt = GATTDigest.text(read, intro: age < 1 ? "Read just now from" : "Read \(age) min ago from")
            snapshot.gattDeviceID = read.deviceID.uuidString
            snapshot.gattItems = read.services.flatMap { service in
                let vendor = BLENumbers.serviceName(uuid: service.uuid) == nil
                let serviceName = service.name ?? BLENumbers.serviceName(uuid: service.uuid) ?? service.uuid
                return service.characteristics.map { characteristic in
                    AssistantSnapshot.GattItem(
                        service: serviceName,
                        name: BLENumbers.characteristicName(uuid: characteristic.uuid) ?? characteristic.uuid,
                        value: characteristic.value.flatMap { GATTValue.decode(uuid: characteristic.uuid, bytes: $0) },
                        writable: characteristic.properties.localizedCaseInsensitiveContains("write"),
                        vendor: vendor
                    )
                }
            }
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
