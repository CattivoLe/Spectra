package cattivole.spectra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cattivole.spectra.core.LinkInspection
import cattivole.spectra.core.LinkInspector
import cattivole.spectra.core.QrPayload
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import cattivole.spectra.core.SiteReport
import cattivole.spectra.platform.SiteProbe
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Opens the system scanner sheet. The code is read, never opened; the camera
 * picture stays with Google Play services and does not reach the app.
 */
private fun scanQr(context: android.content.Context, onRead: (String) -> Unit, onUnavailable: () -> Unit) {
    try {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.takeIf { it.isNotBlank() }?.let(onRead) }
            .addOnFailureListener { onUnavailable() }
    } catch (_: Exception) {
        onUnavailable()
    }
}

/** The LAN tab's way in to the link check. */
fun LazyListScope.linkCheckEntry(model: cattivole.spectra.SpectraModel, nav: Navigator) {
    card(
        header = tr("Checks"),
        key = "checks",
        dividerInset = 70.dp,
        rows = listOf(
            {
                ListRow(
                    title = tr("Check a link"),
                    subtitle = tr("Who really owns the site, look-alike names and phishing bait."),
                    leading = { IconTile(Icons.Rounded.Link, MaterialTheme.colorScheme.primary) },
                    onClick = { nav.linkCheck() },
                )
            },
            speedTestRow(model, nav),
            {
                ListRow(
                    title = tr("Traceroute"),
                    subtitle = tr("The path from this phone to any site or address, hop by hop, on a map."),
                    leading = { IconTile(Icons.Rounded.Route, MaterialTheme.colorScheme.primary) },
                    onClick = { nav.traceRoute() },
                )
            },
        ),
    )
}

private fun LinkInspection.Verdict.title(): String = when (this) {
    LinkInspection.Verdict.NO_FLAGS -> "No warning signs"
    LinkInspection.Verdict.CAUTION -> "Look closer"
    LinkInspection.Verdict.SUSPICIOUS -> "Suspicious"
    LinkInspection.Verdict.DANGEROUS -> "Looks like phishing"
}

private fun LinkInspection.Verdict.advice(): String = when (this) {
    LinkInspection.Verdict.NO_FLAGS -> "The address itself shows nothing wrong. That does not prove the page is safe."
    LinkInspection.Verdict.CAUTION -> "One thing about this address deserves a second look before you open it."
    LinkInspection.Verdict.SUSPICIOUS -> "Several things about this address are typical of phishing. Do not enter passwords or card numbers."
    LinkInspection.Verdict.DANGEROUS -> "Do not open this link and do not enter anything on it."
}

private fun LinkInspection.Verdict.icon(): ImageVector = when (this) {
    LinkInspection.Verdict.NO_FLAGS -> Icons.Rounded.VerifiedUser
    LinkInspection.Verdict.CAUTION -> Icons.Rounded.Info
    LinkInspection.Verdict.SUSPICIOUS -> Icons.Rounded.GppMaybe
    LinkInspection.Verdict.DANGEROUS -> Icons.Rounded.GppBad
}

@Composable
private fun LinkInspection.Verdict.color(): Color = when (this) {
    LinkInspection.Verdict.NO_FLAGS -> Spectra.colors.success
    LinkInspection.Verdict.CAUTION -> Spectra.colors.weak
    LinkInspection.Verdict.SUSPICIOUS -> Spectra.colors.warning
    LinkInspection.Verdict.DANGEROUS -> Spectra.colors.danger
}

@Composable
private fun LinkInspection.Severity.color(): Color = when (this) {
    LinkInspection.Severity.INFO -> MaterialTheme.colorScheme.outline
    LinkInspection.Severity.CAUTION -> Spectra.colors.warning
    LinkInspection.Severity.DANGER -> Spectra.colors.danger
}

/** The finding in the reader's words. Brand names are translated on their own. */
private fun LinkInspection.Finding.shown(): String = tr(text, *args.map { tr(it) }.toTypedArray())

/**
 * A link, or a whole message with one in it, read without opening it: nothing
 * is fetched, so the sender never learns the message was looked at.
 */
@Composable
fun LinkCheckScreen(nav: Navigator) {
    var text by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val inspection = checked?.let { LinkInspector.inspect(it) }
    // The on-demand certificate and registry check, for the link on screen.
    var site by remember { mutableStateOf<SiteReport?>(null) }
    var siteBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // A scanned Wi-Fi join code, explained instead of inspected as a link.
    var wifiCode by remember { mutableStateOf<QrPayload.Wifi?>(null) }
    var scannerMissing by remember { mutableStateOf(false) }
    wifiCode?.let { code ->
        AlertDialog(
            onDismissRequest = { wifiCode = null },
            title = { Text(tr("Wi-Fi network code")) },
            text = {
                Text(
                    listOfNotNull(
                        tr("This code joins a Wi-Fi network named “%s”.", code.ssid),
                        code.security?.let { tr("Security: %s.", it) }
                            ?: tr("The network has no password: traffic on it can be read by anyone nearby."),
                        if (code.hidden) tr("The network is hidden, which is unusual for a public one.") else null,
                        tr("Spectra did not join it. Join only networks you expected to find here."),
                    ).joinToString(" "),
                )
            },
            confirmButton = { TextButton(onClick = { wifiCode = null }) { Text("OK") } },
        )
    }
    LaunchedEffect(checked) {
        site = null
        siteBusy = false
    }
    val noLink = checked != null && inspection == null && checked!!.isNotBlank()
    SpectraScreen(title = tr("Check a link"), onBack = nav::back) {
        freeCard(
            "input",
            footer = tr("Checked on this phone: the link is not sent anywhere and the page is not opened."),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    checked = null
                },
                placeholder = { Text(tr("Paste a link or a whole message")) },
                trailingIcon = if (text.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = {
                            text = ""
                            checked = null
                        }) { Icon(Icons.Rounded.Close, contentDescription = tr("Clear")) }
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = {
                    focus.clearFocus()
                    scanQr(context, onRead = { raw ->
                        when (val payload = QrPayload.classify(raw)) {
                            is QrPayload.Link -> {
                                text = payload.text
                                checked = payload.text
                            }
                            is QrPayload.Wifi -> wifiCode = payload
                            is QrPayload.Text -> {
                                text = payload.text
                                checked = payload.text
                            }
                        }
                    }, onUnavailable = { scannerMissing = true })
                }) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = tr("Scan QR code"), modifier = Modifier.size(18.dp))
                }
                OutlinedButton(onClick = {
                    val pasted = clipboard.getText()?.text?.trim().orEmpty()
                    if (pasted.isNotEmpty()) {
                        text = pasted
                        checked = pasted
                        focus.clearFocus()
                    }
                }) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(tr("Paste"), modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = {
                        checked = text
                        focus.clearFocus()
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(tr("Check"), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (scannerMissing) {
            card(key = "noscanner", rows = listOf {
                NoteRow(tr("The QR scanner needs Google Play services, which this phone does not have or has not finished setting up."), warning = false)
            })
        }
        if (noLink) {
            card(key = "nolink", rows = listOf { NoteRow(tr("No link found in that text."), warning = true) })
        }
        if (inspection != null) {
            inspectionCards(inspection, "top")
            // Only a web link with a name has a certificate and a registry record to ask about.
            val target = inspection.takeIf { it.scheme == "https" || it.scheme == "http" }?.takeIf { it.host.isNotEmpty() }
            if (target != null) {
                val named = target.site != target.host || target.site.any { it.isLetter() }
                card(
                    header = tr("Certificate and domain"),
                    key = "site",
                    footer = tr("Checking connects to the site's HTTPS port without loading a page, and asks the zone's own registry over RDAP. The site and the registry see your IP address."),
                    rows = buildList {
                        site?.findings()?.forEach { finding -> add { FindingLine(finding) } }
                        if (siteBusy) {
                            add { ProgressRow(tr("Asking the site and the registry…"), null) }
                        } else {
                            add {
                                ActionRow(
                                    if (site == null) tr("Check certificate and domain age") else tr("Check again"),
                                    Icons.Rounded.VerifiedUser,
                                    onClick = {
                                        siteBusy = true
                                        val link = checked
                                        scope.launch {
                                            // An account on a free host is not in any registry: its platform is.
                                            val asked = (target.asciiHost ?: target.site).let { name ->
                                                if (target.findings.any { it.kind == LinkInspection.Kind.FREE_HOSTING }) {
                                                    target.site.split('.').takeLast(2).joinToString(".")
                                                } else if (target.asciiHost != null) {
                                                    name.split('.').takeLast(target.site.count { it == '.' } + 1).joinToString(".")
                                                } else {
                                                    target.site
                                                }
                                            }
                                            val report = SiteProbe.probe(target.asciiHost ?: target.host, asked.takeIf { named })
                                            if (checked == link) {
                                                site = report
                                                siteBusy = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FindingLine(finding: LinkInspection.Finding) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            when (finding.severity) {
                LinkInspection.Severity.DANGER -> Icons.Rounded.GppBad
                LinkInspection.Severity.CAUTION -> Icons.Rounded.Warning
                LinkInspection.Severity.INFO -> Icons.Rounded.Info
            },
            contentDescription = null,
            tint = finding.severity.color(),
            modifier = Modifier.size(20.dp),
        )
        Text(finding.shown(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun LazyListScope.inspectionCards(inspection: LinkInspection, key: String) {
    val verdict = inspection.verdict
    freeCard("verdict-$key") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(verdict.icon(), verdict.color(), size = 56.dp)
            Column(Modifier.weight(1f)) {
                Text(tr(verdict.title()), style = MaterialTheme.typography.titleLarge)
                Text(tr(verdict.advice()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    card(
        header = tr("Address"),
        key = "address-$key",
        rows = buildList {
            if (inspection.site.isNotEmpty()) add { InfoRow(tr("Site"), inspection.site, mono = true, copyable = true) }
            if (inspection.host.isNotEmpty() && inspection.host != inspection.site) {
                add { InfoRow(tr("Full name"), inspection.host, mono = true, copyable = true) }
            }
            inspection.asciiHost?.let { ascii -> add { InfoRow(tr("In the address bar"), ascii, mono = true, copyable = true) } }
            add { InfoRow(tr("Link"), inspection.link, mono = true, copyable = true) }
        },
    )
    if (inspection.findings.isNotEmpty()) {
        card(
            header = tr("What stands out"),
            key = "findings-$key",
            rows = inspection.findings.sortedByDescending { it.severity }.map { finding -> { FindingLine(finding) } },
        )
    }
    inspection.nested.forEachIndexed { index, inner ->
        item(key = "nested-header-$key-$index") {
            Text(
                tr("The address inside this link"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, top = 16.dp, end = 16.dp),
            )
        }
        inspectionCards(inner, "$key-$index")
    }
}
