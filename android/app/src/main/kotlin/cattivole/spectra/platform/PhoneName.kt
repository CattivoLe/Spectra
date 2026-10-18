package cattivole.spectra.platform

import android.content.Context
import android.os.Build
import android.provider.Settings

/** The name this phone shows for itself. A linked sensor prints it while the link is up. */
fun phoneName(context: Context): String {
    val named = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    val raw = named?.trim()?.takeIf { it.isNotBlank() }
        ?: Build.MODEL?.trim()?.takeIf { it.isNotBlank() }
        ?: "Android"
    val bytes = raw.encodeToByteArray()
    if (bytes.size <= 31) return raw
    var end = 31
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}
