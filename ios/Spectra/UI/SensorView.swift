import SwiftUI
import UniformTypeIdentifiers

struct SensorView: View {
    @Environment(SensorLink.self) private var link
    @Environment(NetworkStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var pickingFirmware = false
    @State private var pendingFirmware: PendingFirmware?
    @State private var installTaps = 0
    @State private var showLicenses = false

    struct PendingFirmware: Identifiable {
        let id = UUID()
        var name: String
        var data: Data
        var version: String?
    }

    var body: some View {
        @Bindable var link = link

        NavigationStack {
            Form {
                Section {
                    status
                }

                if let update = link.firmwareUpdate {
                    Section {
                        firmwareStatus(update)
                    }
                }

                switch link.state {
                case .searching:
                    Section {
                        if link.discovered.isEmpty {
                            HStack(spacing: 10) {
                                ProgressView()
                                Text("Looking for Cardputers running Spectra…")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        ForEach(link.discovered) { sensor in
                            Button {
                                link.connect(to: sensor.id)
                            } label: {
                                HStack {
                                    Image(systemName: "cpu")
                                    Text(sensor.name)
                                    Spacer()
                                    SignalGlyph(rssi: sensor.rssi + 20)
                                }
                            }
                        }
                    } header: {
                        Text("Nearby sensors")
                    }

                case .connected, .demo:
                    Section("Scanning") {
                        Picker("Automatic scan", selection: $link.liveInterval) {
                            ForEach(SensorLink.liveIntervals, id: \.self) { seconds in
                                Text(SensorLink.intervalLabel(seconds)).tag(seconds)
                            }
                        }
                        if let duration = store.lastScanDuration {
                            InfoRow("Last sweep", value: duration.formatted(.units(allowed: [.seconds, .milliseconds], width: .narrow)))
                        }
                        InfoRow("Scans this session", value: "\(store.scanCount)")
                    }

                    // Older firmware does not say what the board has: every mode is offered, as before.
                    let outputs = link.info?.outputs ?? 0b11
                    if outputs != 0 {
                        Section {
                            switch outputs {
                            case 0b10:
                                // The ESP32-C5 board: an RGB LED and no speaker.
                                Toggle("Blink the LED on findings", isOn: alertBit(SensorLink.AlertMode.lightOnly.rawValue))
                            case 0b01:
                                Toggle("Beep on findings", isOn: alertBit(SensorLink.AlertMode.soundOnly.rawValue))
                            default:
                                Picker("Alerts on sensor", selection: $link.alertMode) {
                                    ForEach(SensorLink.AlertMode.allCases) { mode in
                                        Text(mode.label).tag(mode)
                                    }
                                }
                            }
                        } footer: {
                            if outputs & 0b10 != 0 {
                                Text("The LED blinks red for a threat, yellow for a warning, orange for a tracker that keeps showing up, green for an open network, blue for a watched Bluetooth device and purple for a watched network.")
                            }
                        }
                    }

                    if let info = link.info {
                        Section {
                            InfoRow("Firmware") {
                                HStack(spacing: 6) {
                                    Text(info.fw)
                                    if firmwareUpToDate {
                                        Image(systemName: "checkmark.seal.fill")
                                            .foregroundStyle(.green)
                                            .accessibilityLabel("Up to date")
                                    }
                                }
                            }
                            InfoRow("Reported interval", value: SensorLink.intervalLabel(info.interval))
                            InfoRow("Updates", value: info.ota ? String(localized: "From the app") : String(localized: "Not from the app"))
                            if let level = link.batteryLevel {
                                InfoRow("Battery", value: batteryText(level, charging: link.isCharging == true))
                            }
                            InfoRow("Chip", value: info.chip)
                            InfoRow("Bands", value: info.bands.compactMap(Double.init).map(Units.gigahertz).formatted(.list(type: .and)))
                            if info.proto != SensorProtocol.version {
                                Label("This firmware speaks protocol \(info.proto); the app expects \(SensorProtocol.version). Update the one that is older.", systemImage: "exclamationmark.triangle.fill")
                                    .symbolRenderingMode(.multicolor)
                            }
                            if case .connected = link.state, info.ota, link.firmwareUpdate?.isRunning != true {
                                // The app carries an image per board; only the one for this sensor's chip is offered.
                                if let bundled = BundledFirmware.matching(chip: info.chip) {
                                    if let version = bundled.version, version == info.fw {
                                        Button("Reinstall \(version)", systemImage: "arrow.clockwise") {
                                            offer(PendingFirmware(name: bundled.fileName, data: bundled.data, version: version))
                                        }
                                    } else {
                                        Button(
                                            bundled.version.map { String(localized: "Install firmware \($0)") }
                                                ?? String(localized: "Install included firmware"),
                                            systemImage: "arrow.down.circle"
                                        ) {
                                            offer(PendingFirmware(name: bundled.fileName, data: bundled.data, version: bundled.version))
                                        }
                                    }
                                } else {
                                    // Only a build without the image needs a file from elsewhere.
                                    Button("Choose a firmware file…", systemImage: "folder") {
                                        pickingFirmware = true
                                    }
                                }
                            }
                        } header: {
                            Text("Device")
                        } footer: {
                            if case .connected = link.state, !info.ota {
                                Text("This firmware cannot take updates from the app. Flash 0.6.0 or newer once over USB or the launcher; after that, updates install from here.")
                            } else if case .connected = link.state, info.ota, !BundledFirmware.all.isEmpty, BundledFirmware.matching(chip: info.chip) == nil {
                                Text("The firmware inside the app is for \(BundledFirmware.all.compactMap(\.chip).formatted(.list(type: .and))), and this sensor is \(info.chip). Pick its own firmware.bin from Files, or flash it over USB.")
                            } else if case .connected = link.state, info.ota, BundledFirmware.matching(chip: info.chip) != nil {
                                Text("The app carries the firmware.bin from its last build. Rebuild the iOS app after pio run and the new version shows up here.")
                            } else if case .connected = link.state, info.ota {
                                Text("This build of the app has no firmware inside. Run pio run before building the app, or pick firmware.bin from Files.")
                            }
                        }
                    }

                    if link.info?.lora == true || link.capFix != nil {
                        Section {
                            InfoRow("Module", value: "Cap LoRa-1262")
                            InfoRow("GPS", value: gpsLine)
                            if case .connected = link.state {
                                if link.loraScanning {
                                    HStack(spacing: 10) {
                                        ProgressView()
                                        Text(link.loraStatus)
                                            .foregroundStyle(.secondary)
                                            .monospacedDigit()
                                    }
                                } else {
                                    Button("Scan LoRa", systemImage: "antenna.radiowaves.left.and.right") {
                                        link.scanLoRa()
                                    }
                                }
                            }
                            if link.loraDevices.isEmpty && !link.loraScanning {
                                Text("No LoRa devices yet. LoRa transmitters speak in short bursts, so a quiet pass is common indoors.")
                                    .foregroundStyle(.secondary)
                            }
                            ForEach(Array(link.loraDevices.enumerated()), id: \.offset) { _, device in
                                HStack(alignment: .firstTextBaseline) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(loraTitle(device))
                                        Text(device.detail)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 8)
                                    Text("\(device.rssi)")
                                        .font(.body.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                }
                            }
                        } header: {
                            Text("LoRa")
                        } footer: {
                            Text("The Cardputer's Cap LoRa-1262 listens on EU868 and RU864 channels. A side-button save keeps the list and the GPS fix for when the phone reconnects.")
                        }
                    }

                default:
                    EmptyView()
                }

// Пока не нужен
//                Section {
//                    Toggle("Demo sensor", isOn: Binding(
//                        get: { link.state == .demo },
//                        set: { link.setDemo($0) }
//                    ))
//                } footer: {
//                    Text("Made-up networks with drifting signal, for trying the app without a Cardputer.")
//                }

                Section("About the sensor") {
                    Text("iPhone apps cannot list nearby Wi-Fi networks, so Spectra uses a sensor board as its radio. A Cardputer and a WROOM-32 hear 2.4 GHz. An ESP32-C5 also hears 5 GHz. A board with no screen is controlled from the phone.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Sensor")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button("Licenses…", systemImage: "doc.text") { showLicenses = true }
                    } label: {
                        Label("More", systemImage: "ellipsis.circle")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .navigationDestination(isPresented: $showLicenses) {
                LicensesView()
            }
            .sensoryFeedback(trigger: link.firmwareUpdate?.stage) { _, stage in
                switch stage {
                case .succeeded: .success
                case .failed: .error
                default: nil
                }
            }
            .fileImporter(
                isPresented: $pickingFirmware,
                allowedContentTypes: firmwareFileTypes,
                allowsMultipleSelection: false
            ) { result in
                loadFirmware(result)
            }
            .sheet(item: $pendingFirmware) { pending in
                FirmwareInstallSheet(pending: pending, current: link.info?.fw) {
                    installTaps += 1
                    link.installFirmware(pending.data)
                    pendingFirmware = nil
                } cancel: {
                    pendingFirmware = nil
                }
                .presentationDetents([.height(400)])
                .presentationDragIndicator(.visible)
            }
            .sensoryFeedback(.selection, trigger: pendingFirmware?.id)
            .sensoryFeedback(.impact(weight: .medium), trigger: installTaps)
        }
    }

    /// One alert output on or off, keeping the other: a board without a speaker should not wipe the sound
    /// setting the Cardputer will use.
    private func alertBit(_ bit: Int) -> Binding<Bool> {
        Binding(
            get: { link.alertMode.rawValue & bit != 0 },
            set: { on in
                let raw = on ? link.alertMode.rawValue | bit : link.alertMode.rawValue & ~bit
                link.alertMode = SensorLink.AlertMode(rawValue: raw) ?? link.alertMode
            }
        )
    }

    private func offer(_ firmware: PendingFirmware) {
        pendingFirmware = firmware
    }

    /// The connected sensor already runs the firmware this app build carries.
    private var firmwareUpToDate: Bool {
        guard case .connected = link.state,
              let info = link.info, info.ota,
              link.firmwareUpdate?.isRunning != true,
              let version = BundledFirmware.matching(chip: info.chip)?.version
        else { return false }
        return version == info.fw
    }

    private struct BundledFirmware {
        var fileName: String
        var data: Data
        var version: String?
        var chip: String?

        /// Copied into the app by the Xcode build from each board's pio build: firmware.bin (Cardputer),
        /// firmware-esp32-c5.bin, firmware-esp32.bin. Whatever was built before the app is there.
        static let all: [BundledFirmware] = {
            let urls = Bundle.main.urls(forResourcesWithExtension: "bin", subdirectory: nil) ?? []
            return urls
                .filter { $0.lastPathComponent.hasPrefix("firmware") }
                .sorted { $0.lastPathComponent < $1.lastPathComponent }
                .compactMap { url in
                    guard let data = try? Data(contentsOf: url), (try? FirmwareUpdate.validate(data)) != nil else { return nil }
                    return BundledFirmware(fileName: url.lastPathComponent, data: data,
                                           version: FirmwareUpdate.taggedVersion(data), chip: FirmwareUpdate.chipName(data))
                }
        }()

        /// The image built for `chip`. An image whose header names no known chip only goes to a sensor no other
        /// image fits, as the one image older app builds carried did.
        static func matching(chip: String) -> BundledFirmware? {
            all.first { $0.chip == chip } ?? (all.contains { $0.chip != nil } ? nil : all.first)
        }
    }

    private var firmwareFileTypes: [UTType] {
        if let bin = UTType(filenameExtension: "bin") {
            return [.data, bin]
        }
        return [.data]
    }

    private func loadFirmware(_ result: Result<[URL], Error>) {
        switch result {
        case .failure:
            link.failFirmwareUpdate(String(localized: "Could not read the firmware file."))
        case .success(let urls):
            guard let url = urls.first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                link.failFirmwareUpdate(String(localized: "Could not read the firmware file."))
                return
            }
            pendingFirmware = PendingFirmware(name: url.lastPathComponent, data: data, version: FirmwareUpdate.taggedVersion(data))
        }
    }

    @ViewBuilder private func firmwareStatus(_ update: FirmwareUpdateProgress) -> some View {
        switch update.stage {
        case .preparing:
            LabeledContent("Preparing the sensor…") { ProgressView() }
        case .uploading(let sent, let total):
            let fraction = total > 0 ? Double(sent) / Double(total) : 0
            ProgressView(value: fraction) {
                Text("Update firmware")
            } currentValueLabel: {
                Text(byteProgress(sent, total))
            }
        case .finishing:
            LabeledContent("Checking the image…") { ProgressView() }
        case .rebooting:
            LabeledContent("Waiting for the sensor to reboot…") { ProgressView() }
        case .succeeded(let version):
            Label("Firmware installed", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
            if !version.isEmpty {
                InfoRow("Firmware", value: version)
            }
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .symbolRenderingMode(.multicolor)
        }
        if update.isRunning, update.stage != .rebooting {
            Button("Cancel update", role: .cancel) {
                link.cancelFirmwareUpdate()
            }
        } else if !update.isRunning {
            Button("Dismiss") {
                link.dismissFirmwareUpdate()
            }
        }
    }

    private var gpsLine: String {
        guard let fix = link.capFix else { return String(localized: "Searching for satellites") }
        let coords = String(format: "%.5f, %.5f", fix.latitude, fix.longitude)
        return String(format: String(localized: "%@ · %lld satellites"), coords, Int64(fix.satellites))
    }

    private func loraTitle(_ device: SensorProtocol.LoRaDevice) -> String {
        let hex = String(format: "%08X", device.id)
        if device.join { return String(format: String(localized: "Join %@"), hex) }
        if device.lorawan {
            let arrow = device.downlink ? "↓" : "↑"
            return "\(arrow) " + String(format: String(localized: "DevAddr %@"), hex)
        }
        return String(format: String(localized: "Packet %@"), hex)
    }

    private func batteryText(_ level: Int, charging: Bool) -> String {
        if charging {
            String(format: String(localized: "%lld%%, charging"), level)
        } else {
            "\(level)%"
        }
    }

    private func byteProgress(_ sent: Int, _ total: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return String(
            format: String(localized: "%@ of %@"),
            formatter.string(fromByteCount: Int64(sent)),
            formatter.string(fromByteCount: Int64(total))
        )
    }

    @ViewBuilder private var status: some View {
        switch link.state {
        case .bluetoothUnavailable(let reason):
            Label(unavailableText(reason), systemImage: "exclamationmark.triangle.fill")
                .symbolRenderingMode(.multicolor)
            if reason == .unauthorized, let url = URL(string: UIApplication.openSettingsURLString) {
                Link("Open Settings", destination: url)
            }

        case .idle:
            Label("Not connected", systemImage: "antenna.radiowaves.left.and.right.slash")
            Button("Find sensor", systemImage: "magnifyingglass") {
                link.startSearching()
            }

        case .searching:
            Label("Searching…", systemImage: "dot.radiowaves.left.and.right")
                .symbolEffect(.variableColor.iterative)
            Button("Stop", role: .cancel) {
                link.stopSearching()
            }

        case .connecting(let name):
            HStack(spacing: 12) {
                Label(name, systemImage: "antenna.radiowaves.left.and.right")
                Spacer()
                ProgressView()
                Button("Cancel", systemImage: "xmark.circle.fill", role: .cancel) {
                    link.disconnect()
                }
                .labelStyle(.iconOnly)
                .font(.title2)
                .foregroundStyle(.secondary)
                .buttonStyle(.borderless)
            }

        case .connected(let name):
            HStack(spacing: 12) {
                Label(name, systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Spacer()
                Button("Disconnect", systemImage: "xmark.circle.fill", role: .destructive) {
                    link.disconnect()
                }
                .labelStyle(.iconOnly)
                .font(.title2)
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(.red)
                .buttonStyle(.borderless)
            }

        case .demo:
            Label("Demo sensor", systemImage: "wand.and.stars")
        }
    }

    private func unavailableText(_ reason: SensorLink.Unavailability) -> LocalizedStringKey {
        switch reason {
        case .starting: "Starting Bluetooth…"
        case .poweredOff: "Bluetooth is off. Turn it on in Control Centre."
        case .unauthorized: "Spectra is not allowed to use Bluetooth."
        case .unsupported: "This device has no Bluetooth Low Energy."
        }
    }
}

/// Asks before sending an image: which version replaces which, how big it
/// is, and that the Cardputer has to stay on and near.
private struct FirmwareInstallSheet: View {
    var pending: SensorView.PendingFirmware
    var current: String?
    var install: () -> Void
    var cancel: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "arrow.down.circle.fill")
                .font(.system(size: 52))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(Color.accentColor)
                .padding(.top, 8)

            VStack(spacing: 6) {
                Text("Install this firmware?")
                    .font(.title3.weight(.semibold))
                versions
                Text(details)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            .multilineTextAlignment(.center)

            Label("The Cardputer reboots when the transfer finishes. Keep it powered and nearby.", systemImage: "bolt.horizontal.circle")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(.quaternary.opacity(0.5), in: .rect(cornerRadius: 12))

            VStack(spacing: 10) {
                Button(action: install) {
                    Text("Install firmware")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)

                Button("Cancel", role: .cancel, action: cancel)
                    .buttonStyle(.borderless)
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    @ViewBuilder private var versions: some View {
        if let new = pending.version {
            HStack(spacing: 8) {
                if let current, current != new {
                    Text(current).foregroundStyle(.secondary)
                    Image(systemName: "arrow.right")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                Text(new).foregroundStyle(Color.accentColor)
            }
            .font(.system(.title2, design: .rounded, weight: .bold).monospacedDigit())
        }
    }

    private var details: String {
        let size = ByteCountFormatter.string(fromByteCount: Int64(pending.data.count), countStyle: .file)
        return "\(pending.name) · \(size)"
    }
}
