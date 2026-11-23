package cattivole.spectra.core

/**
 * Which hosts are decrypted, like SSL Proxying in Charles. In [Mode.ALL] every
 * host is; in [Mode.ONLY] the hosts matching [only] are, except those matching
 * [except]; in [Mode.EXCEPT] every host is, except those matching [except].
 *
 * A pattern is a host name with `*` for any run of characters and `?` for one.
 * `*.example.com` also matches `example.com` itself. A port after a colon is
 * ignored. Case does not matter.
 */
data class TlsHostFilter(
    val mode: Mode = Mode.ALL,
    val only: List<String> = emptyList(),
    val except: List<String> = emptyList(),
) {
    enum class Mode(val key: String) {
        ALL("all"), ONLY("only"), EXCEPT("except");

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: ALL
        }
    }

    /** True when a connection to [host] is decrypted. Without a name only [Mode.ONLY] says no. */
    fun allows(host: String?): Boolean = when (mode) {
        Mode.ALL -> true
        Mode.ONLY -> host != null && only.any { matches(it, host) } && except.none { matches(it, host) }
        Mode.EXCEPT -> host == null || except.none { matches(it, host) }
    }

    /** Adds [host] to the exceptions; when everything was decrypted, the filter starts using them. */
    fun excluding(host: String): TlsHostFilter {
        val clean = normalize(host) ?: return this
        val list = if (except.any { it.equals(clean, true) }) except else except + clean
        return copy(mode = if (mode == Mode.ALL) Mode.EXCEPT else mode, except = list)
    }

    /** True when [host] is already left out by this filter. */
    fun excludes(host: String): Boolean = !allows(host)

    companion object {
        /** Trims a typed pattern; null when nothing is left or it has characters a host cannot have. */
        fun normalize(pattern: String): String? {
            var text = pattern.trim().lowercase()
            text = text.removePrefix("https://").removePrefix("http://").substringBefore('/')
            if (text.startsWith("[")) text = text.substringAfter('[').substringBefore(']')
            else if (text.count { it == ':' } == 1) text = text.substringBefore(':')
            text = text.trimEnd('.')
            if (text.isEmpty() || text.length > 253) return null
            if (text.any { !(it.isLetterOrDigit() || it in ".-_*?:") }) return null
            return text
        }

        fun matches(pattern: String, host: String): Boolean {
            val p = normalize(pattern) ?: return false
            val h = host.trim().lowercase().trimEnd('.')
            if (p.startsWith("*.") && h == p.removePrefix("*.")) return true
            return glob(p, h)
        }

        private fun glob(pattern: String, text: String): Boolean {
            var p = 0
            var t = 0
            var star = -1
            var mark = 0
            while (t < text.length) {
                when {
                    p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) -> {
                        p++
                        t++
                    }
                    p < pattern.length && pattern[p] == '*' -> {
                        star = p++
                        mark = t
                    }
                    star >= 0 -> {
                        p = star + 1
                        t = ++mark
                    }
                    else -> return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}
