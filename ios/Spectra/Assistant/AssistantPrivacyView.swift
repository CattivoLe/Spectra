import SwiftUI
import LocalChatKit

/// What the assistant keeps on the device, how it is protected, and how to erase it.
struct AssistantPrivacyView: View {
    @Environment(AssistantModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var chatCount = 0
    @State private var confirmErase = false
    @State private var errorText: String?

    var body: some View {
        @Bindable var lock = model.lock

        NavigationStack {
            List {
                Section {
                    Toggle(isOn: Binding(
                        get: { lock.isEnabled },
                        set: { wantsOn in Task { _ = wantsOn ? await lock.enable() : await lock.disable() } }
                    )) {
                        Label("Lock: \(lock.methodName)", systemImage: "faceid")
                    }
                    if lock.isEnabled {
                        Picker("Lock", selection: $lock.gracePeriod) {
                            ForEach(AppLock.GracePeriod.allCases, id: \.self) { Text($0.title).tag($0) }
                        }
                    }
                    Toggle("Hide Spectra in the app switcher", isOn: $lock.hideInSwitcher)
                } header: {
                    Text("Access")
                } footer: {
                    Text(lock.canAuthenticate
                         ? "The lock covers the whole of Spectra. If your face is not recognized, the phone's passcode opens it."
                         : "No passcode is set on this phone, so the lock cannot be turned on.")
                }

                Section("Internet") {
                    NavigationLink {
                        AssistantWebView().environment(model)
                    } label: {
                        Label(model.web.isEnabled ? "Search allowed" : "Search off", systemImage: "globe")
                    }
                }

                Section("How it works") {
                    fact("sparkle.magnifyingglass", "The assistant reads what Spectra found (networks, devices, history) only on this phone, and only when a question needs it. It can also run a scan or check itself — only ones that read or measure, never anything that transmits over the air.")
                    fact("bolt.horizontal", "With that same switch, it can also run a fresh scan or check itself — the same ones you can run by hand on the other tabs. It never does anything that sends to the air, such as deauthenticating a device or flashing the sensor.")
                    fact("lock.doc.fill", "Chats are encrypted (AES-256). The key is in this phone's keychain and is never copied anywhere.")
                    fact("icloud.slash", "Chats are not included in iCloud or computer backups.")
                    if model.web.isEnabled {
                        fact("globe", "Web search is allowed: only the query text goes out, and you can see it in the log. Besides this, the internet is used to download models.")
                    } else {
                        fact("antenna.radiowaves.left.and.right.slash", "The internet is used only to download models, and only when you tap Download.")
                    }
                    fact("person.crop.circle.badge.xmark", "No account, analytics or ads.")
                }

                Section {
                    Button("Delete all chats (\(chatCount))", systemImage: "trash", role: .destructive) { confirmErase = true }
                        .disabled(chatCount == 0)
                } footer: {
                    Text("Models and settings stay.")
                }
            }
            .navigationTitle("Privacy")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { await refresh() }
            .confirmationDialog("Delete all chats?", isPresented: $confirmErase, titleVisibility: .visible) {
                Button("Delete for good", role: .destructive) {
                    Task {
                        do { try await model.eraseChats() } catch { errorText = error.localizedDescription }
                        await refresh()
                    }
                }
            } message: { Text("They cannot be restored.") }
            .alert("Error", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("OK") { errorText = nil }
            } message: { Text(errorText ?? "") }
        }
    }

    private func fact(_ symbol: String, _ text: LocalizedStringKey) -> some View {
        Label { Text(text).font(.callout) } icon: { Image(systemName: symbol).foregroundStyle(.tint) }
    }

    private func refresh() async {
        chatCount = (try? await model.store.chats().count) ?? 0
    }
}
