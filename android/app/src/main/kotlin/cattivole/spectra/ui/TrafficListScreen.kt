package cattivole.spectra.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.ConnectionFilter
import cattivole.spectra.core.RequestFilter
import cattivole.spectra.core.matches

/**
 * A recording as a list of requests, newest first, or of the connections under them, each with its own filters: the
 * one in progress or the last one, or a saved session.
 */
@Composable
fun TrafficListScreen(model: SpectraModel, nav: Navigator, sessionId: String?) {
    model.generation
    val traffic = model.traffic
    val session = sessionId?.let { traffic.session(it) }
    if (sessionId != null && session == null) {
        SpectraScreen(tr("Traffic"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.WifiTethering, tr("Gone"), tr("This saved recording is no longer here."))
        }) {}
        return
    }
    val flows = session?.flows ?: traffic.flows
    val scope = sessionId ?: "live"
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("spectra", android.content.Context.MODE_PRIVATE) }
    var connections by remember { mutableStateOf(prefs.getBoolean(SHOWS_CONNECTIONS, false)) }
    var query by rememberSaveable { mutableStateOf("") }
    var methods by rememberSaveable { mutableStateOf(listOf<String>()) }
    var failedOnly by rememberSaveable { mutableStateOf(false) }
    var webSocketOnly by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf<ConnectionFilter?>(null) }
    val filter = RequestFilter(methods.toSet(), failedOnly, webSocketOnly, query)
    val needle = query.trim()
    val focus = traffic.focusedKey
    val listState = rememberLazyListState()
    LaunchedEffect(focus) {
        if (focus != null) listState.animateScrollToItem(0)
    }
    val all = flows.flatMap { flow -> flow.exchanges.indices.map { TrafficHit(flow, it) } }
    val hits = if (connections) {
        flows.filter { ConnectionFilter.passes(kind, it) && it.matches(needle) }
            .map { TrafficHit(it, null) }
            .sortedWith(compareByDescending<TrafficHit> { trafficKey(scope, it) == focus }.thenBy { it.flow.closed }.thenByDescending { it.flow.lastAt })
    } else {
        all.filter { filter.passes(it.flow.exchanges[it.index!!], it.flow) || trafficKey(scope, it) == focus }
            .sortedWith(compareByDescending<TrafficHit> { trafficKey(scope, it) == focus }.thenByDescending { it.flow.exchanges[it.index!!].startedAt ?: it.flow.lastAt })
    }
    val total = if (connections) flows.size else all.size
    val narrowing = needle.isNotEmpty() || if (connections) kind != null else filter.isNarrowing
    SpectraScreen(
        title = when {
            session != null -> tr("Recording")
            traffic.running -> tr("Recording…")
            else -> tr("Traffic")
        },
        subtitle = (session?.startedAt ?: traffic.startedAt).takeIf { it > 0 }?.let(::dateTime),
        onBack = nav::back,
        actions = { TrafficActions(model, nav, session) },
        searchQuery = query,
        onSearchChange = { query = it },
        searchPlaceholder = tr("Host, app or address"),
        listState = listState,
        overlay = {
            if (hits.isEmpty()) {
                when {
                    narrowing -> EmptyState(
                        Icons.Rounded.WifiTethering,
                        tr("Nothing in this filter"),
                        if (needle.isNotEmpty()) tr("Nothing matches “%s”.", needle) else tr("No connection matches these filters."),
                        action = tr("Show all"),
                        onAction = {
                            query = ""
                            methods = emptyList()
                            failedOnly = false
                            webSocketOnly = false
                            kind = null
                        },
                    )
                    session == null && traffic.running -> EmptyState(
                        Icons.Rounded.WifiTethering,
                        tr("Recording…"),
                        tr("Connections show up here as this phone opens them."),
                        busy = true,
                    )
                    connections -> EmptyState(Icons.Rounded.WifiTethering, tr("No connections yet"), tr("Only DNS lookups so far. Turn their filter on to see them."))
                    else -> EmptyState(Icons.Rounded.WifiTethering, tr("No requests read yet"), tr("Requests inside encrypted connections show up only when TLS is decrypted."))
                }
            }
        },
    ) {
        item(key = "what") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                for ((index, shows) in listOf(false, true).withIndex()) {
                    SegmentedButton(
                        selected = connections == shows,
                        onClick = {
                            connections = shows
                            prefs.edit().putBoolean(SHOWS_CONNECTIONS, shows).apply()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(if (shows) tr("Connections") else tr("Requests")) }
                }
            }
        }
        item(key = "filters") {
            ChipRow(contentPadding = FilterChipPadding) {
                if (connections) {
                    for (entry in ConnectionFilter.entries) {
                        ToggleChip("${entry.name} ${flows.count(entry::passes)}", kind == entry, { kind = if (kind == entry) null else entry })
                    }
                } else {
                    for (method in RequestFilter.methods(flows)) {
                        ToggleChip(method, method in methods, { methods = filter.toggled(method).methods.toList() })
                    }
                    ToggleChip(tr("Errors"), failedOnly, { failedOnly = !failedOnly }, Icons.Rounded.ErrorOutline)
                    if (webSocketOnly || flows.any { flow -> flow.exchanges.any { it.messages.isNotEmpty() } }) {
                        ToggleChip("WebSocket", webSocketOnly, { webSocketOnly = !webSocketOnly })
                    }
                }
            }
        }
        val refused = flows.count { it.refused }
        cardSection(
            hits,
            header = when {
                connections -> tr("Connections: %d of %d", hits.size, total)
                hits.size == total -> tr("Requests: %d", total)
                else -> tr("Requests: %d of %d", hits.size, total)
            },
            footer = when {
                connections && kind == null -> tr("DNS lookups are left out until their filter is on.")
                !connections && refused > 0 -> tr("Connections that refused the certificate and went through unread: %d.", refused)
                else -> null
            },
            key = { trafficKey(scope, it) },
            sectionKey = "hits",
            dividerInset = 70.dp,
        ) { hit ->
            FlowRow(
                hit.flow,
                hit.index?.let { hit.flow.exchanges.getOrNull(it) },
                focused = trafficKey(scope, hit) == focus,
                onFocus = { traffic.toggleFocus(trafficKey(scope, hit)) },
                onClick = {
                    if (session != null) nav.savedTrafficFlow(session.id, hit.flow.id, hit.index)
                    else nav.trafficFlow(hit.flow.id, hit.index)
                },
            )
        }
    }
}

private const val SHOWS_CONNECTIONS = "trafficShowsConnections"
