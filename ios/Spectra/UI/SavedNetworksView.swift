import SwiftUI
import UniformTypeIdentifiers

/// A password field with an eye button that shows what was typed.
struct PasswordField: View {
    var title: LocalizedStringKey
    @Binding var text: String
    var prompt: Text?
    @State private var visible = false

    var body: some View {
        HStack {
            Group {
                if visible {
                    TextField(title, text: $text, prompt: prompt)
                        .fontDesign(.monospaced)
                } else {
                    SecureField(title, text: $text, prompt: prompt)
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            Button {
                visible.toggle()
            } label: {
                Image(systemName: visible ? "eye.slash" : "eye")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(visible ? Text("Hide password") : Text("Show password"))
        }
    }
}

/// Asks for the password before the phone joins a secured network.
struct JoinPasswordSheet: View {
    var ssid: String
    @Binding var password: String
    var connect: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PasswordField(title: "Password", text: $password)
                } footer: {
                    Text("Enter the password for “\(ssid)”.")
                }
            }
            .navigationTitle(String(localized: "Join “\(ssid)”"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Connect") {
                        connect()
                        dismiss()
                    }
                    .disabled(password.isEmpty)
                }
            }
        }
        .presentationDetents([.height(260)])
    }
}

/// The networks the app keeps: the passwords that put the phone or the sensor on a network, open networks the
/// phone joined, and ones added by hand. iOS does not let apps read its own saved passwords, so this is the app's
/// list. Mirrors SavedNetworksScreen.kt.
struct SavedNetworksView: View {
    @Environment(SensorLink.self) private var link
    @Environment(NetworkStore.self) private var store
    @Environment(WiFiJoiner.self) private var joiner
    @State private var editing: Editing?
    @State private var actions: SavedWiFiNetwork?
    @State private var shown: Set<String> = []

    /// The editor: a new network, or one already in the list.
    private struct Editing: Identifiable {
        let id = UUID()
        var original: SavedWiFiNetwork?
    }

    /// The networks heard now, by name: whether a saved one is in range, and its security for joining.
    private var nearby: [String: [TrackedNetwork]] {
        Dictionary(grouping: store.networks.values.filter(\.isInRange)) { $0.revealedSSID ?? $0.latest.ssid ?? "" }
    }

    var body: some View {
        let saved = link.savedNetworks
        let heard = nearby
        List {
            if !saved.isEmpty {
                Section {
                    ForEach(saved) { network in
                        row(network, heard: heard[network.ssid] ?? [])
                    }
                } header: {
                    Text("On this iPhone: \(saved.count)")
                } footer: {
                    Text("iOS keeps its own saved passwords out of reach of apps, so this list is Spectra's: what worked through it, and what you add. Passwords go to the sensor too.")
                }
            }
        }
        .overlay {
            if saved.isEmpty {
                ContentUnavailableView(
                    "No saved networks",
                    systemImage: "wifi.exclamationmark",
                    description: Text("Networks the phone or the sensor joins through Spectra show up here with their passwords. Add one with +.")
                )
            }
        }
        .navigationTitle("Saved networks")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Add network", systemImage: "plus") { editing = Editing(original: nil) }
            }
        }
        .sheet(item: $editing) { edit in
            NetworkEditor(original: edit.original) { ssid, password in
                if let old = edit.original?.ssid, old != ssid { link.forgetSaved(ssid: old) }
                link.saveNetwork(ssid: ssid, password: password)
            }
        }
        .confirmationDialog(
            actions?.ssid ?? "",
            isPresented: Binding(get: { actions != nil }, set: { if !$0 { actions = nil } }),
            titleVisibility: .visible,
            presenting: actions
        ) { network in
            if !joiner.isOn(network.ssid) {
                Button("Connect") { connect(network, heard: heard[network.ssid] ?? []) }
            }
            if !network.password.isEmpty {
                Button("Copy password") { copy(network.password) }
            }
            Button("Edit…") { editing = Editing(original: network) }
            Button("Delete", role: .destructive) { link.forgetSaved(ssid: network.ssid) }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func row(_ network: SavedWiFiNetwork, heard: [TrackedNetwork]) -> some View {
        let current = joiner.isOn(network.ssid)
        let joining = joiner.status == .joining(ssid: network.ssid)
        let inRange = !heard.isEmpty
        let visible = shown.contains(network.ssid)
        return HStack(spacing: 12) {
            Button {
                actions = network
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: network.password.isEmpty ? "lock.open.fill" : "lock.fill")
                        .font(.title3)
                        .frame(width: 28)
                        .foregroundStyle(current ? AnyShapeStyle(.green) : inRange ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(network.ssid)
                            .font(.body.weight(.medium))
                            .foregroundStyle(.primary)
                        Group {
                            if network.password.isEmpty {
                                Text("No password")
                            } else if visible {
                                Text(verbatim: network.password).fontDesign(.monospaced)
                            } else {
                                Text(verbatim: String(repeating: "•", count: min(max(network.password.count, 6), 12)))
                            }
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        if current {
                            Text("Connected").font(.caption).foregroundStyle(.green)
                        } else if joining {
                            Text("Connecting…").font(.caption).foregroundStyle(.secondary)
                        } else if let message = joiner.failure(for: network.ssid) {
                            Text(message).font(.caption).foregroundStyle(.orange)
                        } else if inRange {
                            Text("In range").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if !network.password.isEmpty {
                Button {
                    if visible { shown.remove(network.ssid) } else { shown.insert(network.ssid) }
                } label: {
                    Image(systemName: visible ? "eye.slash" : "eye")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(visible ? Text("Hide password") : Text("Show password"))
            }
        }
        .swipeActions {
            Button("Delete", role: .destructive) { link.forgetSaved(ssid: network.ssid) }
        }
    }

    private func connect(_ network: SavedWiFiNetwork, heard: [TrackedNetwork]) {
        let wep = heard.first?.latest.auth.joinMethod == .wep
        let password = network.password.isEmpty ? nil : network.password
        Task { await joiner.join(ssid: network.ssid, passphrase: password, isWEP: wep, hidden: false) }
    }

    /// On this device only, and gone from the clipboard after two minutes.
    private func copy(_ password: String) {
        UIPasteboard.general.setItems(
            [[UTType.plainText.identifier: password]],
            options: [.localOnly: true, .expirationDate: Date.now.addingTimeInterval(120)]
        )
    }
}

/// Adds a network by hand, or changes one already saved.
private struct NetworkEditor: View {
    var original: SavedWiFiNetwork?
    var save: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var ssid = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Network name", text: $ssid)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: ssid) { _, value in if value.utf8.count > 32 { ssid = String(value.prefix(32)) } }
                    PasswordField(title: "Password", text: $password, prompt: Text("Empty for an open network"))
                        .onChange(of: password) { _, value in if value.count > 63 { password = String(value.prefix(63)) } }
                }
            }
            .navigationTitle(original == nil ? "Add network" : "Edit network")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        save(ssid.trimmingCharacters(in: .whitespacesAndNewlines), password)
                        dismiss()
                    }
                    .disabled(ssid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                ssid = original?.ssid ?? ""
                password = original?.password ?? ""
            }
        }
        .presentationDetents([.medium])
    }
}
