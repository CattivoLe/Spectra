import SwiftUI

extension InternetAccess {
    var title: String {
        switch self {
        case .full: linkText("Full access")
        case .filtered: linkText("Some services blocked")
        case .whitelistOnly: linkText("Allow-list only")
        case .none: linkText("No access")
        }
    }

    var detail: String {
        switch self {
        case .full: linkText("Foreign sites open, and so do services that are often blocked.")
        case .filtered: linkText("The internet works, but services that are often blocked do not open.")
        case .whitelistOnly: linkText("Only sites from the provider's allow-list open. The rest of the internet does not answer.")
        case .none: linkText("Neither foreign sites nor allow-listed ones answer.")
        }
    }

    /// A real signal's colours, the same in light and dark mode: the lamps
    /// sit in a dark housing either way.
    var color: Color {
        switch self {
        case .full: Color(red: 0.19, green: 0.82, blue: 0.35)
        case .filtered, .whitelistOnly: Color(red: 1, green: 0.77, blue: 0)
        case .none: Color(red: 1, green: 0.23, blue: 0.19)
        }
    }
}

/// Three lamps in a dark housing, one lit: green for the open internet,
/// yellow for blocked services or an allow-list, red for nothing. An unlit
/// lamp keeps a dim tint of its own colour, so all three read as lamps.
struct TrafficLight: View {
    var access: InternetAccess?
    var lamp: CGFloat = 12

    var body: some View {
        VStack(spacing: lamp * 0.3) {
            // Blocked services and an allow-list share the yellow lamp; the title tells them apart.
            ForEach([InternetAccess.none, .whitelistOnly, .full], id: \.self) { state in
                let lit = access.map { $0.color == state.color } ?? false
                Circle()
                    .fill(lit ? state.color : state.color.opacity(0.22))
                    .frame(width: lamp, height: lamp)
                    .shadow(color: lit ? state.color.opacity(0.9) : .clear, radius: lamp * 0.45)
            }
        }
        .padding(lamp * 0.4)
        .background(Color(red: 0.12, green: 0.125, blue: 0.137), in: .rect(cornerRadius: lamp * 0.7))
        .accessibilityHidden(true)
    }
}

/// The verdict with its lamp, and the sites behind it.
struct AccessSection: View {
    var report: AccessReport

    var body: some View {
        Section {
            HStack(spacing: 14) {
                TrafficLight(access: report.verdict)
                VStack(alignment: .leading, spacing: 3) {
                    Text(report.verdict.title)
                        .font(.headline)
                    Text(report.verdict.detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if report.verdict == .filtered {
                        Text(String(format: linkText("Not opening: %@"), report.unreachable.joined(separator: ", ")))
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.orange)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
            DisclosureGroup("Sites asked") {
                ForEach(report.open) { ReachRow(reach: $0, note: nil) }
                ForEach(report.blocked ?? []) { ReachRow(reach: $0, note: linkText("often blocked")) }
                ForEach(report.listed) { ReachRow(reach: $0, note: linkText("allow-list")) }
            }
        } header: {
            Text("Access")
        } footer: {
            Text("Large foreign sites, services that are often blocked, and sites from the Russian allow-list are asked over HTTPS. Two often-blocked services that do not open mean the connection is filtered.")
        }
    }
}

private struct ReachRow: View {
    var reach: ResourceReach
    var note: String?

    var body: some View {
        LabeledContent {
            Text(reach.ok ? millis(reach.millis ?? 0) : linkText("No reply"))
                .foregroundStyle(reach.ok ? .green : .red)
                .monospacedDigit()
        } label: {
            VStack(alignment: .leading, spacing: 1) {
                Text(reach.host)
                if let note {
                    Text(note)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// The person's own sites as chips. Tap one to ask it; hold to remove it.
struct ResourcesSection: View {
    @Environment(ResourceWatch.self) private var watch
    @State private var newSite = ""
    @State private var rejected = false
    @FocusState private var editing: Bool

    var body: some View {
        Section {
            if !watch.hosts.isEmpty {
                FlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(watch.hosts, id: \.self) { host in
                        ResourceChip(host: host, reach: watch.results[host], checking: watch.checking.contains(host)) {
                            watch.check(host)
                        }
                        .contextMenu {
                            Button("Check", systemImage: "arrow.clockwise") { watch.check(host) }
                            Button("Remove", systemImage: "trash", role: .destructive) { watch.remove(host) }
                        }
                    }
                }
                .padding(.vertical, 4)
                Button {
                    watch.checkAll()
                } label: {
                    Label("Check all", systemImage: "checklist")
                }
                .disabled(watch.isChecking)
            }
            HStack(spacing: 10) {
                Image(systemName: "plus.circle")
                    .foregroundStyle(.secondary)
                TextField("Add a site, like youtube.com", text: $newSite)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .submitLabel(.done)
                    .focused($editing)
                    .onSubmit(add)
                    .onChange(of: newSite) { _, _ in rejected = false }
                Button("Add", action: add)
                    .buttonStyle(.borderless)
                    .disabled(newSite.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if rejected {
                Text("That is not a site name, or it is already in the list.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        } header: {
            Text("Resources")
        } footer: {
            Text("Tap a site to see whether it opens from the chosen connection; hold it to remove. Each one is asked over HTTPS without loading the page.")
        }
    }

    private func add() {
        let text = newSite
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        if watch.add(text) {
            newSite = ""
            if let host = AccessCheck.normalize(text) { watch.check(host) }
        } else {
            rejected = true
        }
    }
}

private struct ResourceChip: View {
    var host: String
    var reach: ResourceReach?
    var checking: Bool
    var action: () -> Void

    private var color: Color {
        guard let reach else { return .secondary }
        return reach.ok ? .green : .red
    }

    var body: some View {
        // Borderless: in a List row any other style makes every chip fire at once.
        Button(action: action) {
            HStack(spacing: 6) {
                if checking {
                    ProgressView()
                        .controlSize(.mini)
                } else {
                    Circle()
                        .fill(reach == nil ? AnyShapeStyle(.fill.secondary) : AnyShapeStyle(color))
                        .frame(width: 8, height: 8)
                }
                Text(host)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                if let reach, !checking, reach.ok, let time = reach.millis {
                    Text(millis(time))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .foregroundStyle(.primary)
            .background(reach == nil || checking ? AnyShapeStyle(.fill.tertiary) : AnyShapeStyle(color.opacity(0.16)), in: .capsule)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(Text(host))
        .accessibilityValue(Text(reach.map { $0.ok ? linkText("Opens") : linkText("Does not open") } ?? ""))
    }
}
