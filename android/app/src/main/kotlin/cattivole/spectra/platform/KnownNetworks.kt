package cattivole.spectra.platform

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every network a linked sensor has joined and handed over. The sensor only
 * keeps a short unsent buffer; this is the list it draws from.
 */
class KnownNetworks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("spectra-known-wifi", Context.MODE_PRIVATE)

    fun remember(ssid: String, password: String) {
        if (ssid.isEmpty()) return
        val rows = all().filterNot { it.first == ssid }.toMutableList()
        rows.add(0, ssid to password)
        save(rows)
    }

    fun forget(ssid: String) {
        save(all().filterNot { it.first == ssid })
    }

    /** Newest first. */
    fun all(): List<Pair<String, String>> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val ssid = obj.optString("ssid")
                if (ssid.isNotEmpty()) add(ssid to obj.optString("pass"))
            }
        }
    }

    private fun save(rows: List<Pair<String, String>>) {
        val array = JSONArray()
        rows.forEach { (ssid, pass) -> array.put(JSONObject().put("ssid", ssid).put("pass", pass)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}
