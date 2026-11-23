package cattivole.spectra.core

/**
 * Splits JSON text into the pieces a reader tells apart by colour. It does not validate: a body cut half-way, or one
 * with a mistake in it, is coloured as far as it makes sense. Mirrors the iOS `JSONSyntax`.
 */
object JsonSyntax {
    enum class Kind { KEY, STRING, NUMBER, LITERAL }

    /** A run of one kind, from [start] up to [end]. */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    private val literals = setOf("true", "false", "null")

    /** Text that starts like JSON and is worth colouring. */
    fun looksLikeJson(text: String): Boolean {
        val first = text.firstOrNull { !it.isWhitespace() && it != '﻿' } ?: return false
        return first == '{' || first == '['
    }

    fun spans(text: String): List<Span> {
        val spans = ArrayList<Span>()
        val count = text.length
        var i = 0
        while (i < count) {
            val c = text[i]
            when {
                c == '"' -> {
                    var end = i + 1
                    // A backslash takes the next character with it.
                    while (end < count && text[end] != '"') end += if (text[end] == '\\') 2 else 1
                    end = minOf(end + 1, count)
                    var next = end
                    while (next < count && text[next].isWhitespace()) next++
                    spans.add(Span(i, end, if (next < count && text[next] == ':') Kind.KEY else Kind.STRING))
                    i = end
                }
                c == '-' || c in '0'..'9' -> {
                    var end = i + 1
                    while (end < count && (text[end] in '0'..'9' || text[end] in ".eE+-")) end++
                    spans.add(Span(i, end, Kind.NUMBER))
                    i = end
                }
                c in 'a'..'z' -> {
                    var end = i + 1
                    while (end < count && text[end] in 'a'..'z') end++
                    if (text.substring(i, end) in literals) spans.add(Span(i, end, Kind.LITERAL))
                    i = end
                }
                else -> i++
            }
        }
        return spans
    }
}
