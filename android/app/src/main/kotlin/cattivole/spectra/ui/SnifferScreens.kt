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
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import android.graphics.BitmapFactory
import cattivole.spectra.core.BodyPreview
import cattivole.spectra.core.BodyView
import cattivole.spectra.core.Curl
import cattivole.spectra.core.Har
import cattivole.spectra.core.HttpAssembler
import cattivole.spectra.core.Http2Assembler
import cattivole.spectra.core.WsMessage
import cattivole.spectra.core.MapRule
import cattivole.spectra.core.RepeatRequest
import cattivole.spectra.core.RepeatResult
import cattivole.spectra.core.ThrottleSettings
import cattivole.spectra.platform.RequestRepeater
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import cattivole.spectra.core.TlsHostFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.runtime.remember
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
fun SnifferScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val traffic = model.traffic
    val flows = traffic.flows
    val begin = rememberTrafficRecord(traffic)
    SpectraScreen(
        title = tr("Traffic"),
        subtitle = when {
            traffic.running -> tr("Recording…")
            traffic.startedAt > 0 -> dateTime(traffic.startedAt)
            else -> null
        },
        onBack = nav::back,
        actions = { TrafficActions(model, nav, session = null) },
        overlay = {
            if (flows.isEmpty() && !traffic.running) {
                EmptyState(
                    Icons.Rounded.WifiTethering,
                    tr("No connections yet"),
                    tr("Start a recording to list the connections this phone opens."),
                    action = tr("Start recording"),
                    onAction = begin,
                )
            }
        },
    ) {
        val rows = buildList<@Composable () -> Unit> {
            if (traffic.running || flows.isNotEmpty()) {
                add {
                    ListRow(
                        title = tr("Requests and connections"),
                        subtitle = trafficCounts(flows),
                        leading = {
                            IconTile(
                                Icons.AutoMirrored.Rounded.ViewList,
                                if (traffic.running) Spectra.colors.danger else MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = nav::trafficList,
                    )
                }
            }
            if (traffic.saved.isNotEmpty()) {
                add {
                    ListRow(
                        title = tr("Saved recordings (%d)", traffic.saved.size),
                        leading = { IconTile(Icons.Rounded.History, MaterialTheme.colorScheme.secondary) },
                        onClick = nav::savedTrafficList,
                    )
                }
            }
        }
        if (rows.isNotEmpty()) card(key = "recording", dividerInset = 70.dp, rows = rows)
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
                if (traffic.decryptTls) {
                    add {
                        ListRow(
                            title = tr("Hosts to decrypt"),
                            subtitle = hostFilterSummary(traffic.hostFilter),
                            leading = { IconTile(Icons.Rounded.Tune, MaterialTheme.colorScheme.primary) },
                            onClick = nav::trafficHosts,
                        )
                    }
                }
                traffic.certificateFingerprint()?.let { digest ->
                    if (traffic.decryptTls) add { CertificateState(traffic, digest) }
                    add { InstallCertificateRow(traffic) }
                    add { InfoRow(tr("Fingerprint"), digest, mono = true, copyable = true) }
                }
            },
        )
        card(
            key = "tools",
            rows = listOf(
                {
                    ListRow(
                        title = tr("Throttling"),
                        subtitle = throttleSummary(traffic.throttle),
                        leading = { IconTile(Icons.Rounded.Speed, if (traffic.throttle.enabled) Spectra.colors.warning else MaterialTheme.colorScheme.secondary) },
                        onClick = nav::trafficThrottle,
                    )
                },
                {
                    ListRow(
                        title = tr("Map Local"),
                        subtitle = mapLocalSummary(traffic),
                        leading = { IconTile(Icons.Rounded.SwapHoriz, if (traffic.mapRules.any { it.enabled }) Spectra.colors.warning else MaterialTheme.colorScheme.secondary) },
                        onClick = nav::trafficMapLocal,
                    )
                },
            ),
        )
        card(
            key = "multicast",
            rows = listOf {
                NoteRow(tr("Multicast, including device discovery, is not forwarded while recording."), warning = false)
            },
        )
    }
}

/** What can be done with a recording: the one in progress or the last one, or a saved [session]. */
@Composable
internal fun TrafficActions(model: SpectraModel, nav: Navigator, session: TrafficSession?) {
    val traffic = model.traffic
    val live = session == null
    val flows = session?.flows ?: traffic.flows
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val notify = LocalNotify.current
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val begin = rememberTrafficRecord(traffic)
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
    if (flows.isNotEmpty()) {
        AskAssistantButton(
            model,
            nav,
            subject = { cattivole.spectra.assistant.CardSubject.Traffic },
            prepare = {
                val name = session?.let { tr("Saved session of %s", dateTime(it.stoppedAt)) }
                    ?: if (traffic.running) tr("Recording in progress") else tr("Current recording")
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
            DropdownMenuItem(
                text = { Text(tr("Export HAR")) },
                leadingIcon = { Icon(Icons.Rounded.Description, null) },
                onClick = {
                    menu = false
                    coroutines.launch { shareHar(context, traffic, session?.id, flows) }
                },
            )
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
}

/** Starts a recording, after asking about connections on screen that are not saved. */
@Composable
internal fun rememberTrafficRecord(traffic: TrafficSniffer): () -> Unit {
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    val begin = rememberTrafficConsent(traffic)
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
    return { if (traffic.unsaved) confirmNew = true else begin() }
}

/** How much a recording holds. */
internal fun trafficCounts(flows: List<TrafficFlow>): String =
    tr("Requests: %d · connections: %d", flows.sumOf { it.exchanges.size }, flows.size)

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
                subtitle = trafficCounts(saved.flows),
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
    val coroutines = rememberCoroutineScope()
    val context = LocalContext.current
    val copy = rememberCopier()
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
                http?.httpVersion?.let { add { InfoRow(tr("HTTP version"), it, mono = true) } }
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
            card(key = "focus", rows = buildList {
                add {
                    ActionRow(
                        if (model.traffic.focusedKey == focusKey) tr("Stop keeping at top") else tr("Keep at top"),
                        Icons.Rounded.PushPin,
                        onClick = { model.traffic.toggleFocus(focusKey) },
                    )
                }
                if (http != null) {
                    add {
                        ActionRow(tr("Repeat request"), Icons.Rounded.Replay, onClick = {
                            coroutines.launch {
                                val body = withContext(Dispatchers.IO) {
                                    if (http.requestBodyStored) traffic.body(sessionId, flow.id, exchange!!, true) else null
                                }
                                traffic.repeatDraft = RepeatRequest.from(flow, http, body)
                                nav.trafficRepeat()
                            }
                        })
                    }
                    add {
                        ActionRow(tr("Map locally"), Icons.Rounded.SwapHoriz, onClick = {
                            coroutines.launch {
                                val body = withContext(Dispatchers.IO) {
                                    if (http.responseBodyStored) traffic.body(sessionId, flow.id, exchange!!, false) else null
                                }
                                val text = body?.let { BodyPreview.of(it, http.header("Content-Type", response = true)) }.let { view ->
                                    when (view) {
                                        is BodyView.Json -> view.text
                                        is BodyView.Text -> view.text
                                        else -> http.responseJson.orEmpty()
                                    }
                                }
                                traffic.ruleDraft = MapRule(
                                    id = java.util.UUID.randomUUID().toString(),
                                    host = flow.host ?: flow.destination,
                                    path = http.path,
                                    status = http.status ?: 200,
                                    contentType = http.header("Content-Type", response = true)?.substringBefore(';')?.trim() ?: "application/json",
                                    body = text,
                                )
                                nav.trafficMapRule(null)
                            }
                        })
                    }
                    add {
                        ActionRow(tr("Copy as cURL"), Icons.Rounded.Terminal, onClick = {
                            coroutines.launch {
                                val body = withContext(Dispatchers.IO) {
                                    if (http.requestBodyStored) traffic.body(sessionId, flow.id, exchange!!, true) else null
                                }
                                copy(tr("cURL"), Curl.command(flow, http, body))
                            }
                        })
                    }
                }
                if (flow.exchanges.isNotEmpty()) {
                    add {
                        ActionRow(tr("Export HAR for this connection"), Icons.Rounded.Description, onClick = {
                            coroutines.launch { shareHar(context, traffic, sessionId, listOf(flow)) }
                        })
                    }
                }
                val refusedHost = flow.host?.takeIf { flow.refused && it.isNotEmpty() && traffic.hostFilter.allows(it) }
                if (refusedHost != null) {
                    add {
                        ActionRow(tr("Leave %s encrypted", refusedHost), Icons.Rounded.LockOpen, onClick = {
                            traffic.setHostFilter(traffic.hostFilter.excluding(refusedHost))
                        })
                    }
                }
            })
        }
        if (http == null) {
            cardSection(
                flow.exchanges.withIndex().toList(),
                header = tr("Requests: %d", flow.exchanges.size),
                key = { "exchange-${it.index}" },
                sectionKey = "exchanges",
            ) { (index, request) ->
                ListRow(
                    title = request.summary,
                    subtitle = exchangeLine(request),
                    onClick = { if (sessionId != null) nav.savedTrafficFlow(sessionId, flow.id, index) else nav.trafficFlow(flow.id, index) },
                )
            }
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
            if (http.startedAt != null) {
                card(
                    header = tr("Timing"),
                    key = "timing",
                    rows = buildList {
                        add { InfoRow(tr("Started"), preciseTime(http.startedAt)) }
                        http.waitMs?.let { add { InfoRow(tr("Waiting for the response"), millis(it)) } }
                        http.durationMs?.let { add { InfoRow(tr("Duration"), millis(it)) } }
                        http.requestSize?.let { add { InfoRow(tr("Request size"), sizeLine(it, http.requestHeaderBytes, http.requestBodyBytes)) } }
                        http.responseSize?.let { add { InfoRow(tr("Response size"), sizeLine(it, http.responseHeaderBytes, http.responseBodyBytes)) } }
                    },
                )
            }
            if (http.requestBodyStored || (http.requestBodyBytes ?: 0) > 0) {
                freeCard(key = "request-body", header = tr("Request body")) {
                    BodyContent(model, nav, sessionId, flow, exchange!!, http, request = true, full = false) {
                        nav.trafficBody(flow.id, exchange, sessionId, request = true)
                    }
                }
            }
            freeCard(key = "response-body", header = tr("Response")) {
                BodyContent(model, nav, sessionId, flow, exchange!!, http, request = false, full = false) {
                    nav.trafficBody(flow.id, exchange, sessionId, request = false)
                }
            }
            if (http.messages.isNotEmpty()) {
                cardSection(
                    http.messages.withIndex().toList(),
                    header = tr("WebSocket messages (%d)", http.messages.size + http.messagesDropped),
                    footer = if (http.messagesDropped > 0) tr("%d later messages were counted but not listed.", http.messagesDropped) else null,
                    key = { "ws-${it.index}" },
                    sectionKey = "ws",
                ) { (number, message) ->
                    MessageRow(message, onClick = { nav.trafficMessage(flow.id, exchange!!, number, sessionId) })
                }
            }
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
internal fun FlowRow(
    flow: TrafficFlow,
    exchange: HttpExchange?,
    focused: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val subtitle = buildList {
        // A request's own row names the site; a connection's title already does.
        if (exchange != null) add(flow.title) else add("${flow.protocol} ${flow.destinationPort}")
        flow.app?.let { add(it) }
        if (exchange == null && flow.host != null) add(flow.destination)
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
            detail = exchange?.let { exchangeLine(it) }
                ?: tr("Requests: %d", flow.exchanges.size).takeIf { exchange == null && flow.exchanges.isNotEmpty() }
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
                        byteSize(exchange?.let { (it.requestSize ?: 0) + (it.responseSize ?: 0) }?.takeIf { it > 0 } ?: (flow.sent + flow.received)),
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

/** Whether this phone has the certificate installed at all: read from the system store while the screen is open. */
@Composable
private fun CertificateState(traffic: TrafficSniffer, fingerprint: String) {
    val installed by androidx.compose.runtime.produceState<Boolean?>(null, fingerprint) {
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { traffic.certificateInstalled() }
            kotlinx.coroutines.delay(2_000)
        }
    }
    when (installed) {
        true -> NoteRow(tr("The certificate is installed. Apps that trust user certificates can be read; most others refuse it."), warning = false)
        false -> NoteRow(tr("The certificate is not installed on this phone yet: nothing can be decrypted."), warning = true)
        null -> Unit
    }
}

/** Saves the certificate to Downloads and says where in Settings to install it. */
@Composable
private fun InstallCertificateRow(traffic: TrafficSniffer) {
    val context = LocalContext.current
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    var steps by rememberSaveable { mutableStateOf(false) }
    ActionRow(tr("Install certificate"), Icons.Rounded.Download, onClick = {
        scope.launch {
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { traffic.saveCertificate() }
            if (saved) steps = true else notify(tr("The certificate could not be saved."))
        }
    })
    if (steps) {
        AlertDialog(
            onDismissRequest = { steps = false },
            title = { Text(tr("Install the certificate in Settings")) },
            text = { Text(tr("Android lets no app install a CA certificate, so spectra-ca.crt was saved to Downloads.\n\n1. In Settings search for “CA certificate”. On Samsung: Security and privacy → More security settings → Install from phone storage → CA certificate.\n2. Choose “Install anyway” and pick spectra-ca.crt in Downloads.\n3. Restart the app you want to read.")) },
            confirmButton = {
                TextButton(onClick = {
                    steps = false
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }
                }) { Text(tr("Open Settings")) }
            },
            dismissButton = { TextButton(onClick = { steps = false }) { Text(tr("Done")) } },
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
    if (exchange?.httpVersion == Http2Assembler.VERSION) Pill("H2", MaterialTheme.colorScheme.primary)
    if (exchange?.messages?.isNotEmpty() == true) Pill("WS", MaterialTheme.colorScheme.tertiary)
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

private fun trafficSummary(traffic: TrafficSniffer): String = when {
    traffic.error != null && !traffic.running -> tr(traffic.error!!)
    traffic.running && traffic.flows.isEmpty() -> tr("Waiting for connections")
    traffic.flows.isNotEmpty() -> trafficCounts(traffic.flows)
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
fun TrafficBodyScreen(model: SpectraModel, nav: Navigator, flowId: Long, exchange: Int, sessionId: String?, request: Boolean) {
    model.generation
    val traffic = model.traffic
    val flow = if (sessionId != null) {
        traffic.session(sessionId)?.flows?.firstOrNull { it.id == flowId }
    } else {
        traffic.flows.firstOrNull { it.id == flowId }
    }
    val http = flow?.exchanges?.getOrNull(exchange)
    val title = if (request) tr("Request body") else tr("Response")
    if (flow == null || http == null) {
        SpectraScreen(title, onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.DataObject, tr("Gone"), tr("This connection is no longer in the list."))
        }) {}
        return
    }
    SpectraScreen(title = title, subtitle = http.summary, onBack = nav::back) {
        freeCard("body") {
            BodyContent(model, nav, sessionId, flow, exchange, http, request = request, full = true, onOpen = null)
        }
    }
}

/** What a stored body holds, read from disk off the main thread. */
private class LoadedBody(val bytes: ByteArray?, val view: BodyView?)

/**
 * One side's body: a picture, formatted JSON, text, or the bytes in hex, with a
 * note when it was cut or not kept. A start of it, with a way to the rest;
 * [full] is that rest for a picture or bytes, and [onOpen] leads there.
 */
@Composable
private fun BodyContent(
    model: SpectraModel,
    nav: Navigator,
    sessionId: String?,
    flow: TrafficFlow,
    index: Int,
    http: HttpExchange,
    request: Boolean,
    full: Boolean,
    onOpen: (() -> Unit)?,
) {
    val traffic = model.traffic
    val title = if (request) tr("Request body") else tr("Response")
    val stored = if (request) http.requestBodyStored else http.responseBodyStored
    val type = http.header("Content-Type", response = !request)
    val loaded by produceState<LoadedBody?>(null, flow.id, index, request, stored, sessionId) {
        value = withContext(Dispatchers.IO) {
            val bytes = if (stored) traffic.body(sessionId, flow.id, index, request) else null
            LoadedBody(bytes, bytes?.let { BodyPreview.of(it, type) })
        }
    }
    val cut = if (request) http.requestCut else http.responseCut
    val wire = if (request) http.requestBodyBytes else http.responseBodyBytes
    val legacy = if (request) null else http.responseJson
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (cut) {
            val cap = if (request) HttpAssembler.REQUEST_CAP else HttpAssembler.RESPONSE_CAP
            NoteRow(tr("Only the first %s of this body were kept.", byteSize(cap.toLong())), warning = true, modifier = Modifier.padding(0.dp))
        }
        val state = loaded
        when {
            !stored && legacy != null -> BodyTextPreview(legacy, title, model, nav)
            !stored && (wire ?: 0) > 0 -> NoteRow(
                tr("This body was not kept: the recording reached its limit for stored bodies."),
                warning = true,
            )
            !stored -> NoteRow(if (request) tr("This request has no body.") else tr("This response has no body."), warning = false)
            state == null -> Text(tr("Loading…"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.bytes == null -> NoteRow(tr("The stored body could not be read."), warning = true)
            else -> when (val view = state.view) {
                is BodyView.Image -> {
                    val bitmap = remember(view) { BitmapFactory.decodeByteArray(view.bytes, 0, view.bytes.size) }
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = if (full) 2000.dp else 320.dp),
                        )
                        Text(
                            tr("Image, %d × %d, %s", bitmap.width, bitmap.height, byteSize(view.bytes.size.toLong())),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(tr("Image, %s. This phone cannot draw this format.", byteSize(view.bytes.size.toLong())), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is BodyView.Json -> BodyTextPreview(view.text, title, model, nav)
                is BodyView.Text -> BodyTextPreview(view.text, title, model, nav)
                is BodyView.Binary -> {
                    val encoding = http.header("Content-Encoding", response = !request)
                    Text(
                        if (encoding.isNullOrBlank()) tr("Binary data, %s.", byteSize(view.size.toLong()))
                        else tr("The body is encoded as %s and is not text.", encoding),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val hex = if (full) BodyPreview.hexDump(state.bytes, 64 * 1024) else view.hex.lineSequence().take(16).joinToString("\n")
                    SelectionContainer {
                        Text(hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (full && state.bytes.size > 64 * 1024) {
                        Text(tr("The first %s are shown.", byteSize(64L * 1024)), style = MaterialTheme.typography.bodySmall)
                    } else if (!full && onOpen != null && view.size > 256) {
                        TextButton(onClick = onOpen) { Text(tr("Show all")) }
                    }
                }
                BodyView.Empty, null -> NoteRow(if (request) tr("This request has no body.") else tr("This response has no body."), warning = false)
            }
        }
    }
}

/** One WebSocket message: who sent it, what kind, when, how big, and the start of its text. */
@Composable
private fun MessageRow(message: WsMessage, onClick: () -> Unit) {
    ListRow(
        title = message.preview?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: messageKind(message),
        subtitle = listOf(
            if (message.fromClient) tr("↑ sent") else tr("↓ received"),
            messageKind(message),
            preciseTime(message.at),
        ).joinToString(" · "),
        detail = byteSize(message.size),
        leading = {
            IconTile(
                if (message.fromClient) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                if (message.fromClient) MaterialTheme.colorScheme.primary else Spectra.colors.success,
            )
        },
        onClick = onClick,
    )
}

private fun messageKind(message: WsMessage): String = when (message.opcode) {
    WsMessage.TEXT -> tr("Text")
    WsMessage.BINARY -> tr("Binary")
    WsMessage.CLOSE -> tr("Close frame")
    WsMessage.PING -> "Ping"
    WsMessage.PONG -> "Pong"
    else -> message.kind
}

/** One WebSocket message in full. */
@Composable
fun TrafficMessageScreen(model: SpectraModel, nav: Navigator, flowId: Long, exchange: Int, number: Int, sessionId: String?) {
    model.generation
    val traffic = model.traffic
    val flow = if (sessionId != null) {
        traffic.session(sessionId)?.flows?.firstOrNull { it.id == flowId }
    } else {
        traffic.flows.firstOrNull { it.id == flowId }
    }
    val message = flow?.exchanges?.getOrNull(exchange)?.messages?.getOrNull(number)
    if (flow == null || message == null) {
        SpectraScreen(tr("Message"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.DataObject, tr("Gone"), tr("This connection is no longer in the list."))
        }) {}
        return
    }
    val copy = rememberCopier()
    val loaded by produceState<BodyView?>(null, flowId, exchange, number, sessionId) {
        value = withContext(Dispatchers.IO) {
            val bytes = if (message.stored) traffic.message(sessionId, flowId, exchange, number) else null
            when {
                bytes != null -> BodyPreview.of(bytes, if (message.opcode == WsMessage.BINARY) "application/octet-stream" else "text/plain")
                message.preview != null -> BodyView.Text(message.preview)
                else -> BodyView.Empty
            }
        }
    }
    SpectraScreen(title = tr("Message"), subtitle = flow.title, onBack = nav::back) {
        card(key = "about", rows = buildList {
            add { InfoRow(tr("Direction"), if (message.fromClient) tr("↑ sent") else tr("↓ received")) }
            add { InfoRow(tr("Type"), messageKind(message)) }
            add { InfoRow(tr("Time"), preciseTime(message.at)) }
            add { InfoRow(tr("Size"), byteSize(message.size)) }
            if (message.compressed) add { InfoRow(tr("Compression"), "permessage-deflate") }
            if (message.cut) add { NoteRow(tr("Only the start of this message was kept."), warning = true) }
        })
        freeCard("payload") {
            when (val view = loaded) {
                null -> Text(tr("Loading…"), style = MaterialTheme.typography.bodyMedium)
                is BodyView.Json -> BodyTextPreview(view.text, tr("Message"), model, nav)
                is BodyView.Text -> BodyTextPreview(view.text, tr("Message"), model, nav)
                is BodyView.Binary -> SelectionContainer {
                    Text(view.hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                is BodyView.Image -> Text(tr("Image, %s. This phone cannot draw this format.", byteSize(view.bytes.size.toLong())))
                BodyView.Empty -> NoteRow(tr("This message is empty."), warning = false)
            }
        }
    }
}

/** Sends a request again, as recorded or edited, and shows what came back. */
@Composable
fun TrafficRepeatScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val traffic = model.traffic
    val draft = traffic.repeatDraft
    if (draft == null) {
        SpectraScreen(tr("Repeat request"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.Replay, tr("Gone"), tr("This connection is no longer in the list."))
        }) {}
        return
    }
    var method by rememberSaveable { mutableStateOf(draft.method) }
    var url by rememberSaveable { mutableStateOf(draft.url) }
    var headers by rememberSaveable { mutableStateOf(draft.headerText()) }
    val textBody = draft.body?.let { body -> String(body, Charsets.UTF_8).takeIf { it.toByteArray(Charsets.UTF_8).contentEquals(body) } }
    var body by rememberSaveable { mutableStateOf(textBody.orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RepeatResult?>(null) }
    val coroutines = rememberCoroutineScope()
    val copy = rememberCopier()
    fun send() {
        sending = true
        val request = RepeatRequest(
            method = method.trim().uppercase().ifEmpty { "GET" },
            url = url.trim(),
            headers = RepeatRequest.parseHeaders(headers),
            body = when {
                draft.body != null && textBody == null -> draft.body
                body.isEmpty() -> null
                else -> body.toByteArray(Charsets.UTF_8)
            },
        )
        coroutines.launch {
            result = withContext(Dispatchers.IO) { RequestRepeater.send(request) }
            sending = false
        }
    }
    SpectraScreen(title = tr("Repeat request"), onBack = nav::back) {
        freeCard(
            key = "request",
            header = tr("Request"),
            footer = tr("Sent from this app. While recording, it shows up in the list like any other request."),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(method, { method = it.take(16) }, label = { Text(tr("Method")) }, singleLine = true, modifier = Modifier.weight(0.3f))
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.weight(0.7f))
                }
                OutlinedTextField(headers, { headers = it }, label = { Text(tr("Headers, one per line")) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                if (draft.body != null && textBody == null) {
                    NoteRow(tr("The body is binary, %s, and is sent as recorded.", byteSize(draft.body.size.toLong())), warning = false)
                } else {
                    OutlinedTextField(body, { body = it }, label = { Text(tr("Body")) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = ::send, enabled = !sending && url.startsWith("http")) {
                    Icon(Icons.Rounded.Send, null)
                    Text(if (sending) tr("Sending…") else tr("Send"))
                }
            }
        }
        result?.let { answer ->
            card(
                header = tr("Response"),
                key = "result",
                rows = buildList {
                    if (answer.error != null) add { NoteRow(answer.error, warning = true) }
                    answer.status?.let { status -> add { InfoRow(tr("Status"), listOfNotNull(status.toString(), answer.statusText).joinToString(" "), mono = true) } }
                    add { InfoRow(tr("Duration"), millis(answer.durationMs)) }
                    add { InfoRow(tr("Size"), byteSize(answer.body.size.toLong())) }
                    for (field in answer.headers) add { FieldRow(field) }
                },
            )
            if (answer.body.isNotEmpty()) {
                freeCard("result-body", header = tr("Response body")) {
                    if (answer.cut) {
                        NoteRow(tr("Only the first %s of this body were kept.", byteSize(HttpAssembler.RESPONSE_CAP.toLong())), warning = true)
                    }
                    val type = answer.headers.firstOrNull { it.name.equals("Content-Type", true) }?.value
                    when (val view = remember(answer) { BodyPreview.of(answer.body, type) }) {
                        is BodyView.Json -> BodyTextPreview(view.text, tr("Response body"), model, nav)
                        is BodyView.Text -> BodyTextPreview(view.text, tr("Response body"), model, nav)
                        is BodyView.Binary -> SelectionContainer {
                            Text(view.hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        is BodyView.Image -> {
                            val bitmap = remember(view) { BitmapFactory.decodeByteArray(view.bytes, 0, view.bytes.size) }
                            if (bitmap != null) Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                        }
                        BodyView.Empty -> Unit
                    }
                }
            }
        }
    }
}

/** How fast connections may go while recording, like Throttle Settings in Charles. */
@Composable
fun TrafficThrottleScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val traffic = model.traffic
    val current = traffic.throttle
    var down by rememberSaveable { mutableStateOf(current.downKbps.takeIf { it > 0 }?.toString().orEmpty()) }
    var up by rememberSaveable { mutableStateOf(current.upKbps.takeIf { it > 0 }?.toString().orEmpty()) }
    var latency by rememberSaveable { mutableStateOf(current.latencyMs.takeIf { it > 0 }?.toString().orEmpty()) }
    SpectraScreen(title = tr("Throttling"), onBack = nav::back) {
        item(key = "presets") {
            ChipRow(contentPadding = FilterChipPadding) {
                ToggleChip(tr("Off"), !current.enabled, { traffic.setThrottle(ThrottleSettings()) })
                ThrottleSettings.PRESETS.forEach { preset ->
                    ToggleChip(presetName(preset.preset), current.preset == preset.preset, {
                        traffic.setThrottle(preset)
                        down = preset.downKbps.toString()
                        up = preset.upKbps.toString()
                        latency = preset.latencyMs.toString()
                    })
                }
                ToggleChip(tr("Custom"), current.preset == ThrottleSettings.CUSTOM, {
                    traffic.setThrottle(customThrottle(down, up, latency))
                })
            }
        }
        card(key = "throttle-note", rows = listOf {
            NoteRow(
                if (current.enabled) {
                    tr("Download %s, upload %s, delay %s. Applies to the recorded connections right away.",
                        rate(current.downKbps), rate(current.upKbps), millis(current.latencyMs.toLong()))
                } else {
                    tr("Connections go at full speed.")
                },
                warning = current.enabled,
            )
        })
        freeCard(key = "custom", header = tr("Custom"), footer = tr("Empty or 0 leaves that direction unlimited.")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(down, { down = it.filter(Char::isDigit).take(7) }, label = { Text(tr("Download, kbit/s")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(up, { up = it.filter(Char::isDigit).take(7) }, label = { Text(tr("Upload, kbit/s")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(latency, { latency = it.filter(Char::isDigit).take(5) }, label = { Text(tr("Delay, ms")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { traffic.setThrottle(customThrottle(down, up, latency)) }) { Text(tr("Apply")) }
            }
        }
    }
}

private fun customThrottle(down: String, up: String, latency: String) = ThrottleSettings(
    ThrottleSettings.CUSTOM,
    downKbps = down.toIntOrNull() ?: 0,
    upKbps = up.toIntOrNull() ?: 0,
    latencyMs = latency.toIntOrNull() ?: 0,
)

private fun presetName(name: String): String = when (name) {
    "edge" -> "EDGE"
    "3g" -> "3G"
    "fast3g" -> tr("Fast 3G")
    "4g" -> "4G"
    else -> name
}

private fun rate(kbps: Int): String = when {
    kbps <= 0 -> tr("unlimited")
    kbps >= 1000 -> tr("%.1f Mbit/s", kbps / 1000.0)
    else -> tr("%d kbit/s", kbps)
}

private fun throttleSummary(settings: ThrottleSettings): String = when (settings.preset) {
    ThrottleSettings.OFF -> tr("Off")
    ThrottleSettings.CUSTOM -> tr("Custom: %s down, %s up", rate(settings.downKbps), rate(settings.upKbps))
    else -> presetName(settings.preset)
}

private fun mapLocalSummary(traffic: TrafficSniffer): String {
    val on = traffic.mapRules.count { it.enabled }
    return when {
        traffic.mapRules.isEmpty() -> tr("Answer chosen requests from the phone")
        !traffic.decryptTls -> tr("Rules on: %d. Works only with Decrypt TLS on.", on)
        else -> tr("Rules on: %d", on)
    }
}

/** Map Local rules: requests they match are answered by the phone. */
@Composable
fun TrafficMapLocalScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val traffic = model.traffic
    val rules = traffic.mapRules
    SpectraScreen(
        title = tr("Map Local"),
        onBack = nav::back,
        actions = {
            IconButton(onClick = {
                traffic.ruleDraft = MapRule(java.util.UUID.randomUUID().toString(), host = "", body = "")
                nav.trafficMapRule(null)
            }) { Icon(Icons.Rounded.Add, contentDescription = tr("Add rule")) }
        },
        overlay = {
            if (rules.isEmpty()) {
                EmptyState(Icons.Rounded.SwapHoriz, tr("No rules"), tr("Open a request and choose Map locally, or add a rule here."))
            }
        },
    ) {
        card(key = "map-note", rows = listOf {
            NoteRow(
                tr("A matching request over decrypted HTTPS gets this answer from the phone and never reaches the server. Hosts with rules are kept on HTTP/1.1. Changes apply to new connections."),
                warning = false,
            )
        })
        cardSection(rules, key = { it.id }, sectionKey = "rules") { rule ->
            ListRow(
                title = rule.host + rule.path.ifEmpty { "/*" },
                subtitle = "${rule.status} · ${rule.contentType.ifBlank { "—" }} · ${byteSize(rule.body.toByteArray().size.toLong())}",
                dimmed = !rule.enabled,
                trailing = {
                    Switch(checked = rule.enabled, onCheckedChange = { on ->
                        traffic.setMapRules(rules.map { if (it.id == rule.id) it.copy(enabled = on) else it })
                    })
                },
                onClick = { nav.trafficMapRule(rule.id) },
            )
        }
    }
}

/** One Map Local rule, new or existing. */
@Composable
fun TrafficMapRuleScreen(model: SpectraModel, nav: Navigator, ruleId: String?) {
    model.generation
    val traffic = model.traffic
    val existing = ruleId?.let { id -> traffic.mapRules.firstOrNull { it.id == id } }
    val start = existing ?: traffic.ruleDraft ?: MapRule(java.util.UUID.randomUUID().toString(), host = "")
    var host by rememberSaveable { mutableStateOf(start.host) }
    var path by rememberSaveable { mutableStateOf(start.path) }
    var status by rememberSaveable { mutableStateOf(start.status.toString()) }
    var type by rememberSaveable { mutableStateOf(start.contentType) }
    var body by rememberSaveable { mutableStateOf(start.body) }
    val valid = TlsHostFilter.normalize(host) != null && (status.toIntOrNull() ?: 0) in 100..599
    fun save() {
        val rule = start.copy(
            host = TlsHostFilter.normalize(host) ?: return,
            path = path.trim(),
            status = status.toIntOrNull() ?: 200,
            contentType = type.trim(),
            body = body,
        )
        val rules = traffic.mapRules
        traffic.setMapRules(if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it } else rules + rule)
        traffic.ruleDraft = null
        nav.back()
    }
    SpectraScreen(
        title = if (existing == null) tr("New rule") else tr("Rule"),
        onBack = nav::back,
        actions = {
            if (existing != null) {
                IconButton(onClick = {
                    traffic.setMapRules(traffic.mapRules.filterNot { it.id == existing.id })
                    nav.back()
                }) { Icon(Icons.Rounded.Delete, contentDescription = tr("Delete")) }
            }
        },
    ) {
        freeCard(key = "match", header = tr("Requests"), footer = tr("Path: * stands for any characters; empty matches every path. The query is compared too when the path has one.")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.take(253) }, label = { Text(tr("Host or pattern")) }, placeholder = { Text("api.example.com") },
                    singleLine = true, isError = host.isNotBlank() && TlsHostFilter.normalize(host) == null, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(path, { path = it }, label = { Text(tr("Path")) }, placeholder = { Text("/api/user*") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        freeCard(key = "answer", header = tr("Answer")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(status, { status = it.filter(Char::isDigit).take(3) }, label = { Text(tr("Status")) }, singleLine = true, modifier = Modifier.weight(0.3f))
                    OutlinedTextField(type, { type = it }, label = { Text("Content-Type") }, singleLine = true, modifier = Modifier.weight(0.7f))
                }
                OutlinedTextField(body, { body = it }, label = { Text(tr("Body")) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
                TextButton(onClick = ::save, enabled = valid) { Text(tr("Save")) }
            }
        }
    }
}

/** Which hosts get decrypted, like SSL Proxying in Charles. */
@Composable
fun TrafficHostsScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val traffic = model.traffic
    val filter = traffic.hostFilter
    var draft by rememberSaveable { mutableStateOf("") }
    var draftOnly by rememberSaveable { mutableStateOf(true) }
    val refused = traffic.refusedHosts().filter { filter.allows(it) }
    fun add(onlyList: Boolean) {
        val clean = TlsHostFilter.normalize(draft) ?: return
        val next = if (onlyList) {
            filter.copy(only = (filter.only + clean).distinct())
        } else {
            filter.copy(except = (filter.except + clean).distinct())
        }
        traffic.setHostFilter(next)
        draft = ""
    }
    SpectraScreen(title = tr("Hosts to decrypt"), onBack = nav::back) {
        item(key = "modes") {
            ChipRow(contentPadding = FilterChipPadding) {
                TlsHostFilter.Mode.entries.forEach { mode ->
                    ToggleChip(modeName(mode), filter.mode == mode, { traffic.setHostFilter(filter.copy(mode = mode)) })
                }
            }
        }
        card(key = "mode-note", rows = listOf {
            NoteRow(
                when (filter.mode) {
                    TlsHostFilter.Mode.ALL -> tr("Every host is decrypted. A host whose app refuses the certificate goes through unread.")
                    TlsHostFilter.Mode.ONLY -> tr("Only the hosts in the first list are decrypted, except those in the second. Everything else goes through unread.")
                    TlsHostFilter.Mode.EXCEPT -> tr("Every host is decrypted except those in the list. They go through unread.")
                },
                warning = false,
            )
        })
        if (filter.mode == TlsHostFilter.Mode.ONLY) {
            hostList("only", tr("Decrypt these hosts"), filter.only) { host ->
                traffic.setHostFilter(filter.copy(only = filter.only - host))
            }
        }
        if (filter.mode != TlsHostFilter.Mode.ALL) {
            hostList("except", tr("Leave these encrypted"), filter.except) { host ->
                traffic.setHostFilter(filter.copy(except = filter.except - host))
            }
        }
        if (filter.mode != TlsHostFilter.Mode.ALL) {
            freeCard(
                key = "add",
                header = tr("Add a host"),
                footer = tr("*.example.com matches example.com and all its subdomains. * stands for any characters. Changes apply to new connections."),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(253) },
                    label = { Text(tr("Host or pattern")) },
                    placeholder = { Text("*.example.com") },
                    singleLine = true,
                    isError = draft.isNotBlank() && TlsHostFilter.normalize(draft) == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filter.mode == TlsHostFilter.Mode.ONLY) {
                    ChipRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp)) {
                        ToggleChip(tr("Decrypt"), draftOnly, { draftOnly = true })
                        ToggleChip(tr("Leave encrypted"), !draftOnly, { draftOnly = false })
                    }
                }
                TextButton(
                    onClick = { add(filter.mode == TlsHostFilter.Mode.ONLY && draftOnly) },
                    enabled = TlsHostFilter.normalize(draft) != null,
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Text(tr("Add"))
                }
            }
        }
        if (refused.isNotEmpty()) {
            card(
                header = tr("Refused the certificate"),
                footer = tr("These hosts did not trust the Spectra certificate in this recording, often because the app pins its own. Leaving them encrypted saves a failed first connection next time."),
                key = "refused",
                rows = refused.map { host ->
                    {
                        ListRow(
                            title = host,
                            trailing = {
                                TextButton(onClick = { traffic.setHostFilter(traffic.hostFilter.excluding(host)) }) {
                                    Text(tr("Leave encrypted"))
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.hostList(
    key: String,
    header: String,
    hosts: List<String>,
    onRemove: (String) -> Unit,
) {
    card(
        header = header,
        key = key,
        rows = if (hosts.isEmpty()) {
            listOf { NoteRow(tr("The list is empty."), warning = false) }
        } else {
            hosts.map { host ->
                {
                    ListRow(
                        title = host,
                        trailing = {
                            IconButton(onClick = { onRemove(host) }) {
                                Icon(Icons.Rounded.Close, contentDescription = tr("Remove"))
                            }
                        },
                    )
                }
            }
        },
    )
}

private fun modeName(mode: TlsHostFilter.Mode): String = when (mode) {
    TlsHostFilter.Mode.ALL -> tr("All hosts")
    TlsHostFilter.Mode.ONLY -> tr("Only these")
    TlsHostFilter.Mode.EXCEPT -> tr("All except these")
}

private fun hostFilterSummary(filter: TlsHostFilter): String = when (filter.mode) {
    TlsHostFilter.Mode.ALL -> tr("All hosts")
    TlsHostFilter.Mode.ONLY -> tr("Only listed hosts (%d)", filter.only.size)
    TlsHostFilter.Mode.EXCEPT -> tr("All except listed hosts (%d)", filter.except.size)
}

/** Status, time to the end of the response and size, for a request's row. */
private fun exchangeLine(exchange: HttpExchange): String? {
    val parts = buildList {
        exchange.status?.let { add(it.toString()) }
        exchange.durationMs?.let { add(millis(it)) }
    }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

private fun millis(ms: Long): String = if (ms < 1000) tr("%d ms", ms) else tr("%.2f s", ms / 1000.0)

private fun preciseTime(millis: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(millis))

private fun sizeLine(total: Long, headers: Long?, body: Long?): String =
    if (headers == null) byteSize(total) else tr("%s (headers %s, body %s)", byteSize(total), byteSize(headers), byteSize(body ?: 0))

/** Builds a HAR of [flows] off the main thread and hands it to the share sheet. */
private suspend fun shareHar(context: Context, traffic: TrafficSniffer, sessionId: String?, flows: List<TrafficFlow>) {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    val text = withContext(Dispatchers.IO) {
        Har.build(flows, object : cattivole.spectra.core.BodySource {
            override fun body(flow: TrafficFlow, index: Int, request: Boolean) = traffic.body(sessionId, flow.id, index, request)
            override fun message(flow: TrafficFlow, index: Int, number: Int) = traffic.message(sessionId, flow.id, index, number)
        }, version)
    }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    shareTextFile(context, "spectra-traffic-$stamp.har", text, mime = "application/json")
}

internal data class TrafficHit(val flow: TrafficFlow, val index: Int?)

internal fun trafficKey(scope: String, hit: TrafficHit): String = "$scope:${hit.flow.id}:${hit.index ?: "-"}"

