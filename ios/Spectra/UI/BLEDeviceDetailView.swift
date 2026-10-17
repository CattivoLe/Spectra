import Charts
import SwiftUI

struct BLEDeviceDetailView: View {
    private var lookup: UUID?
    private var snapshot: BLEDevice?

    init(id: UUID) {
        lookup = id
    }

    /// A saved peripheral, shown with the same card as a live one.
    init(device: BLEDevice) {
        snapshot = device
    }

    @Environment(BLEScanner.self) private var scanner

    var body: some View {
        if let device = snapshot ?? lookup.flatMap({ scanner.devices[$0] }) {
            content(device)
        } else {
            ContentUnavailableView("Device cleared", systemImage: "wave.3.right")
        }
    }

    private func content(_ device: BLEDevice) -> some View {
        let nearby = device.isNearby(at: snapshot == nil ? scanner.now : device.lastSeen)
        let quality = SignalQuality(rssi: device.rssi)

        return List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 14) {
                        Image(systemName: device.systemImage)
                            .font(.system(size: 34))
                            .foregroundStyle(nearby ? quality.color : .secondary)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(device.displayName)
                                .font(.title2.weight(.semibold))
                                .fixedSize(horizontal: false, vertical: true)
                            HStack(spacing: 6) {
                                if device.isSpectraSensor {
                                    badge("Sensor", color: .blue)
                                }
                                if let company = device.companyName, company != device.displayName {
                                    Text(company)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    HStack(alignment: .firstTextBaseline) {
                        Text("\(device.rssi)")
                            .font(.system(size: 52, weight: .semibold, design: .rounded).monospacedDigit())
                            .contentTransition(.numericText())
                        Text("dBm").font(.title3).foregroundStyle(.secondary)
                        Spacer()
                        Text(nearby ? quality.label : "Out of range")
                            .font(.headline)
                            .foregroundStyle(nearby ? quality.color : .secondary)
                    }
                    Text("avg \(device.averageRSSI) · min \(device.weakestRSSI) · max \(device.strongestRSSI)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    BLESignalChart(samples: device.samples, color: quality.color)
                        .frame(height: 140)
                }
                .padding(.vertical, 6)
                .animation(.default, value: device.rssi)
            }

            Section {
                if device.isConnectable != nil {
                    InfoRow("Connection", value: device.isConnectable == true
                        ? String(localized: "Connectable")
                        : String(localized: "Not connectable"))
                }
                if let txPower = device.txPower {
                    InfoRow("TX power") {
                        Text("\(txPower) dBm")
                    }
                }
                InfoRow("Identifier") {
                    Text(device.id.uuidString)
                        .monospaced()
                        .textSelection(.enabled)
                }
            } header: {
                Text("Identity")
            } footer: {
                Text("iOS assigns this identifier. It is not the Bluetooth address, and another app sees a different one.")
            }

            if !device.apple.isEmpty {
                Section("Apple") {
                    ForEach(Array(device.apple.enumerated()), id: \.offset) { _, message in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(message.label)
                            if let detail = message.detail {
                                Text(detail)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                }
            }

            if !device.services.isEmpty {
                Section("Services") {
                    ForEach(device.services) { service in
                        serviceRow(service)
                    }
                }
            }

            if !device.solicitedServices.isEmpty {
                Section("Looking for") {
                    ForEach(device.solicitedServices) { service in
                        serviceRow(service)
                    }
                }
            }

            if !device.serviceData.isEmpty {
                Section("Service data") {
                    ForEach(device.serviceData) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name ?? item.uuid)
                                .font(.body.weight(.medium))
                            if item.name != nil {
                                Text(item.uuid)
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                            Text(bleHex(item.data))
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                        }
                    }
                }
            }

            if !device.manufacturerPayload.isEmpty {
                Section("Manufacturer data") {
                    Text(bleHex(device.manufacturerPayload))
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Section("Observed") {
                InfoRow("First seen") { Text(device.firstSeen, style: .time) }
                InfoRow("Last seen") { Text(device.lastSeen, style: .relative) }
                InfoRow("Packets", value: "\(device.packetCount)")
            }
        }
        .navigationTitle(device.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func serviceRow(_ service: BLEServiceRef) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(service.name ?? service.uuid)
            if service.name != nil {
                Text(service.uuid)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
    }

    private func badge(_ text: LocalizedStringKey, color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color, in: .capsule)
    }
}

private struct BLESignalChart: View {
    var samples: [BLEDevice.Sample]
    var color: Color

    var body: some View {
        Chart(samples) { sample in
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
        .chartYScale(domain: -100 ... -15)
        .chartYAxis {
            AxisMarks(position: .leading, values: [-90, -70, -50, -30])
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine()
                AxisValueLabel(format: .dateTime.hour().minute().second(), anchor: .topTrailing)
            }
        }
        .overlay {
            if samples.count < 2 {
                Text("The graph fills in as advertisements arrive.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
