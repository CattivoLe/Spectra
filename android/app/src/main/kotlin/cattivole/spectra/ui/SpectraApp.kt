package cattivole.spectra.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.MacAddress
import cattivole.spectra.platform.Permissions
import kotlinx.coroutines.launch

/** Each tab is its own graph, so it keeps its back stack while another tab is open. */
private enum class Tab(val graph: String, val start: String, val label: () -> String, val selected: ImageVector, val idle: ImageVector) {
    Networks("wifi-tab", "networks", { "Wi‑Fi" }, Icons.Filled.Wifi, Icons.Outlined.Wifi),
    Lan("lan-tab", "lan", { "LAN" }, Icons.Filled.Lan, Icons.Outlined.Lan),
    Bluetooth("bluetooth-tab", "bluetooth", { "Bluetooth" }, Icons.Filled.Bluetooth, Icons.Outlined.Bluetooth),
    History("history-tab", "history", { tr("History") }, Icons.Filled.History, Icons.Outlined.History),
    Sensor("sensor-tab", "sensor", { tr("Sensor") }, Icons.Filled.SettingsInputAntenna, Icons.Outlined.SettingsInputAntenna),
}

/** Where a screen goes next. One object so screens do not each take five lambdas. */
class Navigator(private val nav: NavHostController) {
    fun back() {
        nav.popBackStack()
    }

    fun network(id: MacAddress, entry: String? = null) =
        nav.navigate(if (entry == null) "network/${id.routeId()}" else "saved/network/${Uri.encode(entry)}/${id.routeId()}")

    fun client(mac: MacAddress, entry: String? = null) =
        nav.navigate(if (entry == null) "client/${mac.routeId()}" else "saved/client/${Uri.encode(entry)}/${mac.routeId()}")

    fun host(ip: String, entry: String? = null) =
        nav.navigate(if (entry == null) "host/${Uri.encode(ip)}" else "saved/host/${Uri.encode(entry)}/${Uri.encode(ip)}")

    fun connection() = nav.navigate("connection")

    fun savedConnection(entry: String, checkedAt: Long) =
        nav.navigate("saved/connection/${Uri.encode(entry)}/$checkedAt")

    fun ble(address: String, entry: String? = null) =
        nav.navigate(if (entry == null) "ble/${Uri.encode(address)}" else "saved/ble/${Uri.encode(entry)}/${Uri.encode(address)}")

    fun channels() = nav.navigate("channels")
    fun history(id: String) = nav.navigate("history/${Uri.encode(id)}")
    fun sensorTab() = openTab(nav, Tab.Sensor)
}

@Composable
fun SpectraApp(model: SpectraModel) {
    val context = LocalContext.current
    var prompted by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.permissionsChanged()
    }
    val requestPermissions = {
        val missing = Permissions.missing(context)
        val activity = Permissions.activity(context)
        val showDialog = activity != null && missing.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        when {
            missing.isEmpty() -> model.permissionsChanged()
            prompted && !showDialog -> context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
            else -> {
                prompted = true
                permissions.launch(missing.toTypedArray())
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !prompted && Permissions.missing(context).isNotEmpty()) {
                requestPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val nav = rememberNavController()
    val navigator = remember(nav) { Navigator(nav) }
    val entry = nav.currentBackStackEntryAsState().value
    val currentTab = Tab.entries.firstOrNull { tab -> entry?.destination?.hierarchy?.any { it.route == tab.graph } == true }

    // The Bluetooth tab listens only while it is showing, its device cards included.
    val onBluetooth = currentTab == Tab.Bluetooth
    LaunchedEffect(onBluetooth) {
        if (onBluetooth) model.ble.resumeUnlessPaused() else model.ble.leave()
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = remember {
        { message ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message)
            }
        }
    }

    ProvideScreenServices(notify = notify, bottomBar = true, requestPermissions = requestPermissions) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    Tab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { openTab(nav, tab) },
                            icon = { Icon(if (selected) tab.selected else tab.idle, contentDescription = null) },
                            label = { Text(tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                nav,
                startDestination = Tab.Networks.graph,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                enterTransition = { enter() },
                exitTransition = { exit() },
                popEnterTransition = { popEnter() },
                popExitTransition = { popExit() },
            ) {
                navigation(startDestination = Tab.Networks.start, route = Tab.Networks.graph) {
                    composable(Tab.Networks.start) { NetworksScreen(model, navigator) }
                    composable("network/{id}") { NetworkDetail(model, navigator, it.mac("id"), entryId = null) }
                    composable("client/{mac}") { ClientDetail(model, navigator, it.mac("mac"), entryId = null) }
                    composable("channels") { ChannelsScreen(model, navigator) }
                }
                navigation(startDestination = Tab.Lan.start, route = Tab.Lan.graph) {
                    composable(Tab.Lan.start) { LanScreen(model, navigator) }
                    composable("host/{ip}") { LanDetail(model, navigator, it.text("ip"), entryId = null) }
                    composable("connection") { LinkScreen(model, navigator) }
                }
                navigation(startDestination = Tab.Bluetooth.start, route = Tab.Bluetooth.graph) {
                    composable(Tab.Bluetooth.start) { BluetoothScreen(model, navigator) }
                    composable("ble/{address}") { BleDetail(model, navigator, it.text("address"), entryId = null) }
                }
                navigation(startDestination = Tab.History.start, route = Tab.History.graph) {
                    composable(Tab.History.start) { HistoryScreen(model, navigator) }
                    composable("history/{id}") { HistoryDetail(model, navigator, it.text("id")) }
                    composable("saved/network/{entry}/{id}") { NetworkDetail(model, navigator, it.mac("id"), it.text("entry")) }
                    composable("saved/client/{entry}/{mac}") { ClientDetail(model, navigator, it.mac("mac"), it.text("entry")) }
                    composable("saved/host/{entry}/{ip}") { LanDetail(model, navigator, it.text("ip"), it.text("entry")) }
                    composable("saved/ble/{entry}/{address}") { BleDetail(model, navigator, it.text("address"), it.text("entry")) }
                    composable("saved/connection/{entry}/{at}") {
                        SavedLinkScreen(model, navigator, it.text("entry"), it.arguments?.getString("at")?.toLongOrNull() ?: 0L)
                    }
                }
                navigation(startDestination = Tab.Sensor.start, route = Tab.Sensor.graph) {
                    composable(Tab.Sensor.start) {
                        SensorScreen(model, onFlash = { nav.navigate("flash") }, onDecodePcap = { nav.navigate("pcap") })
                    }
                    composable("flash") { FlashScreen(model) { nav.popBackStack() } }
                    composable("pcap") { PcapScreen { nav.popBackStack() } }
                }
            }
        }
    }
}

private fun NavBackStackEntry.text(key: String): String = Uri.decode(arguments?.getString(key).orEmpty())
private fun NavBackStackEntry.mac(key: String): MacAddress? = MacAddress.parse(text(key).replace('-', ':'))

internal fun MacAddress.routeId(): String = text.replace(':', '-')

private fun openTab(nav: NavHostController, tab: Tab) {
    val already = nav.currentBackStackEntry?.destination?.hierarchy?.any { it.route == tab.graph } == true
    if (already) {
        // Tapping the open tab goes back to its first screen.
        nav.popBackStack(tab.start, inclusive = false)
        return
    }
    nav.navigate(tab.graph) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private val tabStarts = Tab.entries.map { it.start }.toSet()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in tabStarts && targetState.destination.route in tabStarts

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (isTabSwitch()) fadeIn(tween(180)) else slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(280)) { -it / 8 } + fadeOut(tween(200))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (isTabSwitch()) fadeIn(tween(180)) else slideInHorizontally(tween(280)) { -it / 8 } + fadeIn(tween(220))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(200))
