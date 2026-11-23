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
    @State private var showRadar = false

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

            if let assessment = TrackerDetector.assess(device, now: snapshot == nil ? scanner.now : device.lastSeen) {
                trackerSection(device, assessment)
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
                            if let beacon = BeaconDecode.decode(uuid: item.uuid, data: item.data) {
                                Text(beacon.detail.map { "\(beacon.title) · \($0)" } ?? beacon.title)
                                    .font(.caption)
                                    .textSelection(.enabled)
                                    .fixedSize(horizontal: false, vertical: true)
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

            if snapshot == nil, device.isConnectable != false {
                gattSection(device)
            }

            Section("Observed") {
                InfoRow("First seen") { Text(device.firstSeen.formatted(date: .abbreviated, time: .shortened)) }
                InfoRow("Last seen") { Text(device.lastSeen, style: .relative) }
                InfoRow("Packets", value: "\(device.packetCount)")
            }
        }
        .navigationTitle(device.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { scanner.disconnectGATT() }
        .fullScreenCover(isPresented: $showRadar) { RadarView(device: device) }
        .toolbar {
            if snapshot == nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Find", systemImage: "dot.radiowaves.left.and.right") { showRadar = true }
                }
            }
        }
        // A summary of the device, before a GATT read too: `lastRead` outlives the link this screen drops and joins it.
        .summaryButton(isShown: snapshot == nil, subject: { .bluetooth(id: device.id.uuidString) })
    }

    /// What kind of tag this is and whether it looks like it is travelling with the user.
    private func trackerSection(_ device: BLEDevice, _ assessment: TrackerAssessment) -> some View {
        let color: Color = switch assessment.level {
        case .alert: .red
        case .watch: .orange
        case .info: .secondary
        }
        let minutes = Int(assessment.presentFor / 60)
        return Section {
            InfoRow("Kind", value: assessment.kind.label)
            if let owner = assessment.ownerLabel {
                InfoRow("Owner", value: owner)
            }
            if let battery = assessment.batteryLabel {
                InfoRow("Battery", value: battery)
            }
            InfoRow("Near you for") {
                Text(minutes >= 1 ? String(localized: "\(minutes) min") : String(localized: "under a minute"))
            }
            switch assessment.level {
            case .alert:
                Label {
                    Text("This tag has lost its owner and has stayed within range of you. Move to another place and come back to this screen: if it is still here, look for it with Find.")
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                }
                .foregroundStyle(color)
                .font(.subheadline)
            case .watch:
                Text("Away from its owner, but only just nearby. Nothing to do unless it stays with you.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            case .info:
                Text(assessment.separated == false
                     ? "Its owner is nearby: someone's keys or luggage, not a tag planted on you."
                     : "Out of range now.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        } header: {
            HStack {
                Text("Tracker")
                Spacer()
                if assessment.level == .alert {
                    Text("May be travelling with you")
                        .foregroundStyle(color)
                }
            }
        } footer: {
            Text("A phone cannot tell a tag in your bag from one in the bag next to you. Only its staying with you as you move does.")
        }
    }

    @ViewBuilder
    private func gattSection(_ device: BLEDevice) -> some View {
        let bound = scanner.gattDeviceID == device.id
        Section {
            switch bound ? scanner.gattState : .idle {
            case .idle:
                Button("Connect and read services", systemImage: "antenna.radiowaves.left.and.right") {
                    scanner.connectGATT(device.id)
                }
            case .connecting:
                Label("Connecting…", systemImage: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(.secondary)
            case .discovering:
                Label("Reading services…", systemImage: "list.bullet.rectangle")
                    .foregroundStyle(.secondary)
            case .connected:
                Button("Disconnect", systemImage: "xmark.circle", role: .destructive) {
                    scanner.disconnectGATT()
                }
            case .ended:
                VStack(alignment: .leading, spacing: 8) {
                    Label("The device closed the connection. What it gave is kept below.", systemImage: "link.badge.plus")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Connect again", systemImage: "arrow.clockwise") {
                        scanner.connectGATT(device.id)
                    }
                }
            case .failed(let message):
                VStack(alignment: .leading, spacing: 6) {
                    Label(message, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                        .font(.subheadline)
                    Button("Try again", systemImage: "arrow.clockwise") {
                        scanner.connectGATT(device.id)
                    }
                }
            }

            if bound {
                ForEach(scanner.gattServices) { service in
                    gattServiceRow(service)
                }
            }
        } header: {
            Text("Services (GATT)")
        } footer: {
            Text("Connects to the device to list its services and read the values it exposes. Many devices allow only a brief connection. Short codes are standard Bluetooth ones; long UUIDs belong to the maker, who rarely publishes what they mean, so only the raw bytes can be shown.")
        }
    }

    private func gattServiceRow(_ service: GATTService) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(service.name ?? String(localized: "Vendor service"))
                    .font(.headline)
                Text(service.uuid)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            ForEach(service.characteristics) { characteristic in
                characteristicRow(characteristic)
            }
        }
        .padding(.vertical, 4)
    }

    private func characteristicRow(_ characteristic: GATTCharacteristic) -> some View {
        let name = BLENumbers.characteristicName(uuid: characteristic.uuid)
        return VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(name ?? String(localized: "Vendor characteristic"))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(name == nil ? .secondary : .primary)
                Spacer(minLength: 8)
                if !characteristic.properties.isEmpty {
                    Text(characteristic.properties)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.trailing)
                        .fixedSize()
                }
            }
            Text(characteristic.uuid)
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            if let value = characteristic.value, !value.isEmpty {
                if let decoded = GATTValue.decode(uuid: characteristic.uuid, bytes: value) {
                    Text(linkText(decoded))
                        .font(.subheadline)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text(gattValueText(value))
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.leading, 12)
        .overlay(alignment: .leading) {
            Capsule()
                .fill(.quaternary)
                .frame(width: 3)
        }
    }

    /// Hex, plus an ASCII rendering when the bytes look like text.
    private func gattValueText(_ data: Data) -> String {
        let hex = bleHex(data)
        let printable = data.allSatisfy { $0 >= 32 && $0 < 127 }
        if printable, let text = String(data: data, encoding: .ascii), !text.isEmpty {
            return "\(hex)  “\(text)”"
        }
        return hex
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
