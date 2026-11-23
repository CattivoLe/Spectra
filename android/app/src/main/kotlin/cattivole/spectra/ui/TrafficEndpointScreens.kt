package cattivole.spectra.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.assistant.CardSubject
import cattivole.spectra.core.Endpoint
import cattivole.spectra.core.EndpointGroup
import cattivole.spectra.core.RequestFilter
import cattivole.spectra.core.RequestHit
import cattivole.spectra.core.TrafficFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** What the groups are made of: when none of it changes, they stand. Cheap to compare, unlike the list itself. */
private data class GroupingMark(
    val filter: RequestFilter, val connections: Int, val closed: Int, val lastAt: Long, val bytes: Long, val requests: Int,
) {
    companion object {
        fun of(flows: List<TrafficFlow>, filter: RequestFilter) = GroupingMark(
            filter, flows.size, flows.count { it.closed }, flows.maxOfOrNull { it.lastAt } ?: 0,
            flows.sumOf { it.sent + it.received }, flows.sumOf { it.exchanges.size },
        )
    }
}

/**
 * The endpoints of [flows], worked out off the main thread: a live recording changes several times a second. Null
 * until the first are ready. What is shown stays until the next are.
 */
@Composable
internal fun rememberEndpointGroups(flows: List<TrafficFlow>, filter: RequestFilter, on: Boolean): List<EndpointGroup>? {
    var groups by remember { mutableStateOf<List<EndpointGroup>?>(null) }
    val mark = if (on) GroupingMark.of(flows, filter) else null
    LaunchedEffect(mark) {
        if (mark != null) groups = withContext(Dispatchers.Default) { EndpointGroup.build(flows, filter) }
    }
    return groups
}

/** The count on the left, the list or the endpoints on the right. */
@Composable
internal fun GroupingRow(count: String, byEndpoint: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            count,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        SingleChoiceSegmentedButtonRow {
            for ((index, value) in listOf(false, true).withIndex()) {
                SegmentedButton(
                    selected = byEndpoint == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                ) { Text(if (value) tr("By endpoint") else tr("List##requests"), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
    }
}

private sealed class EndpointItem(val key: String) {
    class Head(val group: EndpointGroup) : EndpointItem("head:${group.key}")
    class About(val group: EndpointGroup) : EndpointItem("about:${group.key}")
    class Request(val group: EndpointGroup, val hit: RequestHit) : EndpointItem("hit:${hit.flow.id}:${hit.index}")
}

/** The requests grouped by endpoint. A row opens its group; inside, the same rows as in the list. */
internal fun LazyListScope.endpointRows(
    groups: List<EndpointGroup>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onAbout: (String) -> Unit,
    focus: String?,
    scope: String,
    onFocus: (String) -> Unit,
    onOpen: (RequestHit) -> Unit,
) {
    val items = groups.flatMap { group ->
        buildList {
            add(EndpointItem.Head(group))
            if (group.key in expanded) {
                add(EndpointItem.About(group))
                for (hit in group.shown) add(EndpointItem.Request(group, hit))
            }
        }
    }
    cardSection(items, key = { it.key }, sectionKey = "endpoints", dividerInset = 16.dp) { item ->
        when (item) {
            is EndpointItem.Head -> EndpointRow(item.group, open = item.group.key in expanded) { onToggle(item.group.key) }
            is EndpointItem.About -> ActionRow(tr("About this endpoint"), Icons.Rounded.Info, onClick = { onAbout(item.group.key) }, modifier = Modifier.padding(start = 16.dp))
            is EndpointItem.Request -> {
                val key = trafficKey(scope, TrafficHit(item.hit.flow, item.hit.index))
                androidx.compose.foundation.layout.Box(Modifier.padding(start = 16.dp)) {
                    FlowRow(item.hit.flow, item.hit.exchange, focused = key == focus, onFocus = { onFocus(key) }, onClick = { onOpen(item.hit) })
                }
            }
        }
    }
}

/** An endpoint: what is asked, of whom, how often, how often in vain and how fast. */
@Composable
private fun EndpointRow(group: EndpointGroup, open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    ListRow(
        title = "${group.endpoint.method} ${group.endpoint.path}",
        subtitleContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    group.endpoint.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "· " + preciseClock(group.lastAt),
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        detailContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (group.errors > 0) {
                    Text(tr("Errors: %d", group.errors) + if (endpointLine(group).isEmpty()) "" else " ·", style = MaterialTheme.typography.bodySmall, color = Spectra.colors.danger, maxLines = 1)
                }
                Text(
                    endpointLine(group),
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        badges = {
            if (group.http2) Pill("H2", MaterialTheme.colorScheme.primary)
            if (group.webSocket) Pill("WS", MaterialTheme.colorScheme.tertiary)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(
                    if (group.shown.size == group.hits.size) "${group.hits.size}" else "${group.shown.size}/${group.hits.size}",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp).rotate(turn))
            }
        },
        onClick = onClick,
    )
}

/** Typical and worst duration and size, for an endpoint's row. */
private fun endpointLine(group: EndpointGroup): String = buildList {
    val typical = group.typicalMs
    val worst = group.worstMs
    if (typical != null && worst != null) add(if (typical == worst) millis(typical) else tr("%s, worst %s", millis(typical), millis(worst)))
    if (group.bytes > 0) add(byteSize(group.bytes))
}.joinToString(" · ")

private fun preciseClock(millis: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))

/** One endpoint of a recording: its numbers and every request of it. Follows a recording in progress. */
@Composable
fun TrafficEndpointScreen(model: SpectraModel, nav: Navigator, key: String, sessionId: String?) {
    model.generation
    val traffic = model.traffic
    val flows = if (sessionId == null) traffic.flows else traffic.session(sessionId)?.flows.orEmpty()
    var group by remember { mutableStateOf<EndpointGroup?>(null) }
    var looked by remember { mutableStateOf(false) }
    val mark = GroupingMark.of(flows, RequestFilter())
    LaunchedEffect(mark) {
        // What is on screen stays when the recording is cleared or a new one begins.
        withContext(Dispatchers.Default) { EndpointGroup.find(key, flows) }?.let { group = it }
        looked = true
    }
    val shown = group
    if (shown == null) {
        SpectraScreen(tr("Endpoint"), onBack = nav::back, overlay = {
            if (looked) EmptyState(Icons.Rounded.WifiTethering, tr("Gone"), tr("These requests are no longer in the list."))
        }) {}
        return
    }
    val scope = sessionId ?: "live"
    SpectraScreen(
        title = shown.endpoint.path.ifEmpty { shown.endpoint.host },
        subtitle = shown.endpoint.host,
        onBack = nav::back,
        actions = { AskAssistantButton(model, nav, subject = { CardSubject.Endpoint(sessionId, key) }) },
    ) {
        card(
            header = tr("Endpoint"),
            key = "endpoint",
            footer = if (Endpoint.PLACEHOLDER in shown.endpoint.path) tr("{id} stands for the part that changes from request to request.") else null,
            rows = listOf(
                { InfoRow(tr("Method"), shown.endpoint.method, mono = true) },
                { InfoRow(tr("Host"), shown.endpoint.host, copyable = true) },
                { InfoRow(tr("Path"), shown.endpoint.path, mono = true, copyable = true) },
            ),
        )
        card(
            key = "numbers",
            rows = buildList {
                add { InfoRow(tr("Requests"), "${shown.hits.size}") }
                add { InfoRow(tr("Errors"), "${shown.errors}", valueColor = if (shown.errors > 0) Spectra.colors.danger else MaterialTheme.colorScheme.onSurface) }
                val statuses = shown.statuses.joinToString(", ") { (status, count) -> if (status == 0) tr("no answer ×%d", count) else "$status ×$count" }
                if (statuses.isNotEmpty()) add { InfoRow(tr("Statuses"), statuses) }
                shown.typicalMs?.let { add { InfoRow(tr("Typical duration"), millis(it)) } }
                shown.worstMs?.let { add { InfoRow(tr("Longest duration"), millis(it)) } }
                if (shown.bytes > 0) add { InfoRow(tr("Size"), byteSize(shown.bytes)) }
                add { InfoRow(tr("First request"), preciseClock(shown.firstAt)) }
                add { InfoRow(tr("Last request"), preciseClock(shown.lastAt)) }
            },
        )
        cardSection(shown.hits, header = tr("Requests: %d", shown.hits.size), key = { "${it.flow.id}:${it.index}" }, sectionKey = "hits", dividerInset = 70.dp) { hit ->
            val focusKey = trafficKey(scope, TrafficHit(hit.flow, hit.index))
            FlowRow(
                hit.flow, hit.exchange,
                focused = traffic.focusedKey == focusKey,
                onFocus = { traffic.toggleFocus(focusKey) },
                onClick = { if (sessionId != null) nav.savedTrafficFlow(sessionId, hit.flow.id, hit.index) else nav.trafficFlow(hit.flow.id, hit.index) },
            )
        }
    }
}
