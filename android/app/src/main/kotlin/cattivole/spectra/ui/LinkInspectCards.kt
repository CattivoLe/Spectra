package cattivole.spectra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cattivole.spectra.core.DiscoveredPort
import cattivole.spectra.core.DnsReport
import cattivole.spectra.core.LinkReport
import cattivole.spectra.core.RemoteNote
import cattivole.spectra.core.TlsCheck
import cattivole.spectra.core.TlsObservation
import cattivole.spectra.platform.LinkProbe
import java.util.Locale

/** Whether HTTPS to well-known sites arrives untouched. */
internal fun LazyListScope.tlsCard(check: TlsCheck) {
    card(
        header = tr("HTTPS certificates"),
        key = "tls",
        footer = tr("Four well-known sites are asked for their certificates without loading a page. A proxy, an antivirus or an attacker on the network has to sign them itself, and that shows here."),
        dividerInset = 70.dp,
        rows = check.observations.map<TlsObservation, @Composable () -> Unit> { seen ->
            {
                val good = seen.trusted && seen.expected
                var open by remember { mutableStateOf(false) }
                if (open) CertificateDialog(seen) { open = false }
                ListRow(
                    title = seen.host,
                    subtitle = seen.issuer ?: seen.error.orEmpty(),
                    detail = seen.trustError?.takeIf { seen.error == null }?.let { tr(it) },
                    onClick = { open = true },
                    leading = {
                        IconTile(
                            when {
                                seen.error != null -> Icons.Rounded.HelpOutline
                                good -> Icons.Rounded.Verified
                                else -> Icons.Rounded.GppBad
                            },
                            when {
                                seen.error != null -> MaterialTheme.colorScheme.outline
                                good -> Spectra.colors.success
                                else -> Spectra.colors.danger
                            },
                        )
                    },
                )
            }
        } + {
            NoteRow(
                when {
                    check.reachable == 0 -> tr("Could not reach the reference sites to compare certificates.")
                    check.intercepted -> tr("Certificates do not come from the expected authorities: something between you and the internet re-signs HTTPS, such as a corporate proxy, an antivirus or an attacker on the Wi-Fi.")
                    else -> tr("Certificates come from the expected authorities: no sign of HTTPS interception.")
                },
                warning = check.reachable > 0 && check.intercepted,
            )
        },
    )
}

/** What one reference site's certificate looked like from this network. */
@Composable
private fun CertificateDialog(seen: TlsObservation, onDismiss: () -> Unit) {
    val fine = seen.error == null && seen.trusted && seen.expected
    val verdict = when {
        seen.error != null -> tr("The site could not be reached to read its certificate.")
        !seen.trusted -> tr("Android does not trust this certificate: something between you and the site may be re-signing HTTPS.")
        !seen.expected -> tr("Issued by an authority this site does not use: likely a proxy, an antivirus or an attacker re-signing HTTPS.")
        else -> tr("Issued by the authority this site normally uses.")
    }
    val dates = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                when {
                    seen.error != null -> Icons.Rounded.HelpOutline
                    fine -> Icons.Rounded.Verified
                    else -> Icons.Rounded.GppBad
                },
                contentDescription = null,
                tint = when {
                    seen.error != null -> MaterialTheme.colorScheme.outline
                    fine -> Spectra.colors.success
                    else -> Spectra.colors.danger
                },
            )
        },
        title = { Text(seen.host) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(verdict, color = MaterialTheme.colorScheme.onSurfaceVariant)
                seen.error?.let { CertSection(tr("Error"), listOf(it)) }
                if (seen.error == null) {
                    CertSection(
                        tr("Issued to"),
                        listOfNotNull(
                            seen.subject?.let { tr("Common name") + ": " + it },
                            seen.names?.takeIf { it.isNotEmpty() }?.let { tr("Names covered") + ":\n" + it.joinToString("\n") },
                        ),
                    )
                    CertSection(
                        tr("Issued by"),
                        listOfNotNull(
                            seen.issuer?.let { tr("Organization") + ": " + it },
                            seen.issuerCommonName?.takeIf { it != seen.issuer }?.let { tr("Certificate") + ": " + it },
                            seen.chain?.takeIf { it.isNotEmpty() }?.let { chain ->
                                tr("Chain of trust") + ":\n" + chain.mapIndexed { index, name -> "  ".repeat(index) + "↳ " + name }.joinToString("\n")
                            },
                            tr("Trusted by Android") + ": " + (if (seen.trusted) tr("Yes") else tr("No")),
                            seen.trustError?.let { tr(it) },
                            tr("Expected authority") + ": " + (if (seen.expected) tr("Yes") else tr("No")),
                        ),
                    )
                    val from = seen.notBefore
                    val until = seen.notAfter
                    if (from != null && until != null) {
                        val days = ((until - System.currentTimeMillis()) / 86_400_000L).toInt()
                        CertSection(
                            tr("Validity"),
                            listOf(
                                tr("Valid from") + ": " + dates.format(java.util.Date(from)),
                                tr("Valid until") + ": " + dates.format(java.util.Date(until)),
                                tr("Remaining") + ": " + if (days < 0) tr("Expired") else tr("%d days", days),
                            ),
                        )
                    }
                    seen.fingerprint?.let { print ->
                        CertSection("SHA-256", listOf(print), mono = true)
                        Text(
                            tr("The certificate's fingerprint. Compare it with the one another network or device sees: a different value for the same site means someone in between re-signs the traffic."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (seen.subject == null && seen.notAfter == null) {
                        Text(
                            tr("This check was saved before certificate details were kept. Run the connection check again to see them."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

@Composable
private fun CertSection(title: String, lines: List<String>, mono: Boolean = false) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                lines.joinToString("\n"),
                style = if (mono) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Who answers this network's name lookups. */
internal fun LazyListScope.resolverCard(resolver: DnsReport, publicIp: String?) {
    card(
        header = "DNS",
        key = "resolver",
        footer = tr("The address the lookups come from is learned by resolving whoami.akamai.net; ipwho.is names its owner. Encrypted DNS is tried against Cloudflare over HTTPS and over TLS on port 853."),
        rows = buildList {
            if (resolver.configured.isNotEmpty()) {
                add { InfoRow(tr("Configured"), resolver.configured.take(2).joinToString(", "), mono = true) }
            }
            resolver.resolverIp?.let { ip ->
                add { InfoRow(tr("Answers from"), resolver.resolverName ?: ip) }
                if (resolver.resolverName != null) {
                    add {
                        InfoRow(
                            tr("Address"),
                            listOfNotNull(ip, resolver.resolverCountry?.let { Locale("", it).displayCountry.ifEmpty { it } }).joinToString(" · "),
                            mono = true,
                            copyable = true,
                        )
                    }
                }
            }
            add {
                NoteRow(
                    when (resolver.path(publicIp)) {
                        DnsReport.Path.LOCAL -> tr("This network resolves names itself: the lookups leave from your own public address.")
                        DnsReport.Path.FORWARDED -> tr("The router takes the lookups and forwards them: the world sees them come from the resolver above.")
                        DnsReport.Path.DIRECT -> tr("The phone asks a public resolver directly.")
                        DnsReport.Path.UNKNOWN -> tr("Could not learn where the lookups go.")
                    },
                    warning = false,
                )
            }
            resolver.liesAboutMissingNames?.let { lies ->
                add {
                    NoteRow(
                        if (lies) tr("Invents addresses for names that do not exist") else tr("Does not invent addresses for missing names"),
                        warning = lies,
                    )
                }
            }
            resolver.dohReachable?.let { ok ->
                add {
                    InfoRow(
                        tr("DNS over HTTPS"),
                        if (ok) tr("Reachable") else tr("Blocked"),
                        valueColor = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else Spectra.colors.warning,
                    )
                }
            }
            resolver.dotReachable?.let { ok ->
                add {
                    InfoRow(
                        tr("DNS over TLS"),
                        if (ok) tr("Reachable") else tr("Blocked"),
                        valueColor = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else Spectra.colors.warning,
                    )
                }
            }
        },
    )
}

/** Which ports of the public address answer from outside. [link] is null for a saved check. */
internal fun LazyListScope.exposureCard(report: LinkReport, link: LinkProbe?) {
    val exposure = report.exposure
    if (exposure == null && (link == null || report.external == null)) return
    card(
        header = tr("Ports from the internet"),
        key = "exposure",
        footer = tr("check-host.net tries to connect to your public address on ten common ports, plus any you add, from one of its servers. An open port means your router forwards it to something inside."),
        rows = buildList {
            if (exposure != null) {
                exposure.ports.forEach { port ->
                    add {
                        InfoRow(
                            "${port.port}  ${tr(DiscoveredPort.serviceName(port.port))}",
                            if (port.open) tr("Open##port") else tr("Closed##port"),
                            valueColor = if (port.open) Spectra.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (exposure.ports.isNotEmpty()) {
                    val open = exposure.openPorts.size
                    add {
                        NoteRow(
                            if (open == 0) {
                                tr("No tested port answers from the internet: the router does not let these in.")
                            } else {
                                tr("%d ports answer from the internet. Anyone online can reach what is behind them.", open)
                            },
                            warning = open > 0,
                        )
                    }
                }
                if (exposure.note != RemoteNote.NONE) {
                    add {
                        NoteRow(
                            if (exposure.note == RemoteNote.BUSY) {
                                tr("The lookup service is busy. Try again in a minute.")
                            } else {
                                tr("check-host.net could not be asked about some ports.")
                            },
                            warning = false,
                        )
                    }
                }
            }
            if (link != null) {
                when {
                    link.checkingExposure -> add { ProgressRow(tr("Asking check-host.net…"), null) }
                    link.viaVpn -> add {
                        NoteRow(tr("Through a VPN the public address belongs to the VPN server, so its ports say nothing about your router."), warning = false)
                    }
                    else -> {
                        add {
                            ActionRow(
                                if (exposure == null) tr("Check ports from the internet") else tr("Check again"),
                                Icons.Rounded.Public,
                                onClick = { link.checkExposure() },
                                enabled = !link.running,
                            )
                        }
                        add { ExtraPortRow(enabled = !link.running, onCheck = link::checkExposure) }
                    }
                }
            }
        },
    )
}

@Composable
private fun ExtraPortRow(enabled: Boolean, onCheck: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val port = text.toIntOrNull()?.takeIf { it in 1..65535 }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { value -> text = value.filter { it.isDigit() }.take(5) },
            placeholder = { Text(tr("Another port")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                port?.let(onCheck)
                text = ""
            },
            enabled = enabled && port != null,
        ) { Text(tr("Check")) }
    }
}
