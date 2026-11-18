package cattivole.spectra.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cattivole.spectra.core.AccessReport
import cattivole.spectra.core.InternetAccess
import cattivole.spectra.core.ProbeRoute
import cattivole.spectra.core.ResourceReach
import cattivole.spectra.platform.LinkProbe

internal fun InternetAccess.title(): String = when (this) {
    InternetAccess.FULL -> tr("Full access")
    InternetAccess.FILTERED -> tr("Some services blocked")
    InternetAccess.WHITELIST_ONLY -> tr("Allow-list only")
    InternetAccess.NONE -> tr("No access")
}

private fun InternetAccess.detail(): String = when (this) {
    InternetAccess.FULL -> tr("Foreign sites open, and so do services that are often blocked.")
    InternetAccess.FILTERED -> tr("The internet works, but services that are often blocked do not open.")
    InternetAccess.WHITELIST_ONLY -> tr("Only sites from the provider's allow-list open. The rest of the internet does not answer.")
    InternetAccess.NONE -> tr("Neither foreign sites nor allow-listed ones answer.")
}

@Composable
internal fun InternetAccess.color(): Color = when (this) {
    InternetAccess.FULL -> LampGreen
    InternetAccess.FILTERED, InternetAccess.WHITELIST_ONLY -> LampYellow
    InternetAccess.NONE -> LampRed
}

// A real signal's colours, the same in light and dark themes: the lamps sit
// in a dark housing either way.
private val LampRed = Color(0xFFFF3B30)
private val LampYellow = Color(0xFFFFC400)
private val LampGreen = Color(0xFF30D158)
private val LampHousing = Color(0xFF1F2023)

/**
 * Three lamps in a dark housing, one lit: green for the open internet,
 * yellow for blocked services or an allow-list, red for nothing. An unlit
 * lamp keeps a dim tint of its own colour, so all three read as lamps.
 */
@Composable
fun TrafficLight(access: InternetAccess?, lamp: Dp = 12.dp, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(lamp * 0.7f))
            .background(LampHousing)
            .padding(lamp * 0.4f),
        verticalArrangement = Arrangement.spacedBy(lamp * 0.3f),
    ) {
        for (state in listOf(InternetAccess.NONE, InternetAccess.WHITELIST_ONLY, InternetAccess.FULL)) {
            val lit = access != null && state.color() == access.color()
            val color = state.color()
            Box(
                Modifier
                    .size(lamp)
                    .then(if (lit) Modifier.shadow(lamp * 0.5f, CircleShape, ambientColor = color, spotColor = color) else Modifier)
                    .clip(CircleShape)
                    .background(if (lit) color else color.copy(alpha = 0.22f)),
            )
        }
    }
}

/** Wi-Fi, the mobile network, or whatever the system uses. */
@Composable
internal fun RouteRow(link: LinkProbe, wifiJoined: Boolean? = null) {
    // Without Wi-Fi a Wi-Fi check has nothing to measure: go through the mobile network, and come back
    // to Wi-Fi when it returns, unless the reader picked the route themself.
    var autoCellular by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(wifiJoined) {
        if (wifiJoined == null || link.running) return@LaunchedEffect
        if (!wifiJoined && link.route == ProbeRoute.WIFI) {
            link.route = ProbeRoute.CELLULAR
            autoCellular = true
        } else if (wifiJoined && autoCellular && link.route == ProbeRoute.CELLULAR) {
            link.route = ProbeRoute.WIFI
            autoCellular = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Check through"), style = MaterialTheme.typography.bodyLarge)
        ChipRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            ToggleChip("Wi-Fi", link.route == ProbeRoute.WIFI, { if (!link.running) { link.route = ProbeRoute.WIFI; autoCellular = false } })
            ToggleChip(tr("Cellular"), link.route == ProbeRoute.CELLULAR, { if (!link.running) { link.route = ProbeRoute.CELLULAR; autoCellular = false } })
            ToggleChip("VPN", link.route == ProbeRoute.VPN, { if (!link.running) { link.route = ProbeRoute.VPN; autoCellular = false } })
        }
        Text(routeText(link.route), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun routeText(route: ProbeRoute): String = when (route) {
    ProbeRoute.VPN -> tr("Speed, external address and countries are measured through the VPN tunnel.")
    ProbeRoute.CELLULAR -> tr("The check goes out through the mobile network, even while Wi-Fi is joined. It uses mobile data.")
    ProbeRoute.WIFI -> tr("The VPN is bypassed: the check measures the Wi-Fi or Ethernet connection itself.")
}

/** The verdict with its lamp, and the sites behind it. */
internal fun LazyListScope.accessCard(report: AccessReport) {
    freeCard(
        "access",
        header = tr("Access"),
        footer = tr("Large foreign sites, services that are often blocked, and sites from the Russian allow-list are asked over HTTPS. Two often-blocked services that do not open mean the connection is filtered."),
    ) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val verdict = report.verdict
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TrafficLight(verdict, lamp = 14.dp)
            Column(Modifier.weight(1f)) {
                Text(verdict.title(), style = MaterialTheme.typography.titleLarge)
                Text(verdict.detail(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (verdict == InternetAccess.FILTERED) {
                    Text(
                        tr("Not opening: %s", report.unreachable.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Spectra.colors.warning,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (expanded) tr("Hide sites") else tr("Sites asked"))
        }
        if (expanded) {
            for (reach in report.open) ReachLine(reach, null)
            for (reach in report.blocked.orEmpty()) ReachLine(reach, tr("often blocked"))
            for (reach in report.listed) ReachLine(reach, tr("allow-list"))
        }
    }
}

@Composable
private fun ReachLine(reach: ResourceReach, note: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (note == null) reach.host else "${reach.host} · $note",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (reach.ok) tr("%d ms", reach.millis ?: 0) else tr("No reply"),
            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
            color = if (reach.ok) Spectra.colors.success else Spectra.colors.danger,
        )
    }
}

/** The person's own sites as chips. Tap one to ask it; hold to remove it. */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.resourcesCard(link: LinkProbe) {
    freeCard(
        "resources",
        header = tr("Resources"),
        footer = tr("Tap a site to see whether it opens from the chosen connection; hold it to remove. Each one is asked over HTTPS without loading the page."),
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        var rejected by rememberSaveable { mutableStateOf(false) }
        val add = {
            if (text.isNotBlank()) {
                if (link.addResource(text)) text = "" else rejected = true
            }
        }
        if (link.resources.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (host in link.resources) {
                    ResourceChip(
                        host,
                        link.resourceResults[host],
                        host in link.checkingResources,
                        onCheck = { link.checkResource(host) },
                        onRemove = { link.removeResource(host) },
                    )
                }
            }
            TextButton(
                onClick = { link.checkAllResources() },
                enabled = link.checkingResources.isEmpty(),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                androidx.compose.material3.Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(tr("Check all"), modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    rejected = false
                },
                placeholder = { Text(tr("Add a site, like youtube.com")) },
                singleLine = true,
                isError = rejected,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = add, enabled = text.isNotBlank()) { Text(tr("Add")) }
        }
        if (rejected) {
            Text(
                tr("That is not a site name, or it is already in the list."),
                style = MaterialTheme.typography.bodySmall,
                color = Spectra.colors.danger,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResourceChip(host: String, reach: ResourceReach?, checking: Boolean, onCheck: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val color = when {
        reach == null || checking -> MaterialTheme.colorScheme.outline
        reach.ok -> Spectra.colors.success
        else -> Spectra.colors.danger
    }
    Box {
        Row(
            Modifier
                .clip(CircleShape)
                .background(if (reach == null || checking) MaterialTheme.colorScheme.surfaceContainerHigh else color.copy(alpha = 0.16f))
                .combinedClickable(onClick = onCheck, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
            } else {
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            }
            Text(host, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (reach != null && !checking && reach.ok && reach.millis != null) {
                Text(tr("%d ms", reach.millis), style = MaterialTheme.typography.labelMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(tr("Check")) }, onClick = {
                menu = false
                onCheck()
            })
            DropdownMenuItem(text = { Text(tr("Remove")) }, onClick = {
                menu = false
                onRemove()
            })
        }
    }
}
