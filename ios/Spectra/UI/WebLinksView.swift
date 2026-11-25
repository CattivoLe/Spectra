import SwiftUI
import UIKit

/// The web pages of devices the user kept: a tap opens one in the browser.
/// Mirrors WebLinksScreen.kt.
struct WebLinksView: View {
    @Environment(WebLinks.self) private var store
    @Environment(LANScanner.self) private var scanner
    @Environment(\.openURL) private var openURL
    @State private var editing: WebLink?
    @State private var editMode: EditMode = .inactive

    var body: some View {
        let groups = store.groups(current: scanner.webLinkNetwork)
        List {
            ForEach(groups) { group in
                Section {
                    ForEach(group.links) { link in
                        row(link)
                    }
                    .onMove { source, destination in
                        var ids = group.links.map(\.id)
                        ids.move(fromOffsets: source, toOffset: destination)
                        store.reorder(ids)
                    }
                    .onDelete { offsets in
                        offsets.map { group.links[$0].id }.forEach(store.remove)
                    }
                } header: {
                    if groups.count > 1 {
                        group.label.map { Text(verbatim: $0) } ?? Text("Other network")
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
        .toolbar {
            if !store.links.isEmpty {
                ToolbarItem(placement: .topBarTrailing) { EditButton() }
            }
        }
        .environment(\.editMode, $editMode)
        .onChange(of: store.links.isEmpty) { _, empty in
            if empty { editMode = .inactive }
        }
        .sheet(item: $editing) { link in
            WebLinkEditor(link: link)
        }
        .sensoryFeedback(.impact(weight: .light), trigger: store.links.count)
    }

    /// Opens the page; while the list is being edited, the link's settings.
    @ViewBuilder
    private func row(_ link: WebLink) -> some View {
        if let url = link.url {
            Button {
                if editMode.isEditing {
                    editing = link
                } else {
                    openURL(url)
                }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: link.shownSymbol)
                        .font(.title3)
                        .foregroundStyle(link.tint)
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
                    if !editMode.isEditing {
                        Image(systemName: "safari")
                            .foregroundStyle(.tint)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(editMode.isEditing ? Text("Edit…") : Text("Opens \(link.address) in the browser"))
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

extension WebLinkColor {
    var color: Color {
        switch self {
        case .red: .red
        case .orange: .orange
        case .yellow: .yellow
        case .green: .green
        case .teal: .teal
        case .blue: .blue
        case .indigo: .indigo
        case .purple: .purple
        case .pink: .pink
        case .brown: .brown
        case .gray: .gray
        }
    }
}

extension WebLink {
    /// The icon's colour, the app's own unless one was picked.
    var tint: AnyShapeStyle {
        color.flatMap(WebLinkColor.init).map { AnyShapeStyle($0.color) } ?? AnyShapeStyle(.tint)
    }
}

/// Changes a link's name, address and how its icon looks.
private struct WebLinkEditor: View {
    var link: WebLink
    @Environment(WebLinks.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var address: String
    @State private var icon: String?
    @State private var color: WebLinkColor?

    init(link: WebLink) {
        self.link = link
        _name = State(initialValue: link.name ?? "")
        _address = State(initialValue: link.address)
        _icon = State(initialValue: link.customIcon)
        _color = State(initialValue: link.color.flatMap(WebLinkColor.init))
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
                Section("Color") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 44), spacing: 8)], spacing: 8) {
                        colorCell(nil)
                        ForEach(WebLinkColor.allCases) { colorCell($0) }
                    }
                    .padding(.vertical, 6)
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
                        store.edit(link.id, name: name, address: address, icon: icon, color: color)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(WebLink.normalized(address) == nil)
                }
            }
        }
        .sensoryFeedback(.selection, trigger: icon)
        .sensoryFeedback(.selection, trigger: color)
    }

    private var tint: AnyShapeStyle { style(color) }

    private func style(_ color: WebLinkColor?) -> AnyShapeStyle {
        color.map { AnyShapeStyle($0.color) } ?? AnyShapeStyle(.tint)
    }

    private func colorCell(_ value: WebLinkColor?) -> some View {
        let chosen = color == value
        return Button {
            color = value
        } label: {
            Circle()
                .fill(style(value))
                .frame(width: 30, height: 30)
                .padding(5)
                .overlay { Circle().strokeBorder(style(value), lineWidth: chosen ? 2.5 : 0) }
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(value?.title ?? String(localized: "Auto"))
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }

    private func captionedCell(_ id: String?, symbol: String, title: String) -> some View {
        VStack(spacing: 4) {
            cell(id, symbol: symbol)
                .accessibilityLabel(title)
            Text(title)
                .font(.caption2)
                .foregroundStyle(icon == id ? AnyShapeStyle(.primary) : AnyShapeStyle(.secondary))
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
                .foregroundStyle(tint)
                .frame(width: 48, height: 48)
                .background(.fill.tertiary, in: .rect(cornerRadius: 12))
                // An outline rather than a fill: white on yellow could not be made out.
                .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(tint, lineWidth: chosen ? 2.5 : 0) }
                .contentShape(Rectangle())
        }
        // Several buttons share one row of the form.
        .buttonStyle(.borderless)
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }
}
