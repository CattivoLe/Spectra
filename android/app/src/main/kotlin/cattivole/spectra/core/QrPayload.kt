package cattivole.spectra.core

/**
 * What a scanned code carries. Most phishing codes are plain links; a Wi-Fi
 * code is the other kind worth explaining before anyone acts on it.
 */
sealed class QrPayload {
    /** A link, or text with a link in it, for the link inspector. */
    data class Link(val text: String) : QrPayload()

    /** A Wi-Fi join code: network name, security and whether the network is hidden. */
    data class Wifi(val ssid: String, val security: String?, val hidden: Boolean) : QrPayload()

    /** Anything else: contact cards, plain words, numbers. */
    data class Text(val text: String) : QrPayload()

    companion object {
        fun classify(raw: String): QrPayload {
            val text = raw.trim()
            if (text.uppercase().startsWith("WIFI:")) return wifi(text)
            return if (LinkInspector.extractLink(text) != null) Link(text) else Text(text)
        }

        /** `WIFI:T:WPA;S:Cafe;P:secret;H:true;;` with `\` escaping `;`, `:`, `,` and `\`. */
        private fun wifi(text: String): QrPayload {
            val fields = HashMap<String, String>()
            val key = StringBuilder()
            val value = StringBuilder()
            var readingKey = true
            var escaped = false
            for (char in text.drop(5)) {
                if (escaped) {
                    value.append(char)
                    escaped = false
                    continue
                }
                when {
                    char == '\\' && !readingKey -> escaped = true
                    char == ':' && readingKey -> readingKey = false
                    char == ';' && !readingKey -> {
                        fields[key.toString().uppercase()] = value.toString()
                        key.clear()
                        value.clear()
                        readingKey = true
                    }
                    // Two semicolons close the record.
                    char == ';' -> key.clear()
                    readingKey -> key.append(char)
                    else -> value.append(char)
                }
            }
            if (!readingKey && key.isNotEmpty()) fields[key.toString().uppercase()] = value.toString()
            val security = fields["T"]?.takeIf { it.isNotEmpty() && it.lowercase() != "nopass" }?.uppercase()
            return Wifi(fields["S"].orEmpty(), security, fields["H"].orEmpty().lowercase() == "true")
        }
    }
}
