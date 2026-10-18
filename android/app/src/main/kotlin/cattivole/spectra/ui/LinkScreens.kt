package cattivole.spectra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.AbroadView
import cattivole.spectra.core.ExternalAddress
import cattivole.spectra.core.LinkParse
import cattivole.spectra.core.LinkReport
import cattivole.spectra.core.LinkStatus
import cattivole.spectra.core.LocalTrace
import cattivole.spectra.core.ProbeResult
import cattivole.spectra.core.RegionReach
import cattivole.spectra.core.RemoteNote
import cattivole.spectra.core.RemoteTrace
import cattivole.spectra.core.TraceHop
import cattivole.spectra.platform.LinkProbe
import java.util.Locale

fun LazyListScope.connectionEntry(model: SpectraModel, nav: Navigator) {
    val link = model.connection
    card(header = tr("Connection"), key = "connection", rows = buildList {
        add {
            ListRow(
                title = connectionTitle(link),
                subtitle = connectionDetail(link),
                leading = { IconTile(Icons.Rounded.Public, connectionTint(link)) },
                onClick = { nav.connection() },
            )
        }
        add { VpnSwitch(link) }
        if (link.running) {
            add { ProgressRow(tr(link.phase.ifEmpty { "Checking…" }), link.fraction) }
        } else {
            add {
                ActionRow(
                    if (link.report == null) tr("Check connection") else tr("Check again"),
                    Icons.Rounded.NetworkCheck,
                    onClick = {
                        link.check()
                        nav.connection()
                    },
                )
            }
        }
    })
}

@Composable
fun LinkScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val link = model.connection
    val report = link.report
    SpectraScreen(
        title = tr("Connection"),
        subtitle = report?.external?.ip,
        onBack = nav::back,
        actions = {
            IconButton(onClick = { if (link.running) link.stop() else link.check() }) {
                Icon(
                    if (link.running) Icons.Rounded.Stop else Icons.Rounded.Refresh,
                    contentDescription = if (link.running) tr("Stop") else tr("Check again"),
                )
            }
        },
        overlay = {
            if (report == null && !link.running) {
                EmptyState(
                    Icons.Rounded.Public,
                    tr("Not checked yet"),
                    tr("Run a check to see whether this network reaches the internet, how fast it is, which countries answer, and the path out."),
                    action = tr("Check connection"),
                    onAction = link::check,
                )
            }
        },
    ) {
        card(key = "route", rows = listOf { VpnSwitch(link) })
        if (link.running) {
            card(key = "progress", rows = listOf { ProgressRow(tr(link.phase.ifEmpty { "Checking…" }), link.fraction) })
        }
        if (report != null && report.status != null) connectionCards(report)
    }
}

@Composable
fun SavedLinkScreen(model: SpectraModel, nav: Navigator, entryId: String, checkedAt: Long) {
    model.generation
    val saved = model.history.entry(entryId)?.connections?.firstOrNull { it.checkedAt == checkedAt }
    if (saved == null) {
        SpectraScreen(tr("Connection"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.Public, tr("Gone"), tr("This saved scan is no longer here."))
        }) {}
        return
    }
    val report = saved.toReport()
    SpectraScreen(
        title = tr("Connection"),
        subtitle = report.external?.ip,
        onBack = nav::back,
    ) {
        card(key = "route", rows = listOf {
            InfoRow(
                tr("Through VPN"),
                if (saved.viaVpn) tr("The check follows the VPN.") else tr("The check stays on Wi-Fi or Ethernet."),
            )
        })
        connectionCards(report)
    }
}

private fun LazyListScope.connectionCards(report: LinkReport) {
    internetCard(report)
    report.external?.let { externalCard(it) }
    if (report.speed != null || report.speedFailed) speedCard(report)
    if (report.regions.isNotEmpty()) {
        cardSection(
            report.regions,
            header = tr("Countries"),
            footer = tr("A connection to port 443. A miss can be this network filtering the site, or the site being down."),
            key = { it.code },
            sectionKey = "regions",
        ) { region -> RegionRow(region) }
    }
    if (report.abroad.isNotEmpty() || report.abroadNote != RemoteNote.NONE) abroadCard(report)
    report.localTrace?.let { localCard(it) }
    if (report.remoteTraces.isNotEmpty() || report.traceNote != RemoteNote.NONE) remoteCard(report)
}

private fun LazyListScope.internetCard(report: LinkReport) {
    card(
        header = tr("Internet"),
        footer = tr("Checked %s", ago(report.checkedAt)),
        key = "internet",
        rows = buildList {
            add {
                InfoRow(
                    tr("Internet"),
                    statusLabel(report.status),
                    valueColor = statusColor(report.status),
                )
            }
            report.gateway?.let { add { ProbeInfo(tr("Gateway"), it, tr("Unreachable")) } }
            report.dns?.let { add { ProbeInfo(tr("DNS"), it, tr("No reply")) } }
            report.web?.let { add { ProbeInfo(tr("Secure web"), it, tr("No reply")) } }
            report.detail?.let { add { NoteRow(tr(it), warning = report.status != LinkStatus.ONLINE) } }
        },
    )
}

private fun LazyListScope.externalCard(address: ExternalAddress) {
    val place = listOfNotNull(
        address.city?.takeIf { it.isNotBlank() },
        address.countryCode?.let { countryName(it) }?.takeIf { it.isNotBlank() },
    ).joinToString(", ")
    val provider = listOfNotNull(address.asn, address.isp).joinToString(" ")
    card(
        header = tr("External address"),
        key = "external",
        rows = buildList {
            add { InfoRow(tr("External address"), address.ip, mono = true, copyable = true) }
            if (place.isNotEmpty()) add { InfoRow(tr("Place"), place) }
            if (provider.isNotEmpty()) add { InfoRow(tr("Provider"), provider) }
            address.colo?.let { add { InfoRow(tr("Edge"), it, mono = true) } }
            if (address.warp) add { NoteRow(tr("WARP is on"), warning = false) }
        },
    )
}

private fun LazyListScope.speedCard(report: LinkReport) {
    val speed = report.speed
    card(
        header = tr("Speed"),
        footer = tr("A few seconds of traffic to Cloudflare. It is a snapshot, not a full test."),
        key = "speed",
        rows = buildList {
            speed?.latencyMs?.let { add { InfoRow(tr("Latency"), tr("%d ms", it)) } }
            speed?.downBps?.let { add { InfoRow(tr("Download"), rate(it)) } }
            speed?.upBps?.let { add { InfoRow(tr("Upload"), rate(it)) } }
            if (report.speedFailed) add { NoteRow(tr("Could not measure the speed."), warning = true) }
        },
    )
}

private fun LazyListScope.abroadCard(report: LinkReport) {
    card(
        header = tr("From abroad"),
        footer = tr("A phone behind a router usually does not answer pings from the internet. A reply means the public address answered."),
        key = "abroad",
        rows = buildList {
            report.abroad.forEach { view -> add { AbroadRow(view) } }
            note(report.abroadNote)?.let { text -> add { NoteRow(text, warning = true) } }
        },
    )
}

private fun LazyListScope.localCard(trace: LocalTrace) {
    card(
        header = tr("Path from this phone"),
        footer = if (trace.unavailable) null else tr("To 1.1.1.1, one probe per hop."),
        key = "local-trace",
        rows = buildList {
            if (trace.unavailable) {
                add { NoteRow(tr("This phone blocked the probes a traceroute needs."), warning = true) }
            } else {
                trace.hops.forEachIndexed { index, hop -> add { HopRow(index + 1, hop) } }
                if (trace.hops.isEmpty()) add { NoteRow(tr("No hops came back"), warning = true) }
            }
        },
    )
}

private fun LazyListScope.remoteCard(report: LinkReport) {
    card(
        header = tr("Paths toward this address"),
        footer = tr("Measured by servers in other countries, toward this network's public address."),
        key = "remote-trace",
        rows = buildList {
            report.remoteTraces.forEach { trace -> add { RemoteRow(trace) } }
            note(report.traceNote)?.let { text -> add { NoteRow(text, warning = true) } }
        },
    )
}

@Composable
private fun VpnSwitch(link: LinkProbe) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr("Through VPN"), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (link.viaVpn) tr("The check follows the VPN.") else tr("The check stays on Wi-Fi or Ethernet."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = link.viaVpn, onCheckedChange = { link.viaVpn = it }, enabled = !link.running)
    }
}

@Composable
private fun ProbeInfo(label: String, result: ProbeResult, down: String) {
    val value = when {
        result.ok && result.millis != null -> tr("%s · %d ms", result.name, result.millis)
        result.ok -> result.name
        else -> down
    }
    InfoRow(label, value, mono = result.ok, valueColor = if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant else Spectra.colors.warning)
}

@Composable
private fun RegionRow(region: RegionReach) {
    ListRow(
        title = countryName(region.code),
        subtitle = region.host,
        trailing = {
            Text(
                if (region.ok && region.millis != null) tr("%d ms", region.millis) else tr("No route"),
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                color = if (region.ok) Spectra.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun AbroadRow(view: AbroadView) {
    val place = listOfNotNull(countryName(view.code).takeIf { it.isNotBlank() }, view.city.takeIf { it.isNotBlank() })
        .joinToString(" · ")
    ListRow(
        title = place.ifEmpty { view.code.uppercase() },
        trailing = {
            Text(
                if (view.ok && view.millis != null) tr("%d ms", view.millis) else tr("No reply"),
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                color = if (view.ok) Spectra.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun HopRow(number: Int, hop: TraceHop) {
    val value = when {
        hop.address == null -> "*"
        hop.millis != null -> tr("%s · %d ms", hop.address, hop.millis)
        else -> hop.address
    }
    InfoRow("$number", value, mono = true)
}

@Composable
private fun RemoteRow(trace: RemoteTrace) {
    val place = listOfNotNull(countryName(trace.code).takeIf { it.isNotBlank() }, trace.city.takeIf { it.isNotBlank() })
        .joinToString(" · ")
    val outcome = if (trace.reached) {
        tr("Reaches the address")
    } else {
        val last = trace.hops.lastOrNull { it.address != null }?.address
        if (last != null) tr("Stops at %s", last) else tr("No hops came back")
    }
    val path = LinkParse.compactPath(trace.hops)
    ListRow(
        title = place.ifEmpty { trace.code.uppercase() },
        subtitle = listOf(outcome, path).filter { it.isNotEmpty() }.joinToString("\n"),
        subtitleLines = 4,
        monoSubtitle = true,
    )
}

@Composable
private fun connectionTint(link: LinkProbe): Color = when (link.report?.status) {
    LinkStatus.ONLINE -> Spectra.colors.success
    LinkStatus.CAPTIVE, LinkStatus.LAN -> Spectra.colors.warning
    LinkStatus.OFFLINE, LinkStatus.NO_NETWORK -> Spectra.colors.danger
    null -> MaterialTheme.colorScheme.primary
}

private fun connectionTitle(link: LinkProbe): String = when (link.report?.status) {
    LinkStatus.ONLINE -> tr("Online")
    LinkStatus.CAPTIVE -> tr("Sign-in required")
    LinkStatus.LAN -> tr("Local only")
    LinkStatus.OFFLINE -> tr("Offline")
    LinkStatus.NO_NETWORK -> tr("No Wi-Fi")
    null -> if (link.running) tr("Checking…") else tr("Connection")
}

private fun connectionDetail(link: LinkProbe): String {
    if (link.running && link.phase.isNotEmpty()) return tr(link.phase)
    val report = link.report ?: return tr("Internet, speed, countries and the path out of this network.")
    val bits = listOfNotNull(
        report.external?.ip,
        report.speed?.downBps?.let { rate(it) },
        report.external?.city?.takeIf { it.isNotBlank() },
    )
    if (bits.isNotEmpty()) return bits.joinToString(" · ")
    return report.detail?.let { tr(it) } ?: tr("Checked %s", ago(report.checkedAt))
}

internal fun connectionSummary(report: LinkReport, viaVpn: Boolean): String {
    val bits = listOfNotNull(
        report.external?.ip,
        report.speed?.downBps?.let { rate(it) },
        if (viaVpn) tr("Through VPN") else null,
    )
    if (bits.isNotEmpty()) return bits.joinToString(" · ")
    return report.detail?.let { tr(it) } ?: tr("Checked %s", ago(report.checkedAt))
}

internal fun statusLabel(status: LinkStatus?): String = when (status) {
    LinkStatus.ONLINE -> tr("Online")
    LinkStatus.CAPTIVE -> tr("Sign-in required")
    LinkStatus.LAN -> tr("Local only")
    LinkStatus.OFFLINE -> tr("Offline")
    LinkStatus.NO_NETWORK -> tr("No Wi-Fi")
    null -> tr("Checking…")
}

@Composable
internal fun statusColor(status: LinkStatus?): Color = when (status) {
    LinkStatus.ONLINE -> Spectra.colors.success
    LinkStatus.CAPTIVE, LinkStatus.LAN -> Spectra.colors.warning
    LinkStatus.OFFLINE, LinkStatus.NO_NETWORK -> Spectra.colors.danger
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun note(note: RemoteNote): String? = when (note) {
    RemoteNote.BUSY -> tr("The lookup service is busy. Try again in a minute.")
    RemoteNote.FAILED -> tr("Could not ask the other countries.")
    RemoteNote.NONE -> null
}

private fun countryName(code: String): String {
    if (code.isBlank()) return ""
    return Locale("", code.uppercase()).getDisplayCountry(Locale.getDefault()).ifBlank { code.uppercase() }
}

private fun rate(bps: Double): String = when {
    bps >= 1_000_000 -> tr("%.1f Mbit/s", bps / 1_000_000.0)
    bps >= 1_000 -> tr("%.0f kbit/s", bps / 1_000.0)
    else -> tr("%.0f bit/s", bps)
}
