import SwiftUI
import UIKit

/// The web pages of devices the user kept: a tap opens one in the browser.
/// Mirrors WebLinksScreen.kt.
struct WebLinksView: View {
    @Environment(WebLinks.self) private var store
    @Environment(LANScanner.self) private var scanner
    @State private var editing: WebLink?

    private struct NetworkGroup: Identifiable {
        var id: String
        var links: [WebLink]
    }

    /// By network, the one the phone is on first.
    private var groups: [NetworkGroup] {
        var groups: [NetworkGroup] = []
        for link in store.links {
            let label = link.networkLabel ?? ""
            if let index = groups.firstIndex(where: { $0.id == label }) {
                groups[index].links.append(link)
            } else {
                groups.append(NetworkGroup(id: label, links: [link]))
            }
        }
        let current = scanner.networkLabel ?? ""
        return groups.filter { $0.id == current } + groups.filter { $0.id != current }
    }

    var body: some View {
        let groups = groups
        List {
            ForEach(groups) { group in
                Section {
                    ForEach(group.links) { link in
                        row(link)
                    }
                } header: {
                    if groups.count > 1 {
                        group.id.isEmpty ? Text("Other network") : Text(verbatim: group.id)
                    }
                } footer: {
                    if group.id == groups.last?.id {
                        Text("Opens while the phone is on the same network. When the router gives a device another address, the next LAN scan updates its link.")
                    }
                }
            }
        }
        .overlay {
            if store.links.isEmpty {
                ContentUnavailableView(
                    "No web interfaces",
                    systemImage: "bookmark",
                    description: Text("Open a device on the LAN tab and tap the bookmark next to a port that has a web page.")
                )
            }
        }
        .navigationTitle("Web interfaces")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { link in
            WebLinkEditor(link: link)
        }
        .sensoryFeedback(.impact(weight: .light), trigger: store.links.count)
    }

    @ViewBuilder
    private func row(_ link: WebLink) -> some View {
        if let url = link.url {
            Link(destination: url) {
                HStack(spacing: 12) {
                    Image(systemName: link.shownSymbol)
                        .font(.title3)
                        .foregroundStyle(.tint)
                        .frame(width: 30)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(link.title)
                            .font(.body.weight(.medium))
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                        HStack(spacing: 4) {
                            if url.scheme == "https" {
                                Image(systemName: "lock.fill")
                                    .font(.caption2)
                                    .accessibilityLabel(Text(verbatim: "HTTPS"))
                            }
                            Text(link.shownAddress)
                                .font(.subheadline)
                                .monospacedDigit()
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "safari")
                        .foregroundStyle(.tint)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text("Opens \(link.address) in the browser"))
            .contextMenu {
                Button("Copy address", systemImage: "doc.on.doc") { UIPasteboard.general.string = link.address }
                Button("Edit…", systemImage: "pencil") { editing = link }
                Button("Delete", systemImage: "trash", role: .destructive) { store.remove(link.id) }
            }
            .swipeActions {
                Button("Delete", systemImage: "trash", role: .destructive) { store.remove(link.id) }
                Button("Edit…", systemImage: "pencil") { editing = link }
                    .tint(.blue)
            }
        }
    }
}

/// Changes a link's name, icon and address, say to add the path of a login page.
private struct WebLinkEditor: View {
    var link: WebLink
    @Environment(WebLinks.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var address: String
    @State private var icon: String?

    init(link: WebLink) {
        self.link = link
        _name = State(initialValue: link.name ?? "")
        _address = State(initialValue: link.address)
        _icon = State(initialValue: link.customIcon)
    }

    /// By the name being typed, then what the device and its page say about themselves.
    private var suggestions: [WebLinkIcon] {
        WebLinkIcon.suggestions(
            texts: [name, link.deviceName] + (link.hints ?? []), port: link.port, own: WebLinkIcon.own(link.symbol)?.id
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField(link.deviceName, text: $name)
                }
                Section("Address") {
                    TextField("http://…", text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                }
                Section("Icon") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 8, alignment: .top)], spacing: 12) {
                        captionedCell(nil, symbol: link.symbol, title: String(localized: "Auto"))
                        ForEach(suggestions) { item in
                            captionedCell(item.id, symbol: item.symbol, title: item.title)
                        }
                    }
                    .padding(.vertical, 6)
                    .animation(.default, value: suggestions)
                }
                Section("All icons") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 48), spacing: 8)], spacing: 8) {
                        ForEach(WebLinkIcon.all) { item in
                            cell(item.id, symbol: item.symbol)
                                .accessibilityLabel(item.title)
                        }
                    }
                    .padding(.vertical, 6)
                }
            }
            .navigationTitle("Link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        store.edit(link.id, name: name, address: address, icon: icon)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(WebLink.normalized(address) == nil)
                }
            }
        }
        .sensoryFeedback(.selection, trigger: icon)
    }

    private func captionedCell(_ id: String?, symbol: String, title: String) -> some View {
        VStack(spacing: 4) {
            cell(id, symbol: symbol)
                .accessibilityLabel(title)
            Text(title)
                .font(.caption2)
                .foregroundStyle(icon == id ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .accessibilityHidden(true)
        }
    }

    private func cell(_ id: String?, symbol: String) -> some View {
        let chosen = icon == id
        return Button {
            icon = id
        } label: {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(chosen ? AnyShapeStyle(.white) : AnyShapeStyle(.tint))
                .frame(width: 48, height: 48)
                .background(chosen ? AnyShapeStyle(.tint) : AnyShapeStyle(.fill.tertiary), in: .rect(cornerRadius: 12))
                .contentShape(Rectangle())
        }
        // Several buttons share one row of the form.
        .buttonStyle(.borderless)
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }
}
