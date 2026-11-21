package cattivole.spectra.core

import org.json.JSONObject

/**
 * The third-party notices the Licenses screen shows, read from `spectra/licenses.json` (shared with iOS, built from
 * the components' own license files; see THIRD_PARTY_LICENSES.md). Pure, so the catalog is checked by tests.
 */
object ThirdPartyLicenses {
    enum class Group { APP, CODE, FIRMWARE, DATA }

    data class Component(
        val name: String,
        val group: Group,
        val version: String,
        val url: String,
        /** The license by name, as shown in the list: "MIT", "Apache-2.0", "Data". */
        val license: String,
        val copyright: String,
        /** Extra notice the component asks binaries to carry (a NOTICE file, a source offer, a credit). */
        val notice: String,
        /** The license's full text, or null when there is none to show (public domain, data credits). */
        val text: String?,
    )

    const val RESOURCE = "spectra/licenses.json"

    /** The components that ship in the app for [platform] ("android" or "ios"), grouped as listed. */
    fun parse(json: String, platform: String): List<Component> {
        val root = JSONObject(json)
        val texts = root.getJSONObject("texts")
        val list = root.getJSONArray("components")
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { item ->
            val platforms = item.getJSONArray("platforms")
            (0 until platforms.length()).any { platforms.getString(it) == platform }
        }.map { item ->
            val key = item.optString("text").takeIf { it.isNotEmpty() && !item.isNull("text") }
            Component(
                name = item.getString("name"),
                group = Group.valueOf(item.getString("group").uppercase()),
                version = item.optString("version"),
                url = item.optString("url"),
                license = item.getString("license"),
                copyright = item.optString("copyright"),
                notice = item.optString("notice"),
                text = key?.let { texts.getString(it) },
            )
        }
    }

    /** A list item or a numbered clause starts its own line: "*", "-", "•", "1.", "(a)", "a)". */
    private val ITEM = Regex("""^(?:[*\-•]\s|\d+[.)]\s|\([a-z0-9]+\)\s|[a-z][.)]\s)""")

    /**
     * License files are wrapped at about 80 columns; on a phone those breaks land mid-line. Lines of one paragraph
     * are joined back, while blank lines, list items and numbered clauses keep their own lines.
     */
    fun reflow(text: String): String {
        val out = StringBuilder()
        var previousBlank = true
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {
                    if (!previousBlank) out.append("\n\n")
                    previousBlank = true
                }
                previousBlank -> {
                    out.append(line)
                    previousBlank = false
                }
                ITEM.containsMatchIn(line) -> out.append('\n').append(line)
                else -> out.append(' ').append(line)
            }
        }
        return out.toString().trimEnd()
    }

    fun load(platform: String = "android"): List<Component> {
        val stream = ThirdPartyLicenses::class.java.classLoader?.getResourceAsStream(RESOURCE) ?: return emptyList()
        return parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }, platform)
    }
}
