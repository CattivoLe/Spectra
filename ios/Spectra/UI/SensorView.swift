import SwiftUI

struct SensorView: View {
    @Environment(SensorLink.self) private var link
    @Environment(NetworkStore.self) private var store

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
                        .disabled(store.isScanning)
                        if let duration = store.lastScanDuration {
                            LabeledContent("Last sweep", value: duration.formatted(.units(allowed: [.seconds, .milliseconds], width: .narrow)))
                        }
                        LabeledContent("Scans this session", value: "\(store.scanCount)")
                    }

                    if let info = link.info {
                        Section("Device") {
                            LabeledContent("Firmware", value: info.fw)
                            LabeledContent("Chip", value: info.chip)
                            LabeledContent("Bands", value: info.bands.map { "\($0) GHz" }.formatted(.list(type: .and)))
                            if info.proto != SensorProtocol.version {
                                Label("This firmware speaks protocol \(info.proto); the app expects \(SensorProtocol.version). Update the one that is older.", systemImage: "exclamationmark.triangle.fill")
                                    .symbolRenderingMode(.multicolor)
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
        }
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
