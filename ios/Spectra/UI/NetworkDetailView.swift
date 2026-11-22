import Charts
import SwiftUI

struct NetworkDetailView: View {
    private var lookup: BSSID?
    private var snapshot: TrackedNetwork?

    init(id: BSSID) {
        lookup = id
    }

    /// A saved network, shown with the same card as a live one.
    init(network: TrackedNetwork) {
        snapshot = network
    }

    @Environment(NetworkStore.self) private var store
    @Environment(SensorLink.self) private var link
    @Environment(WiFiJoiner.self) private var joiner
    @State private var pendingJoin: PendingJoin?
    @State private var passphrase = ""
    @State private var decoding: PcapView.Capture?

    /// A secured network waiting for its password before the join is applied.
    private struct PendingJoin: Identifiable {
        let id = UUID()
        var ssid: String
        var isWEP: Bool
        var hidden: Bool
    }

    var body: some View {
        if let network = snapshot ?? lookup.flatMap({ store.networks[$0] }) {
            content(network)
        } else {
            ContentUnavailableView("Network cleared", systemImage: "wifi.slash")
        }
    }

    private func content(_ network: TrackedNetwork) -> some View {
        let reading = network.latest
        let quality = SignalQuality(rssi: reading.rssi)

        return List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 6) {
                        Text(network.revealedSSID ?? reading.displayName)
                            .font(.title2.weight(.semibold))
                            .italic(reading.ssid == nil && network.revealedSSID == nil)
                        if network.revealedSSID != nil {
                            Image(systemName: "eye.fill")
                                .font(.subheadline)
                                .foregroundStyle(.purple)
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    HStack(alignment: .firstTextBaseline) {
                        Text("\(reading.rssi)")
                            .font(.system(size: 52, weight: .semibold, design: .rounded).monospacedDigit())
                            .contentTransition(.numericText())
                        Text("dBm").font(.title3).foregroundStyle(.secondary)
                        Spacer()
                        Text(network.isInRange ? quality.label : "Out of range")
                            .font(.headline)
                            .foregroundStyle(network.isInRange ? quality.color : .secondary)
                    }
                    Text("avg \(network.averageRSSI) · min \(network.weakestRSSI) · max \(network.strongestRSSI)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    SignalHistoryChart(samples: network.samples, color: quality.color)
                        .frame(height: 140)
                }
                .padding(.vertical, 6)
                .animation(.default, value: reading.rssi)
            }

            if !notes(for: network).isEmpty {
                Section("Notes") {
                    ForEach(notes(for: network), id: \.text) { note in
                        Label {
                            Text(note.text)
                        } icon: {
                            Image(systemName: note.isWarning ? "exclamationmark.triangle.fill" : "info.circle.fill")
                                .foregroundStyle(note.isWarning ? .orange : .blue)
                        }
                        .font(.subheadline)
                    }
                }
            }

            Section("Identity") {
                InfoRow("Name", value: network.revealedSSID ?? reading.displayName)
                if network.revealedSSID != nil {
                    InfoRow("Broadcast name", value: String(localized: "Hidden"))
                }
                if let ssid = network.revealedSSID ?? reading.ssid, let password = link.knownPassword(for: ssid) {
                    InfoRow("Password") {
                        Text(password)
                            .monospaced()
                            .textSelection(.enabled)
                    }
                }
                InfoRow("BSSID") {
                    Text(reading.bssid.description)
                        .monospaced()
                        .textSelection(.enabled)
                }
                InfoRow(
                    "Address type",
                    value: reading.bssid.isLocallyAdministered
                        ? String(localized: "Randomised")
                        : String(localized: "Maker-assigned (OUI \(reading.bssid.oui))")
                )
                if !reading.bssid.isLocallyAdministered, let maker = OUIDatabase.lookup(bytes: reading.bssid.bytes) {
                    InfoRow("Maker", value: maker)
                }
                if let country = reading.countryCode {
                    InfoRow("Country", value: Locale.current.localizedString(forRegionCode: country) ?? country)
                }
                if let vendor = network.vendorInfo, !vendor.deviceName.isEmpty {
                    InfoRow("Device name", value: vendor.deviceName)
                }
                if let vendor = network.vendorInfo, !vendor.manufacturer.isEmpty {
                    InfoRow("Manufacturer", value: vendor.manufacturer)
                }
            }

            Section("Radio") {
                InfoRow("Channel", value: "\(reading.channel)")
                InfoRow("Frequency", value: Units.megahertz(reading.frequencyMHz))
                InfoRow("Band", value: Units.gigahertz(reading.band.gigahertz))
                InfoRow("Width", value: widthDescription(reading))
                InfoRow("Standards", value: reading.capabilities.standards)
                InfoRow("Generation", value: reading.capabilities.generation)
                if reading.capabilities.contains(.ftmResponder) {
                    InfoRow("Fine Timing Measurement", value: String(localized: "Supported"))
                }
                if reading.capabilities.contains(.longRange) {
                    InfoRow("Long range", value: String(localized: "802.11 LR"))
                }
            }

            if snapshot == nil, link.isConnected {
                captureSection(network)
            }

            Section("Security") {
                InfoRow("Authentication", value: reading.auth.name)
                InfoRow("Pairwise cipher", value: reading.pairwiseCipher.name)
                InfoRow("Group cipher", value: reading.groupCipher.name)
                InfoRow("WPS", value: reading.capabilities.contains(.wps) ? String(localized: "On") : String(localized: "Off"))
            }

            if let ssid = network.revealedSSID ?? reading.ssid {
                connectionSection(ssid: ssid, hidden: reading.ssid == nil, method: reading.auth.joinMethod)
            }

            // Clients (stations associated with this AP).
            if !network.clients.isEmpty {
                Section {
                    ForEach(network.clients) { client in
                        NavigationLink {
                            ClientDetailView(client: client)
                        } label: {
                            ClientRow(client: client)
                        }
                    }
                } header: {
                    Text("\(network.clients.count) clients")
                } footer: {
                    Text("Devices connected to this network, found through promiscuous-mode frame capture on the sensor.")
                }
            }

            if let traffic = network.traffic {
                Section {
                    InfoRow("Volume", value: traffic.formattedBytes)
                    InfoRow("Packets", value: "\(traffic.packets)")
                    if snapshot == nil,
                       let duration = store.lastScanDuration,
                       let bitrate = traffic.estimatedBitrate(scanDuration: duration) {
                        InfoRow("Est. bitrate", value: bitrate)
                    }
                } header: {
                    Text("Traffic")
                } footer: {
                    Text("Data frames seen during the promiscuous-mode capture window. A high bitrate may indicate streaming (cameras, microphones).")
                }
            }

            Section("Observed") {
                InfoRow("First seen") { Text(network.firstSeen.formatted(date: .abbreviated, time: .shortened)) }
                InfoRow("Last seen") { Text(network.lastSeen, style: .relative) }
                InfoRow("Readings", value: "\(network.samples.count)")
                if network.missedScans > 0 {
                    InfoRow("Missed scans", value: "\(network.missedScans)")
                }
            }
        }
        .navigationTitle(network.revealedSSID ?? reading.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $decoding) { capture in
            PcapView(capture: capture)
        }
        // Only a network still in the live list: a saved one is not in what the summary reads.
        .summaryButton(isShown: lookup != nil, subject: { .wifi(bssid: network.latest.bssid.description) })
        .onDisappear { link.stopRawCapture() }
        .task { await joiner.refreshCurrent() }
        .sheet(item: $pendingJoin) { join in
            JoinPasswordSheet(ssid: join.ssid, password: $passphrase) {
                let password = passphrase
                Task { await joiner.join(ssid: join.ssid, passphrase: password, isWEP: join.isWEP, hidden: join.hidden) }
            }
        }
    }

    // MARK: Join this network from the phone

    @ViewBuilder
    private func connectionSection(ssid: String, hidden: Bool, method: AuthMode.JoinMethod) -> some View {
        Section {
            if case .joining(let joining) = joiner.status, joining == ssid {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Connecting…").foregroundStyle(.secondary)
                }
            } else if joiner.isOn(ssid) {
                Label("Connected", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Button(role: .destructive) {
                    joiner.forget(ssid: ssid)
                } label: {
                    Label("Forget this network", systemImage: "wifi.slash")
                }
            } else {
                if case .failed(let failed, let message) = joiner.status, failed == ssid {
                    Label(message, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .font(.subheadline)
                }
                connectButton(ssid: ssid, hidden: hidden, method: method)
            }
        } header: {
            Text("Connection")
        } footer: {
            switch method {
            case .open:
                Text("This network is open. iOS will ask you to confirm before joining.")
            case .unsupported:
                Text("Enterprise and other 802.1X networks have to be joined from Settings.")
            case .psk, .wep:
                Text("Once the iPhone joins, Spectra keeps the password and hands it to the Cardputer too.")
            }
        }
    }

    @ViewBuilder
    private func connectButton(ssid: String, hidden: Bool, method: AuthMode.JoinMethod) -> some View {
        switch method {
        case .unsupported:
            EmptyView()
        case .open:
            Button {
                Task { await joiner.join(ssid: ssid, passphrase: nil, isWEP: false, hidden: hidden) }
            } label: {
                Label("Connect to this network", systemImage: "wifi")
            }
        case .psk, .wep:
            let saved = link.knownPassword(for: ssid)
            if let saved {
                Button {
                    Task { await joiner.join(ssid: ssid, passphrase: saved, isWEP: method == .wep, hidden: hidden) }
                } label: {
                    Label("Connect with the saved password", systemImage: "wifi")
                }
            }
            Button {
                passphrase = saved ?? ""
                pendingJoin = PendingJoin(ssid: ssid, isWEP: method == .wep, hidden: hidden)
            } label: {
                Label(saved == nil ? "Enter password & connect" : "Use another password", systemImage: saved == nil ? "wifi" : "key")
            }
        }
    }

    /// What the ✨ button asks: this one network by name and BSSID, so the assistant's Wi‑Fi list puts it first,
    /// plus the traffic captured on it when the sensor still holds that capture.
    // MARK: Capture traffic → .pcap

    @ViewBuilder
    private func captureSection(_ network: TrackedNetwork) -> some View {
        let channel = network.latest.channel
        // A capture started from another network's card is not this one's to show.
        let mine = link.rawCaptureNetwork == network.id
        let support = link.captureSupport(channel: channel)
        Section {
            if mine, link.rawCapturing {
                HStack(spacing: 10) {
                    ProgressView()
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Capturing on channel \(channel)…")
                        Text("\(link.rawFrameCount) frames · \(captureBytes(link.rawByteCount))")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                Button(role: .destructive) {
                    link.stopRawCapture()
                } label: {
                    Label("Stop capture", systemImage: "stop.circle")
                }
            } else if mine, link.rawFrameCount > 0 {
                InfoRow("Captured") {
                    Text("\(link.rawFrameCount) frames · \(captureBytes(link.rawByteCount))")
                }
                Button {
                    decoding = PcapView.Capture(name: captureFileName(), data: link.rawCapturePcap())
                } label: {
                    Label("Decode", systemImage: "doc.text.magnifyingglass")
                }
                Button {
                    link.startRawCapture(channel: channel, network: network.id)
                } label: {
                    Label("Capture again", systemImage: "arrow.clockwise")
                }
            } else {
                Button {
                    link.startRawCapture(channel: channel, network: network.id)
                } label: {
                    Label("Capture traffic → save .pcap", systemImage: "dot.radiowaves.left.and.right")
                }
                .disabled(support != .ok)
            }
        } header: {
            Text("Traffic capture")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                switch support {
                case .ok:
                    Text("Pins the sensor to channel \(channel) and streams the frames it hears into a .pcap for Wireshark. Bluetooth is narrow, so this is a short, thinned sample — not a full capture — and it stops on its own near \(SensorLink.rawFrameCap) frames.")
                case .bandNotHeard:
                    Text("This network is on 5 GHz (channel \(channel)), and this sensor hears only 2.4 GHz. Capturing it needs the ESP32-C5 sensor.")
                case .firmwareTooOld(let version):
                    Text("This network is on 5 GHz (channel \(channel)). The sensor's firmware \(version) captures on 2.4 GHz only: update the sensor to firmware \(SensorLink.fiveGHzCaptureFirmware) or newer.")
                }
                if mine, link.rawDropped {
                    Text("Some frames were dropped on the way over Bluetooth, so there are gaps.")
                }
            }
        }
    }

    private func captureBytes(_ count: Int) -> String {
        Int64(count).formatted(.byteCount(style: .file))
    }

    private func captureFileName() -> String {
        "spectra-capture-\(Self.fileStamp.string(from: .now)).pcap"
    }

    private static let fileStamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter
    }()

    private func widthDescription(_ reading: AccessPointReading) -> String {
        switch reading.secondaryChannel {
        case .none: Units.megahertz(20)
        case .above: String(localized: "\(Units.megahertz(40)) (secondary above)")
        case .below: String(localized: "\(Units.megahertz(40)) (secondary below)")
        }
    }

    private struct Note {
        var text: String
        var isWarning = true
    }

    private func notes(for network: TrackedNetwork) -> [Note] {
        let reading = network.latest
        var notes: [Note] = []
        switch reading.auth.strength {
        case .none:
            notes.append(Note(text: String(localized: "Open network: traffic is not encrypted, anyone nearby can read it.")))
        case .weak:
            notes.append(Note(text: String(localized: "\(reading.auth.name) is obsolete and can be broken in minutes.")))
        default:
            break
        }
        if reading.capabilities.contains(.wps) {
            notes.append(Note(text: String(localized: "WPS is on. Its PIN method is a known weak point; turn it off if this is your router.")))
        }
        if reading.groupCipher == .tkip || reading.pairwiseCipher == .tkip {
            notes.append(Note(text: String(localized: "TKIP is enabled for old devices and weakens the whole network.")))
        }
        if reading.bssid.isLocallyAdministered {
            notes.append(Note(
                text: String(localized: "Randomised address: usually a phone hotspot, a guest network or a mesh node."),
                isWarning: false
            ))
        }
        if network.revealedSSID != nil {
            notes.append(Note(
                text: String(localized: "Hidden SSID revealed from a probe response or association frame — the AP hides its name from beacons."),
                isWarning: false
            ))
        }
        return notes
    }
}

struct SignalHistoryChart: View {
    var samples: [TrackedNetwork.Sample]
    var color: Color

    var body: some View {
        Chart(samples, id: \.date) { sample in
            AreaMark(
                x: .value("Time", sample.date),
                yStart: .value("Floor", -100),
                yEnd: .value("Signal", sample.rssi)
            )
            .foregroundStyle(
                LinearGradient(colors: [color.opacity(0.35), color.opacity(0.02)], startPoint: .top, endPoint: .bottom)
            )
            .interpolationMethod(.monotone)

            LineMark(x: .value("Time", sample.date), y: .value("Signal", sample.rssi))
                .foregroundStyle(color)
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
                .interpolationMethod(.monotone)
        }
        .chartYScale(domain: -100 ... -20)
        .chartYAxis {
            AxisMarks(position: .leading, values: [-90, -70, -50, -30])
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine()
                // Anchored at the trailing edge so the newest label, right
                // at the end of the plot, is not clipped.
                AxisValueLabel(format: .dateTime.hour().minute().second(), anchor: .topTrailing)
            }
        }
        .overlay {
            if samples.count < 2 {
                Text("The graph fills in as scans come in")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
