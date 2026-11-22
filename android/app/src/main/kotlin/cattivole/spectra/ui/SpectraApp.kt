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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import cattivole.spectra.core.HistoryArea
import cattivole.spectra.core.MacAddress
import cattivole.spectra.platform.Permissions
import kotlinx.coroutines.launch

/**
 * Each tab is its own graph, so it keeps its back stack while another tab is open.
 * There is no shared History tab: each tab with saved scans opens its own history ([area]).
 */
private enum class Tab(
    val graph: String,
    val start: String,
    val label: () -> String,
    val selected: ImageVector,
    val idle: ImageVector,
    val area: HistoryArea? = null,
) {
    Networks("wifi-tab", "networks", { "Wi‑Fi" }, Icons.Filled.Wifi, Icons.Outlined.Wifi, HistoryArea.WIFI),
    Lan("lan-tab", "lan", { "LAN" }, Icons.Filled.Lan, Icons.Outlined.Lan, HistoryArea.LAN),
    // Checks of the way out: connection, link, speed, route and the phone's own traffic.
    Internet("internet-tab", "internet", { tr("Internet") }, Icons.Filled.Public, Icons.Outlined.Public, HistoryArea.INTERNET),
    Bluetooth("bluetooth-tab", "bluetooth", { "Bluetooth" }, Icons.Filled.Bluetooth, Icons.Outlined.Bluetooth, HistoryArea.BLUETOOTH),
    // As on iOS, the sensor is a screen of the Wi‑Fi tab and its place in the bar went to the assistant.
    Assistant("assistant-tab", "assistant", { tr("Assistant") }, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome);

    /** Where this tab shows a summary card: each tab has its own copy of the screen (see [Navigator.card]). */
    val cardRoute: String get() = "$start/card"

    /** This tab's history list; a session opens under it at `historyRoute/{id}`. */
    val historyRoute: String get() = "$start/history"

    /** The licenses, opened from this tab's history menu. */
    val licensesRoute: String get() = "$start/licenses"

    companion object {
        fun of(area: HistoryArea): Tab = entries.first { it.area == area }
    }
}

/** Where a screen goes next. One object so screens do not each take five lambdas. */
class Navigator(private val nav: NavHostController) : cattivole.spectra.assistant.ui.AssistantNav {
    override fun back() {
        nav.popBackStack()
    }

    override fun chat(id: java.util.UUID) = nav.navigate("assistant/chat/$id")

    /** A summary card opens in the tab it was asked from, so going back returns to that screen. */
    override fun card(id: java.util.UUID) {
        val hierarchy = nav.currentBackStackEntry?.destination?.hierarchy?.mapNotNull { it.route }?.toList().orEmpty()
        val tab = Tab.entries.firstOrNull { it.graph in hierarchy } ?: Tab.Assistant
        nav.navigate("${tab.cardRoute}/$id")
    }

    override fun models() = nav.navigate("assistant/models")

    override fun internet() = nav.navigate("assistant/internet")


    /** A ✨ button: a summary card about what this screen shows — the findings at once, the model's words under them. */
    fun summary(model: cattivole.spectra.SpectraModel, subject: cattivole.spectra.assistant.CardSubject) {
        if (!cattivole.spectra.assistant.AssistantModel.isSupported) return
        kotlinx.coroutines.MainScope().launch { model.assistant.cards.open(subject)?.let(::card) }
    }

    fun network(id: MacAddress, entry: String? = null) =
        nav.navigate(if (entry == null) "network/${id.routeId()}" else "saved/network/${Uri.encode(entry)}/${id.routeId()}")

    fun client(mac: MacAddress, entry: String? = null) =
        nav.navigate(if (entry == null) "client/${mac.routeId()}" else "saved/client/${Uri.encode(entry)}/${mac.routeId()}")

    fun host(ip: String, entry: String? = null) =
        nav.navigate(if (entry == null) "host/${Uri.encode(ip)}" else "saved/host/${Uri.encode(entry)}/${Uri.encode(ip)}")

    fun connection() = nav.navigate("connection")

    fun linkCheck() = nav.navigate("link-check")

    fun speedTest() = nav.navigate("speed-test")

    fun traceRoute() = nav.navigate("trace-route")

    fun traffic() = nav.navigate("traffic")

    fun savedTraffic(id: String) = nav.navigate("traffic/saved/${Uri.encode(id)}")

    fun savedTrafficList() = nav.navigate("traffic/sessions")

    fun trafficFlow(id: Long, exchange: Int? = null) =
        nav.navigate(if (exchange == null) "traffic/flow/$id" else "traffic/flow/$id/e/$exchange")

    fun savedTrafficFlow(session: String, id: Long, exchange: Int? = null) =
        nav.navigate(
            if (exchange == null) "traffic/saved/${Uri.encode(session)}/flow/$id"
            else "traffic/saved/${Uri.encode(session)}/flow/$id/e/$exchange",
        )

    fun trafficJson(id: Long, exchange: Int, session: String? = null) =
        nav.navigate(
            if (session == null) "traffic/flow/$id/e/$exchange/json"
            else "traffic/saved/${Uri.encode(session)}/flow/$id/e/$exchange/json",
        )

    fun savedConnection(entry: String, checkedAt: Long) =
        nav.navigate("saved/connection/${Uri.encode(entry)}/$checkedAt")

    fun ble(address: String, entry: String? = null) =
        nav.navigate(if (entry == null) "ble/${Uri.encode(address)}" else "saved/ble/${Uri.encode(entry)}/${Uri.encode(address)}")

    fun channels() = nav.navigate("channels")
    fun savedNetworks() = nav.navigate("saved-networks")
    fun pcap() = nav.navigate("pcap")

    /** Decodes the sensor's last raw capture, shown under [name]. */
    fun decodeCapture(name: String) = nav.navigate("capture-pcap/${Uri.encode(name)}")
    /** The saved sessions of [area]'s tab. */
    fun history(area: HistoryArea) = nav.navigate(Tab.of(area).historyRoute)

    /** One saved session, as [area]'s tab shows it. */
    fun history(area: HistoryArea, id: String) = nav.navigate("${Tab.of(area).historyRoute}/${Uri.encode(id)}")
    fun sensorTab() = nav.navigate("sensor")
    /** The licenses open inside the tab whose history menu asked for them. */
    fun licenses() = nav.navigate(currentTab().licensesRoute)
    fun license(index: Int) = nav.navigate("${currentTab().licensesRoute}/$index")

    private fun currentTab(): Tab {
        val hierarchy = nav.currentBackStackEntry?.destination?.hierarchy?.mapNotNull { it.route }?.toList().orEmpty()
        return Tab.entries.firstOrNull { it.graph in hierarchy } ?: Tab.Networks
    }
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
                    // One scale for every label, so they stay the same size when the narrow cover screen shrinks them.
                    val labelScale = remember(LocalConfiguration.current.screenWidthDp) { mutableFloatStateOf(1f) }
                    Tab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { openTab(nav, tab) },
                            icon = { Icon(if (selected) tab.selected else tab.idle, contentDescription = null) },
                            label = { TabLabel(tab.label(), labelScale) },
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
                    composable("${Tab.Networks.cardRoute}/{id}") { summaryCard(model, navigator, it) }
                    composable("network/{id}") { NetworkDetail(model, navigator, it.mac("id"), entryId = null) }
                    composable("client/{mac}") { ClientDetail(model, navigator, it.mac("mac"), entryId = null) }
                    historyScreens(model, navigator, Tab.Networks)
                    composable("saved/network/{entry}/{id}") { NetworkDetail(model, navigator, it.mac("id"), it.text("entry")) }
                    composable("saved/client/{entry}/{mac}") { ClientDetail(model, navigator, it.mac("mac"), it.text("entry")) }
                    composable("channels") { ChannelsScreen(model, navigator) }
                    composable("saved-networks") { SavedNetworksScreen(model, navigator) }
                    composable("pcap") {
                        PcapScreen(navigator::back, onAsk = assistantAsk(model, navigator))
                    }
                    composable("sensor") {
                        SensorScreen(model, onBack = navigator::back, onFlash = { nav.navigate("flash") }, onLicenses = navigator::licenses)
                    }
                    composable("flash") { FlashScreen(model) { nav.popBackStack() } }
                    composable("capture-pcap/{name}") {
                        val label = it.text("name")
                        val capture = remember { "$label.pcap" to model.link.rawCapturePcap() }
                        PcapScreen(navigator::back, capture, onSave = { sharePcap(context, capture.second, label) }, onAsk = assistantAsk(model, navigator))
                    }
                }
                navigation(startDestination = Tab.Lan.start, route = Tab.Lan.graph) {
                    composable(Tab.Lan.start) { LanScreen(model, navigator) }
                    composable("${Tab.Lan.cardRoute}/{id}") { summaryCard(model, navigator, it) }
                    composable("host/{ip}") { LanDetail(model, navigator, it.text("ip"), entryId = null) }
                    historyScreens(model, navigator, Tab.Lan)
                    composable("saved/host/{entry}/{ip}") { LanDetail(model, navigator, it.text("ip"), it.text("entry")) }
                }
                navigation(startDestination = Tab.Internet.start, route = Tab.Internet.graph) {
                    composable(Tab.Internet.start) { InternetScreen(model, navigator) }
                    composable("${Tab.Internet.cardRoute}/{id}") { summaryCard(model, navigator, it) }
                    historyScreens(model, navigator, Tab.Internet)
                    composable("saved/connection/{entry}/{at}") {
                        SavedLinkScreen(model, navigator, it.text("entry"), it.arguments?.getString("at")?.toLongOrNull() ?: 0L)
                    }
                    composable("connection") { LinkScreen(model, navigator) }
                    composable("link-check") { LinkCheckScreen(model, navigator) }
                    composable("speed-test") { SpeedTestScreen(model, navigator) }
                    composable("trace-route") { TraceScreen(model, navigator) }
                    composable("traffic") { SnifferScreen(model, navigator, sessionId = null) }
                    composable("traffic/sessions") { SavedTrafficScreen(model, navigator) }
                    composable("traffic/saved/{id}") { SnifferScreen(model, navigator, sessionId = it.text("id")) }
                    composable("traffic/flow/{id}/e/{exchange}/json") {
                        TrafficJsonScreen(
                            model, navigator,
                            it.arguments?.getString("id")?.toLongOrNull() ?: -1L,
                            it.arguments?.getString("exchange")?.toIntOrNull() ?: 0,
                            sessionId = null,
                        )
                    }
                    composable("traffic/saved/{id}/flow/{flow}/e/{exchange}/json") {
                        TrafficJsonScreen(
                            model, navigator,
                            it.arguments?.getString("flow")?.toLongOrNull() ?: -1L,
                            it.arguments?.getString("exchange")?.toIntOrNull() ?: 0,
                            sessionId = it.text("id"),
                        )
                    }
                    composable("traffic/flow/{id}/e/{exchange}") {
                        TrafficFlowScreen(
                            model, navigator,
                            it.arguments?.getString("id")?.toLongOrNull() ?: -1L,
                            sessionId = null,
                            exchange = it.arguments?.getString("exchange")?.toIntOrNull(),
                        )
                    }
                    composable("traffic/saved/{id}/flow/{flow}/e/{exchange}") {
                        TrafficFlowScreen(
                            model, navigator,
                            it.arguments?.getString("flow")?.toLongOrNull() ?: -1L,
                            sessionId = it.text("id"),
                            exchange = it.arguments?.getString("exchange")?.toIntOrNull(),
                        )
                    }
                    composable("traffic/flow/{id}") {
                        TrafficFlowScreen(model, navigator, it.arguments?.getString("id")?.toLongOrNull() ?: -1L, sessionId = null)
                    }
                    composable("traffic/saved/{id}/flow/{flow}") {
                        TrafficFlowScreen(
                            model,
                            navigator,
                            it.arguments?.getString("flow")?.toLongOrNull() ?: -1L,
                            sessionId = it.text("id"),
                        )
                    }
                }
                navigation(startDestination = Tab.Bluetooth.start, route = Tab.Bluetooth.graph) {
                    composable(Tab.Bluetooth.start) { BluetoothScreen(model, navigator) }
                    composable("${Tab.Bluetooth.cardRoute}/{id}") { summaryCard(model, navigator, it) }
                    composable("ble/{address}") { BleDetail(model, navigator, it.text("address"), entryId = null) }
                    historyScreens(model, navigator, Tab.Bluetooth)
                    composable("saved/ble/{entry}/{address}") { BleDetail(model, navigator, it.text("address"), it.text("entry")) }
                }
                navigation(startDestination = Tab.Assistant.start, route = Tab.Assistant.graph) {
                    composable(Tab.Assistant.start) { cattivole.spectra.assistant.ui.AssistantHomeScreen(model, navigator) }
                    composable("${Tab.Assistant.cardRoute}/{id}") { summaryCard(model, navigator, it) }
                    composable("assistant/chat/{id}") {
                        val id = runCatching { java.util.UUID.fromString(it.text("id")) }.getOrNull()
                        if (id != null) cattivole.spectra.assistant.ui.AssistantChatScreen(model, navigator, id)
                    }
                    composable("assistant/models") { cattivole.spectra.assistant.ui.AssistantModelsScreen(model, navigator) }
                    composable("assistant/internet") { cattivole.spectra.assistant.ui.AssistantInternetScreen(model, navigator) }
                }
            }
        }
    }
}

/**
 * A tab's name on one line. A name too wide for its slot shrinks the shared [scale] a step at a time,
 * down to [MIN_LABEL_SCALE]; past that it ends in an ellipsis. It is drawn only once it fits.
 */
@Composable
private fun TabLabel(text: String, scale: MutableFloatState) {
    val style = LocalTextStyle.current
    var fits by remember(text) { mutableStateOf(false) }
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = style.copy(fontSize = style.fontSize * scale.floatValue),
        modifier = Modifier.drawWithContent { if (fits) drawContent() },
        onTextLayout = { layout ->
            val over = layout.hasVisualOverflow
            if (over && scale.floatValue > MIN_LABEL_SCALE) {
                scale.floatValue = (scale.floatValue - 0.05f).coerceAtLeast(MIN_LABEL_SCALE)
            } else {
                fits = true
            }
        },
    )
}

private const val MIN_LABEL_SCALE = 0.75f

/** The decode screen's ✨: the capture is kept for the assistant, then a summary card about it opens. */
private fun assistantAsk(model: SpectraModel, navigator: Navigator): ((String, cattivole.spectra.core.CaptureAnalysis, Boolean) -> Unit)? =
    if (!cattivole.spectra.assistant.AssistantModel.isSupported) null else { name, analysis, fromSensor ->
        model.captures.keep(name, analysis, fromSensor)
        navigator.summary(model, cattivole.spectra.assistant.CardSubject.Capture)
    }

/** A tab's own history: the list of its sessions and one session, inside that tab's graph. */
private fun androidx.navigation.NavGraphBuilder.historyScreens(model: SpectraModel, navigator: Navigator, tab: Tab) {
    val area = tab.area ?: return
    composable(tab.historyRoute) { HistoryScreen(model, navigator, area) }
    composable("${tab.historyRoute}/{id}") { HistoryDetail(model, navigator, it.text("id"), area) }
    composable(tab.licensesRoute) { LicensesScreen(navigator) }
    composable("${tab.licensesRoute}/{index}") { LicenseDetail(navigator, it.arguments?.getString("index")?.toIntOrNull() ?: -1) }
}

@Composable
private fun summaryCard(model: SpectraModel, navigator: Navigator, entry: NavBackStackEntry) {
    val id = runCatching { java.util.UUID.fromString(entry.text("id")) }.getOrNull() ?: return
    cattivole.spectra.assistant.ui.AssistantCardScreen(model, navigator, id)
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
