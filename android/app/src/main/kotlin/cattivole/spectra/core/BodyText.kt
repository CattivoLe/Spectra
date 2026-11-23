package cattivole.spectra.core

/**
 * A long text cut into rows a screen can draw one by one, with every position in it still counted from its start:
 * a search finds a match anywhere and knows the row it is on.
 */
class BodyText(val text: String) {
    /** One row: the text from [start] up to [end], without its line break. */
    data class Row(val start: Int, val end: Int)

    val rows: List<Row> = buildList {
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val end = if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
            // Minified JSON is one line of megabytes: no row is longer than a screen can lay out quickly.
            var from = start
            while (end - from > MAX_ROW) {
                // Not between the halves of an emoji.
                val cut = (from + MAX_ROW).let { if (text[it - 1].isHighSurrogate()) it - 1 else it }
                add(Row(from, cut))
                from = cut
            }
            add(Row(from, end))
            if (newline == text.length) break
            start = newline + 1
        }
    }

    val syntax: List<JsonSyntax.Span> = if (JsonSyntax.looksLikeJson(text)) JsonSyntax.spans(text) else emptyList()

    /** Where [needle] starts, each place once, case apart. At most [MAX_MATCHES]. */
    fun find(needle: String): IntArray {
        if (needle.isEmpty()) return IntArray(0)
        val found = ArrayList<Int>()
        var at = text.indexOf(needle, 0, ignoreCase = true)
        while (at >= 0 && found.size < MAX_MATCHES) {
            found.add(at)
            at = text.indexOf(needle, at + needle.length, ignoreCase = true)
        }
        return found.toIntArray()
    }

    /** The row the position is on. */
    fun rowOf(position: Int): Int {
        var low = 0
        var high = rows.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (rows[middle].start <= position) low = middle else high = middle - 1
        }
        return low
    }

    /** The coloured runs that touch [row]. */
    fun syntax(row: Row): List<JsonSyntax.Span> {
        // The first run that ends after the row starts; they are in order and do not overlap.
        var low = 0
        var high = syntax.size
        while (low < high) {
            val middle = (low + high) / 2
            if (syntax[middle].end <= row.start) low = middle + 1 else high = middle
        }
        val touching = ArrayList<JsonSyntax.Span>()
        while (low < syntax.size && syntax[low].start < row.end) touching.add(syntax[low++])
        return touching
    }

    companion object {
        /** What a screen can carry; the rest of a longer body is left out, and the screen says so. */
        const val LIMIT = 1_000_000
        const val MAX_ROW = 1_000
        const val MAX_MATCHES = 20_000
    }
}

/** A text opened on its own screen, handed over by the screen that shows its start. */
class OpenText(val title: String, val text: String)
