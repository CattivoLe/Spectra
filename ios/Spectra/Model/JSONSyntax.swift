import Foundation

/// Splits JSON text into the pieces a reader tells apart by colour. It does not validate: a body cut half-way, or one
/// with a mistake in it, is coloured as far as it makes sense.
nonisolated enum JSONSyntax {
    enum Kind: Equatable, Sendable { case key, string, number, literal }

    /// A run of one kind, in UTF-16 units of the text, as `NSAttributedString` counts.
    struct Span: Equatable, Sendable {
        var location: Int
        var length: Int
        var kind: Kind
        var range: NSRange { NSRange(location: location, length: length) }
    }

    /// Text that starts like JSON and is worth colouring.
    static func looksLikeJSON(_ text: String) -> Bool {
        guard let first = text.unicodeScalars.first(where: { !CharacterSet.whitespacesAndNewlines.contains($0) && $0 != "\u{FEFF}" }) else { return false }
        return first == "{" || first == "["
    }

    static func spans(_ text: String) -> [Span] {
        let units = Array(text.utf16)
        let count = units.count
        var spans: [Span] = []
        var i = 0
        while i < count {
            switch units[i] {
            case 0x22: // "
                var end = i + 1
                while end < count, units[end] != 0x22 {
                    end += units[end] == 0x5C ? 2 : 1 // a backslash takes the next character with it
                }
                end = min(end + 1, count)
                var next = end
                while next < count, isSpace(units[next]) { next += 1 }
                let isKey = next < count && units[next] == 0x3A
                spans.append(Span(location: i, length: end - i, kind: isKey ? .key : .string))
                i = end
            case 0x2D, 0x30...0x39: // - or a digit
                var end = i + 1
                while end < count, isNumberPart(units[end]) { end += 1 }
                spans.append(Span(location: i, length: end - i, kind: .number))
                i = end
            case 0x61...0x7A: // a bare word: true, false, null
                var end = i + 1
                while end < count, (0x61...0x7A).contains(units[end]) { end += 1 }
                let word = String(decoding: units[i..<end], as: UTF16.self)
                if ["true", "false", "null"].contains(word) {
                    spans.append(Span(location: i, length: end - i, kind: .literal))
                }
                i = end
            default:
                i += 1
            }
        }
        return spans
    }

    private static func isSpace(_ unit: UInt16) -> Bool { unit == 0x20 || (0x09...0x0D).contains(unit) }

    private static func isNumberPart(_ unit: UInt16) -> Bool {
        (0x30...0x39).contains(unit) || [0x2E, 0x65, 0x45, 0x2B, 0x2D].contains(unit)
    }
}
