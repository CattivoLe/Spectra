import Charts
import SwiftUI

struct NetworkDetailView: View {
    var id: BSSID

    @Environment(NetworkStore.self) private var store

    var body: some View {
        if let network = store.networks[id] {
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
                if let country = reading.countryCode {
                    InfoRow("Country", value: Locale.current.localizedString(forRegionCode: country) ?? country)
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
            }

            Section("Security") {
                InfoRow("Authentication", value: reading.auth.name)
                InfoRow("Pairwise cipher", value: reading.pairwiseCipher.name)
                InfoRow("Group cipher", value: reading.groupCipher.name)
                InfoRow("WPS", value: reading.capabilities.contains(.wps) ? String(localized: "On") : String(localized: "Off"))
            }

            // Clients (stations associated with this AP).
            if !network.clients.isEmpty {
                Section {
                    ForEach(network.clients) { client in
                        ClientRow(client: client)
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
                    if let duration = store.lastScanDuration,
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
                InfoRow("First seen") { Text(network.firstSeen, style: .time) }
                InfoRow("Last seen") { Text(network.lastSeen, style: .relative) }
                InfoRow("Readings", value: "\(network.samples.count)")
            }
        }
        .navigationTitle(network.revealedSSID ?? reading.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }

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
