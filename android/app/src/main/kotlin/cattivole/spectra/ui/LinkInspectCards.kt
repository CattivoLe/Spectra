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
                ListRow(
                    title = seen.host,
                    subtitle = seen.issuer ?: seen.error.orEmpty(),
                    detail = seen.trustError?.takeIf { seen.error == null },
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
                            if (port.open) tr("Open") else tr("Closed"),
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
