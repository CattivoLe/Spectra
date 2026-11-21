import SwiftUI

/// History → Settings → Licenses: Spectra's own license and every third-party notice the app has to carry.
struct LicensesView: View {
    private let components = ThirdPartyLicenses.bundled

    var body: some View {
        List {
            ForEach(ThirdPartyLicenses.Group.allCases, id: \.self) { group in
                let shown = components.filter { $0.group == group }
                if !shown.isEmpty {
                    Section {
                        ForEach(shown) { component in
                            NavigationLink {
                                LicenseDetailView(component: component)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(component.name)
                                    Text([component.license, component.version].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    } header: {
                        Text(group.title)
                    } footer: {
                        if let footer = group.footer { Text(footer) }
                    }
                }
            }
        }
        .navigationTitle("Licenses")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// One component: where it comes from, its copyright and notice, and the full license text.
private struct LicenseDetailView: View {
    var component: ThirdPartyLicenses.Component

    var body: some View {
        List {
            Section {
                if !component.version.isEmpty {
                    LabeledContent("Version", value: component.version)
                }
                LabeledContent("License", value: component.license)
                if let url = URL(string: component.url), !component.url.isEmpty {
                    Link(destination: url) {
                        Label("Project page", systemImage: "arrow.up.right.square")
                    }
                }
                if !component.copyright.isEmpty {
                    Text(component.copyright)
                        .font(.callout)
                        .textSelection(.enabled)
                }
            }
            if !component.notice.isEmpty {
                Section("Notice") {
                    LicenseText(text: component.notice)
                }
            }
            if let text = component.text {
                Section(component.license) {
                    LicenseText(text: text)
                }
            }
        }
        .navigationTitle(component.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct LicenseText: View {
    var text: String

    var body: some View {
        Text(verbatim: ThirdPartyLicenses.reflow(text))
            .font(.footnote)
            .textSelection(.enabled)
    }
}

private extension ThirdPartyLicenses.Group {
    var title: LocalizedStringKey {
        switch self {
        case .app: "This app"
        case .code: "Libraries"
        case .firmware: "Sensor firmware"
        case .data: "Data"
        }
    }

    var footer: LocalizedStringKey? {
        switch self {
        case .app: "Spectra is open source: github.com/CattivoLe/Spectra"
        case .firmware: "The app carries firmware images for flashing the sensor; these are the parts built into them."
        default: nil
        }
    }
}
