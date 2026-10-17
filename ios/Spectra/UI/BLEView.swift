import SwiftUI

struct BLEView: View {
    @Environment(BLEScanner.self) private var scanner
    @Environment(HistoryStore.self) private var history
    @Environment(\.scenePhase) private var scenePhase
    @State private var search = ""
    @AppStorage("bleSort") private var sort: Sort = .signal
    @AppStorage("bleScope") private var scope: Scope = .all
    @AppStorage("bleShowOutOfRange") private var showOutOfRange = true

    enum Sort: String, CaseIterable, Identifiable {
        case signal, name, recent
        var id: Self { self }

        var label: LocalizedStringKey {
            switch self {
            case .signal: "Signal"
            case .name: "Name"
            case .recent: "Recently seen"
            }
        }
    }

    enum Scope: String, CaseIterable, Identifiable {
        case all, named, apple
        var id: Self { self }

        var label: LocalizedStringKey {
            switch self {
            case .all: "All devices"
            case .named: "With a name"
            case .apple: "Apple"
            }
        }
    }

    private var visible: [BLEDevice] {
        let now = scanner.now
        let query = search.trimmingCharacters(in: .whitespaces)
        return scanner.devices.values.filter { device in
            (showOutOfRange || device.isNearby(at: now))
                && matches(device, scope: scope)
                && (query.isEmpty
                    || device.displayName.localizedStandardContains(query)
                    || device.subtitle.localizedStandardContains(query)
                    || device.id.uuidString.localizedStandardContains(query))
        }
        .sorted { a, b in
            let aNear = a.isNearby(at: now)
            let bNear = b.isNearby(at: now)
            if aNear != bNear { return aNear }
            switch sort {
            case .signal:
                if a.orderRSSI != b.orderRSSI { return a.orderRSSI > b.orderRSSI }
            case .name:
                let order = a.displayName.localizedStandardCompare(b.displayName)
                if order != .orderedSame { return order == .orderedAscending }
            case .recent:
                let aBucket = Int(a.lastSeen.timeIntervalSinceReferenceDate) / 10
                let bBucket = Int(b.lastSeen.timeIntervalSinceReferenceDate) / 10
                if aBucket != bBucket { return aBucket > bBucket }
            }
            return a.id.uuidString < b.id.uuidString
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if !visible.isEmpty {
                    Section {
                        ForEach(visible) { device in
                            NavigationLink(value: device.id) {
                                BLEDeviceRow(device: device, now: scanner.now)
                            }
                        }
                    } header: {
                        Text("\(scanner.nearbyCount) nearby")
                    } footer: {
                        Text("iOS hears Bluetooth Low Energy advertisements only, and it hides the hardware address.")
                    }
                }
            }
            .overlay {
                if scanner.devices.isEmpty {
                    emptyState
                } else if visible.isEmpty, !search.isEmpty {
                    ContentUnavailableView.search(text: search)
                } else if visible.isEmpty {
                    ContentUnavailableView(
                        "No devices",
                        systemImage: "wave.3.right",
                        description: Text("Nothing in this filter is nearby.")
                    )
                }
            }
            .searchable(text: $search, prompt: Text("Name, company or service"))
            .refreshable {
                scanner.start()
                try? await Task.sleep(for: .seconds(1))
            }
            .navigationTitle("Bluetooth")
            .navigationDestination(for: UUID.self) { id in
                BLEDeviceDetailView(id: id)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    BLEStatusBar()
                }
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save", systemImage: "square.and.arrow.down") {
                        history.saveBluetooth(Array(scanner.devices.values), source: "This iPhone")
                    }
                    .disabled(scanner.devices.isEmpty)
                }
                if #available(iOS 26, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Sort by", selection: $sort) {
                            ForEach(Sort.allCases) { Text($0.label).tag($0) }
                        }
                        Picker("Show", selection: $scope) {
                            ForEach(Scope.allCases) { Text($0.label).tag($0) }
                        }
                        Toggle("Show out of range", isOn: $showOutOfRange)
                        Divider()
                        Button(scanner.isScanning ? "Stop" : "Scan now", systemImage: scanner.isScanning ? "stop" : "arrow.clockwise") {
                            if scanner.isScanning { scanner.stop() } else { scanner.start() }
                        }
                        .disabled(scannerUnavailable)
                        if !scanner.devices.isEmpty {
                            Button("Clear list", systemImage: "trash", role: .destructive) {
                                scanner.clear()
                            }
                        }
                    } label: {
                        Label("Options", systemImage: "line.3.horizontal.decrease")
                    }
                }
            }
        }
        .onAppear { scanner.resumeUnlessPaused() }
        .onDisappear { scanner.leave() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background {
                scanner.suspend()
            } else if phase == .active {
                scanner.resumeIfWanted()
            }
        }
    }

    private var scannerUnavailable: Bool {
        if case .unavailable = scanner.state { return true }
        return false
    }

    private func matches(_ device: BLEDevice, scope: Scope) -> Bool {
        switch scope {
        case .all: true
        case .named: device.hasOwnName
        case .apple: device.companyID == BLEAdvertisement.appleCompany
        }
    }

    @ViewBuilder private var emptyState: some View {
        switch scanner.state {
        case .unavailable(let reason):
            ContentUnavailableView {
                Label(unavailableTitle(reason), systemImage: "wave.3.right.circle")
            } description: {
                Text(unavailableText(reason))
            } actions: {
                if reason == .unauthorized, let url = URL(string: UIApplication.openSettingsURLString) {
                    Link("Open Settings", destination: url)
                        .buttonStyle(.borderedProminent)
                }
            }
        case .scanning:
            ContentUnavailableView {
                Label("Listening", systemImage: "wave.3.right")
            } description: {
                Text("Devices appear as their advertisements arrive.")
            } actions: {
                ProgressView()
            }
        case .idle:
            ContentUnavailableView {
                Label("Bluetooth", systemImage: "wave.3.right")
            } description: {
                Text("Phones, headphones, sensors and other devices that are advertising nearby.")
            } actions: {
                Button("Scan", action: scanner.start)
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    private func unavailableTitle(_ reason: BLEScanner.Availability) -> LocalizedStringKey {
        switch reason {
        case .starting: "Bluetooth"
        case .poweredOff: "Bluetooth is off"
        case .unauthorized: "Bluetooth"
        case .unsupported: "No Bluetooth"
        }
    }

    private func unavailableText(_ reason: BLEScanner.Availability) -> String {
        switch reason {
        case .starting: String(localized: "Starting Bluetooth…")
        case .poweredOff: String(localized: "Bluetooth is off. Turn it on in Control Centre.")
        case .unauthorized: String(localized: "Spectra is not allowed to use Bluetooth.")
        case .unsupported: String(localized: "This device has no Bluetooth Low Energy.")
        }
    }
}

/// Scan state in the Bluetooth navigation bar, the same glass capsule as the
/// sensor status on Networks. Tapping starts or stops the scan.
struct BLEStatusBar: View {
    @Environment(BLEScanner.self) private var scanner
    @State private var taps = 0

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 6) {
                Image(systemName: "wave.3.right")
                    .symbolEffect(.variableColor.iterative, isActive: scanner.isScanning)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: 168, alignment: .leading)
        }
        .disabled(unavailable)
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
        .accessibilityLabel("\(title), \(detail)")
    }

    private func toggle() {
        taps += 1
        if scanner.isScanning {
            scanner.stop()
        } else if !unavailable {
            scanner.start()
        }
    }

    private var unavailable: Bool {
        if case .unavailable = scanner.state { return true }
        return false
    }

    private var title: String {
        switch scanner.state {
        case .scanning: String(localized: "Scanning…")
        case .idle: String(localized: "Paused")
        case .unavailable(let reason):
            switch reason {
            case .starting: String(localized: "Starting Bluetooth…")
            case .poweredOff: String(localized: "Bluetooth is off")
            case .unauthorized, .unsupported: String(localized: "Bluetooth")
            }
        }
    }

    private var detail: String {
        if case .unavailable(let reason) = scanner.state, reason != .starting {
            switch reason {
            case .poweredOff: return String(localized: "Turn it on")
            case .unauthorized: return String(localized: "Permission is off")
            case .unsupported: return String(localized: "No radio")
            case .starting: break
            }
        }
        let nearby = scanner.nearbyCount
        let total = scanner.devices.count
        if nearby == total {
            return String(localized: "\(nearby) nearby")
        }
        return String(localized: "\(nearby) nearby · \(total) seen")
    }
}

struct BLEDeviceRow: View {
    var device: BLEDevice
    var now: Date

    private var nearby: Bool { device.isNearby(at: now) }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: device.systemImage)
                .font(.title3)
                .foregroundStyle(nearby ? SignalQuality(rssi: device.rssi).color : .secondary)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(device.displayName)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                        .layoutPriority(1)
                    if device.isSpectraSensor {
                        badge("Sensor", color: .blue)
                    }
                }
                Group {
                    if nearby {
                        Text(device.subtitle)
                    } else {
                        Text("Out of range · \(device.lastSeen, style: .relative)")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .monospacedDigit()
                .lineLimit(2)
            }

            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 4) {
                Text("\(device.rssi)")
                    .font(.callout.monospacedDigit().weight(.semibold))
                    .foregroundStyle(nearby ? .primary : .tertiary)
                    .accessibilityLabel("\(device.rssi) dBm")
                if nearby {
                    Sparkline(values: device.samples.suffix(24).map(\.rssi), color: SignalQuality(rssi: device.rssi).color)
                        .frame(width: 40, height: 14)
                        .accessibilityHidden(true)
                }
            }
        }
        .opacity(nearby ? 1 : 0.6)
        .contextMenu {
            Button("Copy name", systemImage: "doc.on.doc") {
                UIPasteboard.general.string = device.displayName
            }
            Button("Copy identifier", systemImage: "number") {
                UIPasteboard.general.string = device.id.uuidString
            }
        }
    }

    private func badge(_ text: LocalizedStringKey, color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .background(color, in: .capsule)
            .lineLimit(1)
            .fixedSize()
            .layoutPriority(-1)
    }
}
