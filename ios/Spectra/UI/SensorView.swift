import SwiftUI
import UniformTypeIdentifiers

struct SensorView: View {
    @Environment(SensorLink.self) private var link
    @Environment(NetworkStore.self) private var store
    @State private var pickingFirmware = false
    @State private var pendingFirmware: PendingFirmware?

    private struct PendingFirmware: Identifiable {
        let id = UUID()
        var name: String
        var data: Data
    }

    var body: some View {
        @Bindable var link = link

        NavigationStack {
            Form {
                Section {
                    status
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
                                if seconds == 0 {
                                    Text("Off").tag(seconds)
                                } else {
                                    Text("Every \(seconds) s").tag(seconds)
                                }
                            }
                        }
                        Button("Scan now", systemImage: "arrow.clockwise") {
                            link.scanNow()
                        }
                        .disabled(store.isScanning || link.firmwareUpdate?.isRunning == true)
                        if let duration = store.lastScanDuration {
                            InfoRow("Last sweep", value: duration.formatted(.units(allowed: [.seconds, .milliseconds], width: .narrow)))
                        }
                        InfoRow("Scans this session", value: "\(store.scanCount)")
                    }

                    if let update = link.firmwareUpdate {
                        Section {
                            firmwareStatus(update)
                        }
                    }

                    if let info = link.info {
                        Section {
                            InfoRow("Firmware", value: info.fw)
                            InfoRow("Chip", value: info.chip)
                            InfoRow("Bands", value: info.bands.compactMap(Double.init).map(Units.gigahertz).formatted(.list(type: .and)))
                            if info.proto != SensorProtocol.version {
                                Label("This firmware speaks protocol \(info.proto); the app expects \(SensorProtocol.version). Update the one that is older.", systemImage: "exclamationmark.triangle.fill")
                                    .symbolRenderingMode(.multicolor)
                            }
                            if case .connected = link.state, info.ota, link.firmwareUpdate?.isRunning != true {
                                Button("Update firmware", systemImage: "arrow.down.circle") {
                                    pickingFirmware = true
                                }
                            }
                        } header: {
                            Text("Device")
                        } footer: {
                            if case .connected = link.state, !info.ota {
                                Text("This firmware cannot take updates from the app. Flash 0.6.0 or newer once over USB or the launcher; after that, updates install from here.")
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
            .fileImporter(
                isPresented: $pickingFirmware,
                allowedContentTypes: firmwareFileTypes,
                allowsMultipleSelection: false
            ) { result in
                loadFirmware(result)
            }
            .confirmationDialog(
                "Install this firmware?",
                isPresented: Binding(
                    get: { pendingFirmware != nil },
                    set: { if !$0 { pendingFirmware = nil } }
                ),
                titleVisibility: .visible,
                presenting: pendingFirmware
            ) { pending in
                Button("Install firmware") {
                    link.installFirmware(pending.data)
                    pendingFirmware = nil
                }
                Button("Cancel", role: .cancel) {
                    pendingFirmware = nil
                }
            } message: { pending in
                Text(installMessage(pending))
            }
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
            pendingFirmware = PendingFirmware(name: url.lastPathComponent, data: data)
        }
    }

    private func installMessage(_ pending: PendingFirmware) -> String {
        let size = ByteCountFormatter.string(fromByteCount: Int64(pending.data.count), countStyle: .file)
        return String(
            format: String(localized: "%@ (%@). The Cardputer reboots when the transfer finishes. Keep it powered and nearby."),
            pending.name,
            size
        )
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
            HStack {
                Label(name, systemImage: "antenna.radiowaves.left.and.right")
                Spacer()
                ProgressView()
            }
            Button("Cancel", role: .cancel) {
                link.disconnect()
            }

        case .connected(let name):
            Label(name, systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
            Button("Disconnect", role: .destructive) {
                link.disconnect()
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
