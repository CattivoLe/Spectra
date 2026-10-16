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
                VStack(alignment: .leading, spacing: 14) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("\(reading.rssi)")
                            .font(.system(size: 52, weight: .semibold, design: .rounded).monospacedDigit())
                            .contentTransition(.numericText())
                        Text("dBm").font(.title3).foregroundStyle(.secondary)
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(network.isInRange ? quality.label : "Out of range")
                                .font(.headline)
                                .foregroundStyle(network.isInRange ? quality.color : .secondary)
                            Text("avg \(network.averageRSSI) · min \(network.weakestRSSI) · max \(network.strongestRSSI)")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
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
                LabeledContent("Name", value: reading.displayName)
                LabeledContent("BSSID") {
                    Text(reading.bssid.description)
                        .monospaced()
                        .textSelection(.enabled)
                }
                LabeledContent(
                    "Address type",
                    value: reading.bssid.isLocallyAdministered
                        ? String(localized: "Randomised")
                        : String(localized: "Maker-assigned (OUI \(reading.bssid.oui))")
                )
                if let country = reading.countryCode {
                    LabeledContent("Country", value: Locale.current.localizedString(forRegionCode: country) ?? country)
                }
            }

            Section("Radio") {
                LabeledContent("Channel", value: "\(reading.channel)")
                LabeledContent("Frequency", value: "\(reading.frequencyMHz) MHz")
                LabeledContent("Band", value: reading.band.rawValue)
                LabeledContent("Width", value: widthDescription(reading))
                LabeledContent("Standards", value: reading.capabilities.standards)
                LabeledContent("Generation", value: reading.capabilities.generation)
                if reading.capabilities.contains(.ftmResponder) {
                    LabeledContent("Fine Timing Measurement", value: String(localized: "Supported"))
                }
            }

            Section("Security") {
                LabeledContent("Authentication", value: reading.auth.name)
                LabeledContent("Pairwise cipher", value: reading.pairwiseCipher.name)
                LabeledContent("Group cipher", value: reading.groupCipher.name)
                LabeledContent("WPS", value: reading.capabilities.contains(.wps) ? String(localized: "On") : String(localized: "Off"))
            }

            Section("Observed") {
                LabeledContent("First seen") { Text(network.firstSeen, style: .time) }
                LabeledContent("Last seen") { Text(network.lastSeen, style: .relative) }
                LabeledContent("Readings", value: "\(network.samples.count)")
            }
        }
        .navigationTitle(reading.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func widthDescription(_ reading: AccessPointReading) -> String {
        switch reading.secondaryChannel {
        case .none: "20 MHz"
        case .above: String(localized: "40 MHz (secondary above)")
        case .below: String(localized: "40 MHz (secondary below)")
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
                AxisValueLabel(format: .dateTime.hour().minute().second())
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
