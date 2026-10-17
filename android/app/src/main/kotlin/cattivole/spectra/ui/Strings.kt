package cattivole.spectra.ui

import java.util.Locale

/**
 * Text the reader sees, keyed by its English wording, like the iOS app's
 * string catalog. Translations live in `spectra/strings-<lang>.tsv`; a line
 * that is missing stays English. Formats use `String.format` placeholders.
 */
object Strings {
    @Volatile private var loaded: Pair<String, Map<String, String>> = "" to emptyMap()

    /** The table follows the app language, which Android can change while the process lives. */
    fun of(english: String): String {
        val language = Locale.getDefault().language
        var current = loaded
        if (current.first != language) {
            current = language to load(language)
            loaded = current
        }
        return current.second[english] ?: english
    }

    private fun load(language: String): Map<String, String> {
        if (language == "en") return emptyMap()
        val stream = Strings::class.java.classLoader?.getResourceAsStream("spectra/strings-$language.tsv")
            ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                line.substring(0, tab).unescape() to line.substring(tab + 1).unescape()
            }.toMap()
        }
    }

    private fun String.unescape(): String = replace("\\n", "\n").replace("\\t", "\t")
}

/** The reader's wording for [english]. */
fun tr(english: String): String = Strings.of(english)

/** The reader's wording for a format, filled with [args]. */
fun tr(format: String, vararg args: Any?): String = String.format(Locale.getDefault(), Strings.of(format), *args)
