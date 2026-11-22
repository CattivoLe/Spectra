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
                    fact("sparkle.magnifyingglass", "A summary reads what Spectra found (networks, devices, history) on this phone only. Its findings come from the app's own rules; the model on this phone only puts them into words.")
                    fact("bolt.horizontal", "The buttons under a summary run the same scans and checks you can run by hand on the other tabs. Nothing that sends to the air, such as deauthenticating a device or flashing the sensor, is ever among them.")
                    fact("lock.doc.fill", "Summaries are encrypted (AES-256). The key is in this phone's keychain and is never copied anywhere.")
                    fact("icloud.slash", "Summaries are not included in iCloud or computer backups.")
                    if model.web.isEnabled {
                        fact("globe", "Web search is allowed: the query text goes out, and you can see it in the log. With page reading on, the phone also opens the pages found, as a browser does. Besides this, the internet is used to download models.")
                    } else {
                        fact("antenna.radiowaves.left.and.right.slash", "The internet is used only to download models, and only when you tap Download.")
                    }
                    fact("person.crop.circle.badge.xmark", "No account, analytics or ads.")
                }

                Section {
                    Button("Delete all summaries (\(chatCount))", systemImage: "trash", role: .destructive) { confirmErase = true }
                        .disabled(chatCount == 0)
                } footer: {
                    Text("Models and settings stay.")
                }
            }
            .navigationTitle("Privacy")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { await refresh() }
            .confirmationDialog("Delete all summaries?", isPresented: $confirmErase, titleVisibility: .visible) {
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
