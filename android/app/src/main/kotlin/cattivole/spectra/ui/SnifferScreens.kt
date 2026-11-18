package cattivole.spectra.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Https
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.HttpExchange
import cattivole.spectra.core.HttpField
import cattivole.spectra.core.TrafficFlow
import cattivole.spectra.core.TrafficKind
import cattivole.spectra.core.TrafficSession
import cattivole.spectra.core.kind
import cattivole.spectra.platform.TrafficSniffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts the tunnel, asking for the VPN consent the first time. [onStarted] runs once it is up. */
@Composable
fun rememberTrafficConsent(sniffer: TrafficSniffer, onStarted: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sniffer.start()
            onStarted()
        }
    }
    return start@{
        val prepare = try {
            VpnService.prepare(context)
        } catch (_: Exception) {
            return@start
        }
        if (prepare == null) {
            sniffer.start()
            onStarted()
        } else {
            launcher.launch(prepare)
        }
    }
}

fun LazyListScope.trafficEntry(model: SpectraModel, nav: Navigator) {
    val traffic = model.traffic
    card(header = tr("Traffic"), key = "traffic", rows = listOf {
        ListRow(
            title = if (traffic.running) tr("Recording…") else tr("This phone"),
            subtitle = trafficSummary(traffic),
            leading = {
                IconTile(
                    Icons.Rounded.WifiTethering,
                    if (traffic.running) Spectra.colors.danger else MaterialTheme.colorScheme.primary,
                )
            },
            onClick = { nav.traffic() },
        )
    })
}

@Composable
fun SnifferScreen(model: SpectraModel, nav: Navigator, sessionId: String?) {
    model.generation
    val traffic = model.traffic
    val session = sessionId?.let { traffic.session(it) }
    if (sessionId != null && session == null) {
        SpectraScreen(tr("Traffic"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.WifiTethering, tr("Gone"), tr("This saved recording is no longer here."))
        }) {}
        return
    }
    val live = session == null
    val flows = session?.flows ?: traffic.flows
    var query by rememberSaveable { mutableStateOf("") }
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    val notify = LocalNotify.current
    val context = LocalContext.current
    val begin = rememberTrafficConsent(traffic)
    val listState = rememberLazyListState()
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    var method by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = kindName?.let { runCatching { TrafficKind.valueOf(it) }.getOrNull() }
    val needle = query.trim()
    val scope = sessionId ?: "live"
    val hits = flows.flatMap { flow ->
        if (flow.exchanges.isEmpty()) listOf(TrafficHit(flow, null)) else flow.exchanges.indices.map { TrafficHit(flow, it) }
    }
    val focus = traffic.focusedKey
    val visible = hits.filter { hit ->
        val exchange = hit.index?.let { hit.flow.exchanges.getOrNull(it) }
        if (trafficKey(scope, hit) == focus) return@filter true
        if (method != null && !method.equals(exchange?.method, ignoreCase = true)) return@filter false
        if (kind != null && hit.flow.kind() != kind) return@filter false
        hit.matches(needle)
    }.sortedWith(
        compareByDescending<TrafficHit> { trafficKey(scope, it) == focus }
            .thenBy { it.flow.closed }
            .thenByDescending { it.flow.lastAt },
    )
    val methods = hits.mapNotNull { hit -> hit.index?.let { hit.flow.exchanges.getOrNull(it)?.method } }.distinct()
        .sortedBy { METHODS.indexOf(it).let { index -> if (index < 0) METHODS.size else index } }
    LaunchedEffect(focus) {
        if (focus != null) listState.animateScrollToItem(0)
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text(tr("Clear connections?")) },
            text = { Text(tr("The list on screen is removed. A saved recording stays.")) },
            confirmButton = {
                TextButton(onClick = {
                    traffic.clear()
                    confirmClear = false
                }) { Text(tr("Clear"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel")) } },
        )
    }
    if (confirmDelete && session != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(tr("Delete this recording?")) },
            text = { Text(tr("The saved connection list is removed.")) },
            confirmButton = {
                TextButton(onClick = {
                    traffic.delete(session.id)
                    confirmDelete = false
                    nav.back()
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text(tr("Start a new recording?")) },
            text = { Text(tr("The connections on screen are not saved.")) },
            confirmButton = {
                TextButton(onClick = {
                    traffic.save()
                    confirmNew = false
                    begin()
                }) { Text(tr("Save and start")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmNew = false }) { Text(tr("Cancel")) }
                    TextButton(onClick = {
                        confirmNew = false
                        begin()
                    }) { Text(tr("Start over")) }
                }
            },
        )
    }
    SpectraScreen(
        title = tr("Traffic"),
        subtitle = when {
            session != null -> dateTime(session.startedAt)
            traffic.running -> tr("Recording…")
            traffic.startedAt > 0 -> dateTime(traffic.startedAt)
            else -> null
        },
        onBack = nav::back,
        actions = {
            if (flows.isNotEmpty()) {
                AskAssistantButton(
                    model,
                    nav,
                    question = { tr("Analyze the recorded traffic of this phone: which servers and apps did it talk to, and is anything unusual?") },
                    prepare = {
                        val name = session?.let { tr("saved session of %s", dateTime(it.stoppedAt)) }
                            ?: if (traffic.running) "recording in progress" else "current recording"
                        model.captures.keep(flows, name)
                    },
                )
            }
            if (live && flows.isNotEmpty()) {
                if (traffic.isCurrentSaved) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = tr("Saved"),
                        tint = Spectra.colors.success,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    IconButton(onClick = {
                        val updating = traffic.currentSessionId != null
                        when {
                            traffic.save() == null -> notify(tr("Nothing to save"))
                            updating -> notify(tr("Saved session updated"))
                            else -> notify(tr("Recording saved"))
                        }
                    }) {
                        Icon(
                            Icons.Rounded.Save,
                            contentDescription = if (traffic.currentSessionId != null) tr("Update saved session") else tr("Save recording"),
                        )
                    }
                }
            }
            if (live) {
                IconButton(onClick = {
                    when {
                        traffic.running -> traffic.stop()
                        traffic.unsaved -> confirmNew = true
                        else -> begin()
                    }
                }) {
                    Icon(
                        if (traffic.running) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                        contentDescription = if (traffic.running) tr("Stop recording") else tr("Start recording"),
                    )
                }
            }
            if (session != null || (live && flows.isNotEmpty())) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = tr("Options")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (session != null) {
                        DropdownMenuItem(
                            text = { Text(tr("Share")) },
                            leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                            onClick = {
                                menu = false
                                shareTextFile(context, trafficFileName(), trafficText(session))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Delete recording")) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = {
                                menu = false
                                confirmDelete = true
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(tr("Share")) },
                            leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                            onClick = {
                                menu = false
                                val text = trafficText(TrafficSession("live", traffic.startedAt, System.currentTimeMillis(), flows))
                                shareTextFile(context, trafficFileName(), text)
                            },
                        )
                        if (!traffic.running) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(tr("Clear connections")) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                                onClick = {
                                    menu = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                }
            }
        },
        searchQuery = query.takeIf { flows.isNotEmpty() },
        onSearchChange = if (flows.isNotEmpty()) {
            { query = it }
        } else {
            null
        },
        searchPlaceholder = tr("Host, app or address"),
        listState = listState,
        overlay = {
            when {
                flows.isEmpty() && live && !traffic.running -> EmptyState(
                    Icons.Rounded.WifiTethering,
                    tr("No connections yet"),
                    tr("Start a recording to list the connections this phone opens."),
                    action = tr("Start recording"),
                    onAction = { if (traffic.unsaved) confirmNew = true else begin() },
                )
                flows.isEmpty() && live && traffic.running -> EmptyState(
                    Icons.Rounded.WifiTethering,
                    tr("Recording…"),
                    tr("Connections show up here as this phone opens them."),
                    busy = true,
                )
                visible.isEmpty() && needle.isNotEmpty() -> EmptyState(
                    Icons.Rounded.WifiTethering,
                    tr("No results"),
                    tr("Nothing matches “%s”.", needle),
                )
                visible.isEmpty() && flows.isNotEmpty() -> EmptyState(
                    Icons.Rounded.WifiTethering,
                    tr("Nothing in this filter"),
                    tr("No connection matches these filters."),
                )
            }
        },
    ) {
        if (live) {
            if (traffic.saved.isNotEmpty()) {
                card(key = "saved-link", rows = listOf {
                    ListRow(
                        title = tr("Saved recordings (%d)", traffic.saved.size),
                        leading = { IconTile(Icons.Rounded.History, MaterialTheme.colorScheme.secondary) },
                        onClick = nav::savedTrafficList,
                    )
                })
            }
            traffic.error?.let { message ->
                card(key = "error", rows = listOf { NoteRow(tr(message), warning = true) })
            }
            if (traffic.refusedCertificate && traffic.decryptTls) {
                card(key = "refused", rows = listOf {
                    NoteRow(
                        tr("Apps keep refusing Spectra's certificate, and none has accepted it yet: it is probably not trusted. Connections that refused go through unread."),
                        warning = true,
                    )
                })
            }
            card(
                key = "tls",
                rows = buildList {
                    add { TlsSwitch(traffic) }
                    traffic.certificateFingerprint()?.let { digest ->
                        add { InfoRow(tr("Fingerprint"), digest, mono = true, copyable = true) }
                    }
                },
            )
        }
        if (flows.isNotEmpty()) {
            item(key = "proto-filters") {
                ChipRow(contentPadding = FilterChipPadding) {
                    ToggleChip(tr("All"), kind == null, { kindName = null })
                    TrafficKind.entries.forEach { entry ->
                        val present = hits.any { it.flow.kind() == entry }
                        if (!present && kind != entry) return@forEach
                        ToggleChip(entry.name, kind == entry, {
                            kindName = if (kind == entry) null else entry.name
                            if (entry != TrafficKind.HTTP) method = null
                        })
                    }
                }
            }
            if (methods.isNotEmpty()) {
                item(key = "method-filters") {
                    ChipRow(contentPadding = FilterChipPadding) {
                        ToggleChip(tr("All"), method == null, { method = null })
                        methods.forEach { name ->
                            ToggleChip(name, method.equals(name, true), {
                                method = if (method.equals(name, true)) null else name
                                if (kind != null && kind != TrafficKind.HTTP) kindName = TrafficKind.HTTP.name
                            })
                        }
                    }
                }
            }
        }
        cardSection(
            visible,
            header = if (visible.any { it.index != null }) tr("%d requests", visible.size) else tr("%d connections", visible.size),
            key = { trafficKey(scope, it) },
            sectionKey = "flows",
            dividerInset = 70.dp,
        ) { hit ->
            val exchange = hit.index?.let { hit.flow.exchanges.getOrNull(it) }
            FlowRow(
                hit.flow,
                exchange,
                focused = trafficKey(scope, hit) == focus,
                onFocus = { traffic.toggleFocus(trafficKey(scope, hit)) },
                onClick = {
                    if (session != null) nav.savedTrafficFlow(session.id, hit.flow.id, hit.index)
                    else nav.trafficFlow(hit.flow.id, hit.index)
                },
            )
        }
        if (live) {
            card(
                key = "multicast",
                rows = listOf {
                    NoteRow(tr("Multicast, including device discovery, is not forwarded while recording."), warning = false)
                },
            )
        }
    }
}

/** The recordings kept on this phone, newest first, with date and time. */
@Composable
fun SavedTrafficScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val traffic = model.traffic
    SpectraScreen(
        title = tr("Saved recordings"),
        onBack = nav::back,
        overlay = {
            if (traffic.saved.isEmpty()) {
                EmptyState(Icons.Rounded.WifiTethering, tr("No saved sessions"), tr("Save a recording to keep it here."))
            }
        },
    ) {
        cardSection(
            traffic.saved,
            key = { it.id },
            sectionKey = "saved",
            dividerInset = 70.dp,
        ) { saved ->
            ListRow(
                title = dateTime(saved.startedAt),
                subtitle = tr("%d connections", saved.flows.size),
                detail = stopwatch((saved.stoppedAt - saved.startedAt).coerceAtLeast(0)),
                leading = {
                    IconTile(
                        if (saved.id == traffic.currentSessionId) Icons.Rounded.CheckCircle else Icons.Rounded.WifiTethering,
                        if (saved.id == traffic.currentSessionId) Spectra.colors.success else MaterialTheme.colorScheme.secondary,
                    )
                },
                onClick = { nav.savedTraffic(saved.id) },
            )
        }
    }
}

@Composable
fun TrafficFlowScreen(model: SpectraModel, nav: Navigator, flowId: Long, sessionId: String?, exchange: Int? = null) {
    model.generation
    val traffic = model.traffic
    val flow = if (sessionId != null) {
        traffic.session(sessionId)?.flows?.firstOrNull { it.id == flowId }
    } else {
        traffic.flows.firstOrNull { it.id == flowId }
    }
    val http = exchange?.let { flow?.exchanges?.getOrNull(it) }
    val focusKey = flow?.let { trafficKey(sessionId ?: "live", TrafficHit(it, exchange)) }
    if (flow == null) {
        SpectraScreen(tr("Traffic"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.WifiTethering, tr("Gone"), tr("This connection is no longer in the list."))
        }) {}
        return
    }
    SpectraScreen(title = http?.summary ?: flow.title, subtitle = flow.host ?: flow.app, onBack = nav::back) {
        freeCard("hero") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(flow.icon(), flow.tint(), size = 56.dp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(http?.summary ?: flow.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(flow.protocol, flow.destinationPort.toString(), flow.app).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 6.dp)) { FlowBadges(flow, http) }
                }
            }
        }
        card(
            header = tr("Connection"),
            key = "connection",
            rows = buildList {
                flow.host?.let { add { InfoRow(tr("Server name"), it, copyable = true) } }
                add { InfoRow(tr("Destination"), "${flow.destination}:${flow.destinationPort}", mono = true, copyable = true) }
                add { InfoRow(tr("Protocol"), flow.protocol) }
                add { InfoRow(tr("Source port"), flow.sourcePort.toString(), mono = true) }
                flow.app?.let { add { InfoRow(tr("Application"), it) } }
                http?.status?.let { add { InfoRow(tr("Status"), it.toString(), mono = true) } }
                if (http == null) flow.request?.let { add { InfoRow(tr("Request"), it, copyable = true) } }
                if (flow.refused) add { NoteRow(tr("This app did not trust the Spectra certificate."), warning = true) }
                add { InfoRow(tr("Sent"), byteSize(flow.sent)) }
                add { InfoRow(tr("Received"), byteSize(flow.received)) }
                add {
                    InfoRow(
                        tr("State"),
                        if (flow.closed) tr("Closed") else tr("Still open"),
                        valueColor = if (flow.closed) MaterialTheme.colorScheme.onSurfaceVariant else Spectra.colors.success,
                    )
                }
            },
        )
        if (focusKey != null) {
            card(key = "focus", rows = listOf {
                ActionRow(
                    if (model.traffic.focusedKey == focusKey) tr("Stop keeping at top") else tr("Keep at top"),
                    Icons.Rounded.PushPin,
                    onClick = { model.traffic.toggleFocus(focusKey) },
                )
            })
        }
        if (http != null) {
            val parameters = http.query + http.parameters
            if (parameters.isNotEmpty()) {
                card(
                    header = tr("Parameters"),
                    key = "parameters",
                    rows = buildList { for (field in parameters) add { FieldRow(field) } },
                )
            }
            if (http.requestHeaders.isNotEmpty()) {
                card(
                    header = tr("Request headers"),
                    key = "req-headers",
                    rows = buildList { for (field in http.requestHeaders) add { FieldRow(field) } },
                )
            }
            if (http.responseHeaders.isNotEmpty()) {
                card(
                    header = tr("Response headers"),
                    key = "res-headers",
                    rows = buildList { for (field in http.responseHeaders) add { FieldRow(field) } },
                )
            }
            card(
                header = tr("Response"),
                key = "response",
                rows = buildList {
                    if (http.responseCut) add { NoteRow(tr("The body was cut short, so the JSON may be incomplete."), warning = true) }
                    val body = http.responseJson
                    if (body != null) {
                        add {
                            SelectionContainer(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    } else {
                        val encoding = http.responseHeaders.firstOrNull { it.name.equals("Content-Encoding", true) }?.value
                        add {
                            NoteRow(
                                if (encoding.isNullOrBlank()) tr("This response has no body.")
                                else tr("The body is encoded as %s and is not text.", encoding),
                                warning = encoding != null,
                            )
                        }
                    }
                },
            )
        }
        card(
            header = tr("Observed"),
            key = "observed",
            rows = listOf(
                { InfoRow(tr("Started"), clockTime(flow.startedAt)) },
                { InfoRow(tr("Last packet"), ago(flow.lastAt)) },
            ),
        )
    }
}

@Composable
private fun FlowRow(
    flow: TrafficFlow,
    exchange: HttpExchange?,
    focused: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val subtitle = buildList {
        add("${flow.protocol} ${flow.destinationPort}")
        flow.app?.let { add(it) }
        if (flow.host != null) add(flow.destination)
    }.joinToString(" · ")
    WithCopyMenu(
        listOfNotNull(
            flow.host?.let { CopyItem(tr("Copy name"), tr("Server name"), it) },
            CopyItem(tr("Copy address"), tr("Destination"), flow.destination),
            exchange?.summary?.let { CopyItem(tr("Copy request"), tr("Request"), it) },
        ),
    ) { open ->
        ListRow(
            title = exchange?.summary ?: flow.title,
            subtitle = subtitle,
            detail = exchange?.status?.toString()
                ?: flow.request?.takeIf { exchange == null }
                ?: tr("This app did not trust the Spectra certificate.").takeIf { flow.refused && exchange == null },
            leading = { IconTile(flow.icon(), flow.tint()) },
            badges = { FlowBadges(flow, exchange) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onFocus, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = if (focused) tr("Stop keeping at top") else tr("Keep at top"),
                            tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        byteSize(flow.sent + flow.received),
                        style = MaterialTheme.typography.titleSmall.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dimmed = flow.closed,
            onClick = onClick,
            onLongClick = open,
        )
    }
}

@Composable
private fun TlsSwitch(traffic: TrafficSniffer) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr("Decrypt TLS"), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (traffic.decryptTls) {
                    tr("New connections are decrypted with the Spectra certificate. An app that does not trust it closes the connection.")
                } else {
                    tr("TLS stays encrypted. Headers and JSON are shown only for cleartext DNS and HTTP.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = traffic.decryptTls, onCheckedChange = traffic::setDecryptTls)
    }
}

@Composable
private fun FlowBadges(flow: TrafficFlow, exchange: HttpExchange? = null) {
    when (flow.kind()) {
        TrafficKind.HTTP -> Pill(exchange?.method ?: "HTTP", Spectra.colors.warning)
        TrafficKind.TLS -> Pill("TLS", MaterialTheme.colorScheme.primary)
        TrafficKind.DNS -> Pill("DNS", MaterialTheme.colorScheme.secondary)
        TrafficKind.UDP -> Pill("UDP", MaterialTheme.colorScheme.tertiary)
        TrafficKind.TCP -> Unit
    }
}

@Composable
private fun TrafficFlow.icon() = when {
    exchanges.isNotEmpty() || request != null -> Icons.Rounded.Language
    tls -> Icons.Rounded.Lock
    destinationPort == 53 -> Icons.Rounded.Dns
    destinationPort == 443 -> Icons.Rounded.Https
    else -> Icons.Rounded.WifiTethering
}

@Composable
private fun TrafficFlow.tint() = when {
    exchanges.isNotEmpty() || request != null -> Spectra.colors.warning
    tls -> MaterialTheme.colorScheme.primary
    closed -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.secondary
}

private fun TrafficFlow.matches(needle: String): Boolean {
    if (needle.isEmpty()) return true
    return title.contains(needle, true) ||
        destination.contains(needle, true) ||
        app?.contains(needle, true) == true ||
        request?.contains(needle, true) == true ||
        protocol.contains(needle, true) ||
        destinationPort.toString() == needle
}

private fun TrafficHit.matches(needle: String): Boolean {
    if (needle.isEmpty()) return true
    val exchange = index?.let { flow.exchanges.getOrNull(it) }
    if (exchange != null) {
        if (exchange.method.contains(needle, true)) return true
        if (exchange.path.contains(needle, true)) return true
        if (exchange.status?.toString() == needle) return true
        if ((exchange.query + exchange.parameters).any { it.name.contains(needle, true) || it.value.contains(needle, true) }) return true
    }
    return flow.matches(needle)
}

private fun trafficSummary(traffic: TrafficSniffer): String = when {
    traffic.error != null && !traffic.running -> tr(traffic.error!!)
    traffic.running && traffic.flows.isEmpty() -> tr("Waiting for connections")
    traffic.flows.isNotEmpty() -> tr("%d connections", traffic.flows.size)
    else -> tr("Forward this phone's connections and list them")
}

private fun trafficText(session: TrafficSession): String = buildString {
    appendLine("Spectra")
    appendLine(dateTime(session.startedAt))
    appendLine()
    for (flow in session.flows) {
        appendLine(flow.title)
        append(flow.protocol)
        append(' ')
        append(flow.destinationPort)
        flow.app?.let { append(" · "); append(it) }
        appendLine()
        append(flow.destination)
        append(':')
        appendLine(flow.destinationPort)
        if (flow.exchanges.isEmpty()) {
            flow.request?.let { appendLine(it) }
        } else {
            for (exchange in flow.exchanges) {
                append(exchange.method)
                append(' ')
                append(exchange.path)
                exchange.status?.let { append(" · "); append(it) }
                appendLine()
            }
        }
        appendLine(tr("Up %s · down %s", byteSize(flow.sent), byteSize(flow.received)))
        appendLine()
    }
}

private fun trafficFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "spectra-traffic-$stamp.txt"
}

@Composable
private fun FieldRow(field: HttpField) {
    InfoRow(field.name, field.value, mono = true, copyable = true)
}

@Composable
fun TrafficJsonScreen(model: SpectraModel, nav: Navigator, flowId: Long, exchange: Int, sessionId: String?) {
    model.generation
    val traffic = model.traffic
    val flow = if (sessionId != null) {
        traffic.session(sessionId)?.flows?.firstOrNull { it.id == flowId }
    } else {
        traffic.flows.firstOrNull { it.id == flowId }
    }
    val http = flow?.exchanges?.getOrNull(exchange)
    val json = http?.responseJson
    val copy = rememberCopier()
    if (flow == null || http == null) {
        SpectraScreen(tr("Response JSON"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.DataObject, tr("Gone"), tr("This connection is no longer in the list."))
        }) {}
        return
    }
    SpectraScreen(
        title = tr("Response JSON"),
        subtitle = http.summary,
        onBack = nav::back,
        actions = {
            if (!json.isNullOrEmpty()) {
                IconButton(onClick = { copy(tr("Response JSON"), json) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = tr("Copy"))
                }
            }
        },
        overlay = {
            if (json.isNullOrEmpty()) {
                EmptyState(Icons.Rounded.DataObject, tr("No JSON"), tr("No JSON in this response."))
            }
        },
    ) {
        if (!json.isNullOrEmpty()) {
            if (http.responseCut) {
                card(key = "cut", rows = listOf {
                    NoteRow(tr("The body was cut short, so the JSON may be incomplete."), warning = true)
                })
            }
            freeCard("json") {
                SelectionContainer {
                    Text(json, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private data class TrafficHit(val flow: TrafficFlow, val index: Int?)

private fun trafficKey(scope: String, hit: TrafficHit): String = "$scope:${hit.flow.id}:${hit.index ?: "-"}"

private val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
