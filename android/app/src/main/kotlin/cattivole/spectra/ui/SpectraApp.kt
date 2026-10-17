package cattivole.spectra.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.MacAddress
import cattivole.spectra.core.RadioSource
import cattivole.spectra.platform.Permissions
import cattivole.spectra.platform.SensorLink

private enum class Tab(val route: String, val label: String) {
    Networks("networks", "Wi‑Fi"),
    Lan("lan", "LAN"),
    Bluetooth("bluetooth", "Bluetooth"),
    History("history", "History"),
    Sensor("sensor", "Sensor"),
}

@Composable
fun SpectraApp(model: SpectraModel) {
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.link.refreshAvailability(connectSaved = true)
        model.phone.refresh()
    }
    LaunchedEffect(Unit) {
        val missing = Permissions.missing(context)
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    val topLevel = Tab.entries.any { it.route == current }
    Scaffold(
        bottomBar = {
            if (topLevel) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { openTab(nav, tab.route) },
                            icon = { Icon(tab.icon(), contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Networks.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Networks.route) {
                NetworksScreen(
                    model,
                    onOpen = { nav.navigate("network/${it.routeId()}") },
                    onOpenHost = { nav.navigate("host/${Uri.encode(it)}") },
                    onSensor = { openTab(nav, Tab.Sensor.route) },
                    onChannels = { nav.navigate("channels") },
                )
            }
            composable(
                "network/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                NetworkDetail(
                    model,
                    MacAddress.parse(entry.arguments?.getString("id").orEmpty().replace('-', ':')),
                    onOpenHost = { nav.navigate("host/${Uri.encode(it)}") },
                )
            }
            composable("channels") { ChannelsScreen(model, onBack = { nav.popBackStack() }) }
            composable(Tab.Lan.route) { LanScreen(model, onOpen = { nav.navigate("host/${Uri.encode(it)}") }) }
            composable(
                "host/{ip}",
                arguments = listOf(navArgument("ip") { type = NavType.StringType }),
            ) { entry ->
                LanDetail(model, Uri.decode(entry.arguments?.getString("ip").orEmpty()))
            }
            composable(Tab.Bluetooth.route) {
                LaunchedEffect(Unit) { model.ble.resumeUnlessPaused() }
                BluetoothScreen(model, onOpen = { nav.navigate("ble/${Uri.encode(it)}") })
            }
            composable(
                "ble/{address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType }),
            ) { entry ->
                BleDetail(model, Uri.decode(entry.arguments?.getString("address").orEmpty()))
            }
            composable(Tab.History.route) {
                HistoryScreen(model, onOpen = { nav.navigate("history/${Uri.encode(it)}") })
            }
            composable(
                "history/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                HistoryDetail(model, Uri.decode(entry.arguments?.getString("id").orEmpty()), onBack = { nav.popBackStack() })
            }
            composable(Tab.Sensor.route) { SensorScreen(model) }
        }
    }
}

private fun openTab(nav: androidx.navigation.NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(Tab.Networks.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun sensorHeadline(model: SpectraModel): String = when (val state = model.link.state) {
    is SensorLink.State.Connected -> state.name
    SensorLink.State.Demo -> "Demo sensor"
    is SensorLink.State.Connecting -> state.name
    SensorLink.State.Searching -> "Searching"
    SensorLink.State.PoweredOff -> "Bluetooth is off"
    SensorLink.State.Unauthorized -> "Bluetooth permission"
    SensorLink.State.Unsupported -> "No Bluetooth"
    SensorLink.State.Idle, SensorLink.State.Starting -> "No sensor"
}

internal fun sensorDetail(model: SpectraModel): String {
    val battery = model.link.batteryLevel?.let { level ->
        if (model.link.isCharging == true) "$level% charging" else "$level%"
    }
    return listOfNotNull(
        if (model.store.isScanning) "Scanning" else null,
        battery,
    ).joinToString(" · ").ifEmpty {
        when (model.link.state) {
            is SensorLink.State.Connected, SensorLink.State.Demo -> "Connected"
            is SensorLink.State.Connecting -> "Connecting"
            SensorLink.State.Searching -> "Looking for a Cardputer"
            else -> "Tap to connect"
        }
    }
}

private fun Tab.icon() = when (this) {
    Tab.Networks -> Icons.Default.Wifi
    Tab.Lan -> Icons.Default.SettingsInputAntenna
    Tab.Bluetooth -> Icons.Default.Bluetooth
    Tab.History -> Icons.Default.History
    Tab.Sensor -> Icons.Default.Sensors
}

internal fun cattivole.spectra.core.MacAddress.routeId(): String = text.replace(':', '-')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenColumn(
    title: String,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column(
                    if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier,
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = navigationIcon,
            actions = { actions() },
        )
        content(PaddingValues(0.dp))
    }
}

@Composable
internal fun SourceBadge(source: RadioSource) {
    Text(
        if (source == RadioSource.SENSOR) "Sensor" else "Phone",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun SimpleList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), content = content)
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun ClickRow(
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(trailing, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}
