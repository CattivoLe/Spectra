import SwiftUI
import LocalChatKit

/// Shows a model answer with formatting: paragraphs, headings, lists, quotes and code blocks.
struct AssistantMarkdownView: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(MarkdownParser.parse(text).enumerated()), id: \.offset) { _, block in
                view(for: block)
            }
        }
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func view(for block: MarkdownBlock) -> some View {
        switch block {
        case .paragraph(let text):
            Text(inline(text))
        case .heading(let level, let text):
            Text(inline(text)).font(level == 1 ? .title2.bold() : level == 2 ? .title3.bold() : .headline)
        case .listItem(let marker, let text, let indent):
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(marker).foregroundStyle(.secondary)
                Text(inline(text))
            }
            .padding(.leading, CGFloat(indent) * 16)
        case .quote(let text):
            Text(inline(text))
                .foregroundStyle(.secondary)
                .padding(.leading, 10)
                .overlay(alignment: .leading) { Rectangle().fill(.secondary.opacity(0.5)).frame(width: 3) }
        case .code(let language, let code, _):
            AssistantCodeBlock(language: language, code: code)
        }
    }

    /// Bold, italic, `code` and links inside a line. If the markup is broken, the text is shown as is.
    private func inline(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        var result = (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
        Self.highlightNetworkData(&result)
        return result
    }

    // MARK: - Network data highlighting

    private static let dataPatterns: [(NSRegularExpression, Color)] = {
        let specs: [(String, Color)] = [
            (#"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?::\d{1,5})?(?:/\d{1,2})?\b"#, .blue),
            (#"\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b"#, .orange),
            (#"\b\d{1,5}/(?:tcp|udp)\b"#, .purple),
            (#"\b(?:HTTPS?|SSH|RTSP|RTMP|SFTP|FTP|DNS|DHCP|SNMP|NTP|SMB|AFP|MQTT|WPA[23]?|WEP|WPS|ARP|ICMP|TCP|UDP|TLS|SSL|mDNS|UPnP|SSDP)\b"#, .teal),
        ]
        return specs.compactMap { pattern, color in
            (try? NSRegularExpression(pattern: pattern)).map { ($0, color) }
        }
    }()

    private static let signalRegex = try? NSRegularExpression(pattern: #"-(\d{2,3})\s*(?:dBm|дБм)"#)

    private static func highlightNetworkData(_ attributed: inout AttributedString) {
        let plain = String(attributed.characters)
        guard !plain.isEmpty else { return }
        let fullRange = NSRange(plain.startIndex..., in: plain)

        for (regex, color) in dataPatterns {
            for match in regex.matches(in: plain, range: fullRange) {
                guard let range = Range(match.range, in: plain) else { continue }
                let lo = plain.distance(from: plain.startIndex, to: range.lowerBound)
                let hi = plain.distance(from: plain.startIndex, to: range.upperBound)
                let start = attributed.characters.index(attributed.startIndex, offsetBy: lo)
                let end = attributed.characters.index(attributed.startIndex, offsetBy: hi)
                attributed[start..<end].foregroundColor = color
            }
        }

        if let signalRegex {
            for match in signalRegex.matches(in: plain, range: fullRange) {
                guard let range = Range(match.range, in: plain),
                      let valRange = Range(match.range(at: 1), in: plain),
                      let dBm = Int(plain[valRange]) else { continue }
                let lo = plain.distance(from: plain.startIndex, to: range.lowerBound)
                let hi = plain.distance(from: plain.startIndex, to: range.upperBound)
                let start = attributed.characters.index(attributed.startIndex, offsetBy: lo)
                let end = attributed.characters.index(attributed.startIndex, offsetBy: hi)
                attributed[start..<end].foregroundColor = switch dBm {
                case 0..<50: Color.green
                case 50..<70: Color.orange
                default: Color.red
                }
            }
        }
    }
}

private struct AssistantCodeBlock: View {
    let language: String?
    let code: String
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language ?? String(localized: "code")).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc") {
                    UIPasteboard.general.string = code
                    copied = true
                    Task {
                        try? await Task.sleep(for: .seconds(1.5))
                        copied = false
                    }
                }
                .font(.caption)
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.quaternary)

            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(.system(.callout, design: .monospaced))
                    .padding(10)
            }
        }
        .background(.quinary, in: RoundedRectangle(cornerRadius: 10))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
