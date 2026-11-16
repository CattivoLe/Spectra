import SwiftUI

/// What one reference site's certificate looked like from this network.
struct CertificateDetailView: View {
    var observation: TLSObservation

    private var fine: Bool { observation.error == nil && observation.trusted && observation.expected }

    var body: some View {
        List {
            Section {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(Image(systemName: symbol))
                        .font(.title2)
                        .foregroundStyle(color)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(observation.host)
                            .font(.title3.weight(.semibold))
                            .textSelection(.enabled)
                        Text(verdict)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.vertical, 4)
            }

            if let error = observation.error {
                Section("Error") {
                    Text(error)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }

            if observation.error == nil {
                Section("Issued to") {
                    if let subject = observation.subject {
                        InfoRow("Common name") { Text(subject).textSelection(.enabled) }
                    }
                    if let names = observation.names, !names.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Names covered")
                            Text(names.joined(separator: "\n"))
                                .font(.subheadline.monospaced())
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }

                Section("Issued by") {
                    if let issuer = observation.issuer {
                        InfoRow("Organization") { Text(issuer).textSelection(.enabled) }
                    }
                    if let common = observation.issuerCommonName, common != observation.issuer {
                        InfoRow("Certificate") { Text(common).textSelection(.enabled) }
                    }
                    if let chain = observation.chain, !chain.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Chain of trust")
                            ForEach(Array(chain.enumerated()), id: \.offset) { index, name in
                                HStack(alignment: .firstTextBaseline, spacing: 6) {
                                    Image(systemName: index == chain.count - 1 ? "checkmark.seal" : "arrow.turn.down.right")
                                        .font(.caption)
                                    Text(name)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .padding(.leading, CGFloat(index) * 12)
                            }
                        }
                    }
                    InfoRow("Trusted by iOS") {
                        Text(observation.trusted ? "Yes" : "No")
                            .foregroundStyle(observation.trusted ? Color.secondary : Color.red)
                    }
                    if let reason = observation.trustError {
                        Text(reason)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .textSelection(.enabled)
                    }
                    InfoRow("Expected authority") {
                        Text(observation.expected ? "Yes" : "No")
                            .foregroundStyle(observation.expected ? Color.secondary : Color.red)
                    }
                }

                if let from = observation.notBefore, let until = observation.notAfter {
                    Section("Validity") {
                        InfoRow("Valid from") { Text(from.formatted(date: .abbreviated, time: .shortened)) }
                        InfoRow("Valid until") {
                            Text(until.formatted(date: .abbreviated, time: .shortened))
                                .foregroundStyle(until < .now ? Color.red : Color.secondary)
                        }
                        InfoRow("Remaining") { Text(remaining(until)) }
                    }
                }

                if let fingerprint = observation.fingerprint {
                    Section {
                        Text(fingerprint)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    } header: {
                        Text(verbatim: "SHA-256")
                    } footer: {
                        Text("The certificate's fingerprint. Compare it with the one another network or device sees: a different value for the same site means someone in between re-signs the traffic.")
                    }
                }

                if observation.subject == nil, observation.notAfter == nil {
                    Section {
                        Text("This check was saved before certificate details were kept. Run the connection check again to see them.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Certificate")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var symbol: String {
        if observation.error != nil { return "questionmark.circle" }
        return fine ? "checkmark.seal.fill" : "xmark.seal.fill"
    }

    private var color: Color {
        if observation.error != nil { return .secondary }
        return fine ? .green : .red
    }

    private var verdict: String {
        if observation.error != nil { return String(localized: "The site could not be reached to read its certificate.") }
        if !observation.trusted { return String(localized: "iOS does not trust this certificate: something between you and the site may be re-signing HTTPS.") }
        if !observation.expected { return String(localized: "Issued by an authority this site does not use: likely a proxy, an antivirus or an attacker re-signing HTTPS.") }
        return String(localized: "Issued by the authority this site normally uses.")
    }

    private func remaining(_ until: Date) -> String {
        let days = Calendar(identifier: .gregorian).dateComponents([.day], from: .now, to: until).day ?? 0
        if days < 0 { return String(localized: "Expired") }
        return String(localized: "\(days) days")
    }
}
