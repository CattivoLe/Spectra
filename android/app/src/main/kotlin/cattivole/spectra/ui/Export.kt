package cattivole.spectra.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import cattivole.spectra.core.Exporter
import cattivole.spectra.core.HistoryEntry
import cattivole.spectra.core.HistoryStore
import cattivole.spectra.platform.phoneName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/** Device fields for the WiGLE header, read from the running app. */
private fun exportDevice(context: Context): Exporter.Device {
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"
    return Exporter.Device(appVersion = version, model = Build.MODEL ?: "Android", release = Build.VERSION.RELEASE ?: "", brand = Build.BRAND ?: "")
}

/**
 * Writes an export to cacheDir/exports and hands it to a share chooser through
 * the app's FileProvider. Any reader that accepts text can take it.
 */
fun shareExport(context: Context, format: Exporter.Format, entries: List<HistoryEntry>) {
    val file = Exporter.file(format, entries, exportDevice(context))
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val out = File(dir, file.name)
    out.writeText(file.contents)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    val mime = if (format == Exporter.Format.KML) "application/vnd.google-earth.kml+xml" else "text/csv"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The history file another Spectra can import. CSV and the map formats stay for other tools. */
fun shareHistory(context: Context, entries: List<HistoryEntry>) {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val out = File(dir, "spectra-$stamp.json")
    val origins = entries.mapNotNull { it.importedFrom }.toSet()
    val from = if (origins.size == 1) origins.first() else phoneName(context)
    out.writeText(HistoryStore.encodeDocument(entries, from))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Writes a captured `.pcap` to cacheDir/exports and hands it to a share chooser. */
fun sharePcap(context: Context, bytes: ByteArray, label: String) {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val out = File(dir, "spectra-$label-$stamp.pcap")
    out.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.tcpdump.pcap"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Writes a text recording to cacheDir/exports and hands it to a share chooser. */
fun shareTextFile(context: Context, name: String, contents: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val out = File(dir, name)
    out.writeText(contents)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Writes the CA certificate into Downloads as `spectra-ca.crt`.
 * Android refuses to install a CA from an app, so the user picks this file in Settings.
 */
fun saveCaCertificate(context: Context, der: ByteArray): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val pem = certificatePem(der).toByteArray(Charsets.US_ASCII)
    return runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching { resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(CA_FILE)) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, CA_FILE)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(pem) } ?: return false
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}

fun openSecuritySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
}

private fun certificatePem(der: ByteArray): String {
    val body = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
    return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
}

private const val CA_FILE = "spectra-ca.crt"

/** A share button that opens a menu of export formats. */
@Composable
fun ExportMenu(entries: List<HistoryEntry>) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.IosShare, contentDescription = tr("Export"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(tr("Spectra")) },
                onClick = {
                    open = false
                    shareHistory(context, entries)
                },
            )
            for (format in Exporter.Format.entries) {
                DropdownMenuItem(
                    text = { Text(format.label) },
                    onClick = {
                        open = false
                        shareExport(context, format, entries)
                    },
                )
            }
        }
    }
}
