import SwiftUI
import UniformTypeIdentifiers

struct SensorView: View {
    @Environment(SensorLink.self) private var link
    @Environment(NetworkStore.self) private var store
    @State private var pickingFirmware = false
    @State private var pendingFirmware: PendingFirmware?
    @State private var installTaps = 0

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

                    if let info = link.info {
                        Section {
                            InfoRow("Firmware", value: info.fw)
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
                                if let bundled = BundledFirmware.current {
                                    if let version = bundled.version, version == info.fw {
                                        Label("Firmware is up to date", systemImage: "checkmark.seal.fill")
                                            .foregroundStyle(.green)
                                        Button("Reinstall \(version)", systemImage: "arrow.clockwise") {
                                            offer(PendingFirmware(name: "firmware.bin", data: bundled.data, version: version))
                                        }
                                    } else {
                                        Button(
                                            bundled.version.map { String(localized: "Install firmware \($0)") }
                                                ?? String(localized: "Install included firmware"),
                                            systemImage: "arrow.down.circle"
                                        ) {
                                            offer(PendingFirmware(name: "firmware.bin", data: bundled.data, version: bundled.version))
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
                            } else if case .connected = link.state, info.ota, BundledFirmware.current != nil {
                                Text("The app carries the firmware.bin from its last build. Rebuild the iOS app after pio run and the new version shows up here.")
                            } else if case .connected = link.state, info.ota {
                                Text("This build of the app has no firmware inside. Run pio run before building the app, or pick firmware.bin from Files.")
                            }
                        }
                    }

                default:
                    EmptyView()
                }

                Section {
                    Toggle("Demo sensor", isOn: Binding(
                        get: { link.state == .demo },
                        set: { link.setDemo($0) }
                    ))
                } footer: {
                    Text("Made-up networks with drifting signal, for trying the app without a Cardputer.")
                }

                Section("About the sensor") {
                    Text("iPhone apps cannot list nearby Wi-Fi networks, so Spectra uses a Cardputer as its radio. Its ESP32-S3 hears the 2.4 GHz band only; 5 GHz networks do not appear.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Sensor")
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

    private func offer(_ firmware: PendingFirmware) {
        pendingFirmware = firmware
    }

    private struct BundledFirmware {
        var data: Data
        var version: String?

        /// Copied into the app by the Xcode build, from pio's firmware.bin.
        static let current: BundledFirmware? = {
            guard let url = Bundle.main.url(forResource: "firmware", withExtension: "bin"),
                  let data = try? Data(contentsOf: url),
                  (try? FirmwareUpdate.validate(data)) != nil
            else { return nil }
            return BundledFirmware(data: data, version: FirmwareUpdate.taggedVersion(data))
        }()
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
