import SwiftUI

/// Lets the user name a host, say whether it is theirs, and leave a note.
struct DeviceLabelEditor: View {
    var device: LANDevice
    @Environment(DeviceLabels.self) private var labels
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var trust: DeviceTrust
    @State private var note: String

    init(device: LANDevice) {
        self.device = device
        _name = State(initialValue: device.customName ?? "")
        _trust = State(initialValue: device.trust)
        _note = State(initialValue: device.note ?? "")
    }

    /// Nothing but the address identifies this host, so the label may land
    /// on whichever device gets that address next.
    private var addressOnly: Bool {
        device.fingerprints.allSatisfy { $0.hasPrefix("ip:") }
    }

    private var suggestedName: String {
        if let customName = device.customName, device.displayName == customName {
            // Show what the scanner would call it without the label.
            let bare = LANDevice(
                ipAddress: device.ipAddress, macAddress: device.macAddress, hostnames: device.hostnames,
                services: device.services, isGateway: device.isGateway, isLocalDevice: device.isLocalDevice, profile: device.profile
            )
            return bare.displayName
        }
        return device.displayName
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(suggestedName, text: $name)
                        .textInputAutocapitalization(.words)
                } header: {
                    Text("Name")
                } footer: {
                    Text("Shown instead of the name the device announces.")
                }

                Section {
                    Picker("Trust", selection: $trust) {
                        ForEach(DeviceTrust.allCases, id: \.self) { level in
                            Text(level.label).tag(level)
                        }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Trust")
                } footer: {
                    Text("Trusted devices lose the New mark and can be hidden from the list. Suspicious ones are flagged in red.")
                }

                Section("Note") {
                    TextField("Where it is, whose it is…", text: $note, axis: .vertical)
                        .lineLimit(2...6)
                }

                Section {
                    LabeledContent("Identified by") {
                        Text(identifiedBy)
                            .multilineTextAlignment(.trailing)
                    }
                } footer: {
                    if addressOnly {
                        Text("Only the address is known, so the label may move to another device that gets this address later.")
                    } else {
                        Text("The label follows the device by these, even when its address changes.")
                    }
                }
            }
            .navigationTitle("Label")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        labels.set(DeviceLabel(name: name, trust: trust, note: note), for: device)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var identifiedBy: String {
        var parts: [String] = []
        if let mac = device.macAddress { parts.append(mac.description) }
        let names = device.fingerprints.filter { $0.hasPrefix("name:") }.map { String($0.dropFirst(5)) }
        parts.append(contentsOf: names.prefix(2))
        if parts.isEmpty { parts.append(device.ipAddress) }
        return parts.joined(separator: "\n")
    }
}
