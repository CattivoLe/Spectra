package cattivole.spectra

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cattivole.spectra.platform.BleGattClient
import cattivole.spectra.platform.BleRadio
import cattivole.spectra.platform.DeviceBleScanner
import cattivole.spectra.platform.LanScanner
import cattivole.spectra.platform.LinkProbe
import cattivole.spectra.platform.PhoneWifiScanner
import cattivole.spectra.platform.TrafficSniffer
import cattivole.spectra.platform.WifiJoiner
import cattivole.spectra.platform.SensorLink
import cattivole.spectra.platform.UsbEspFlasher
import cattivole.spectra.core.HistoryStore
import cattivole.spectra.core.LinkStatus
import cattivole.spectra.core.NetworkStore
import cattivole.spectra.core.SavedConnection

class SpectraModel(app: Application) : AndroidViewModel(app) {
    val store = NetworkStore()
    val history = HistoryStore(app)
    val radio = BleRadio(app)
    val link = SensorLink(app, store, radio, history)
    val phone = PhoneWifiScanner(app, store)
    val ble = DeviceBleScanner(app, radio, link)
    val gatt = BleGattClient(app)
    private val hostStore = object : cattivole.spectra.core.TextStore {
        private val prefs = app.getSharedPreferences("spectra-hosts", Context.MODE_PRIVATE)
        override fun read(key: String): String? = prefs.getString(key, null)
        override fun write(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }
    val lan = LanScanner(app, cattivole.spectra.core.KnownHosts(hostStore), cattivole.spectra.core.DeviceLabels(hostStore))
    val traffic = TrafficSniffer(app)
    val connection = LinkProbe(app, lan)
    val speed = cattivole.spectra.platform.SpeedTester(app) { connection.currentNetwork() }
    val usb = UsbEspFlasher(app)
    val wifiJoiner = WifiJoiner(app)

    /** The capture last decoded and the traffic last handed over, for the assistant to talk about. */
    val captures = cattivole.spectra.assistant.CaptureShelf()
    /** Notes and per-device baselines the assistant keeps between chats. */
    val assistantMemory = cattivole.spectra.assistant.AssistantMemory(java.io.File(app.filesDir, "assistant/memory.json"))
    val assistantSources = cattivole.spectra.assistant.AssistantSources(this, captures, assistantMemory)

    /** The on-device assistant. Created the first time anything needs it: its tab or a ✨ button. */
    val assistant: cattivole.spectra.assistant.AssistantModel by lazy {
        cattivole.spectra.assistant.AssistantModel(app, viewModelScope, assistantSources).also { model ->
            model.onDownloadsActive = { active ->
                val intent = android.content.Intent(app, cattivole.spectra.platform.AssistantDownloadService::class.java)
                if (active) androidx.core.content.ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
            }
        }
    }

    var generation by mutableIntStateOf(0)
        private set

    private val prefs = app.getSharedPreferences("spectra", Context.MODE_PRIVATE)

    /** When on, finished Wi‑Fi/LAN/Bluetooth scans are kept in History on their own. */
    var autosaveHistory: Boolean
        get() = prefs.getBoolean(AUTOSAVE_HISTORY, true)
        set(value) {
            prefs.edit().putBoolean(AUTOSAVE_HISTORY, value).apply()
            generation += 1
        }

    /** Hosts marked Trusted leave the LAN list unless a search is on. */
    var hideTrustedHosts: Boolean
        get() = prefs.getBoolean(HIDE_TRUSTED, false)
        set(value) {
            prefs.edit().putBoolean(HIDE_TRUSTED, value).apply()
            generation += 1
        }

    /** A finished LAN scan that found hosts new to the network posts a notification. */
    var notifyNewDevices: Boolean
        get() = prefs.getBoolean(NOTIFY_NEW, true)
        set(value) {
            prefs.edit().putBoolean(NOTIFY_NEW, value).apply()
            generation += 1
        }

    /** BSSIDs and Bluetooth addresses the user is watching for. */
    val watchedIds: Set<String>
        get() = prefs.getStringSet(WATCHED, emptySet()) ?: emptySet()

    fun isWatched(id: String): Boolean = watchedIds.contains(id)

    fun toggleWatch(id: String) {
        val next = watchedIds.toMutableSet()
        if (!next.add(id)) next.remove(id)
        prefs.edit().putStringSet(WATCHED, next).apply()
        link.setWatched(next)
        generation += 1
    }

    private var lastWifiScan = 0
    private var lastLanScan: Long? = null
    private var savedCheckAt = 0L
    private var savedRevision = 0

    init {
        link.setWatched(watchedIds)
        val bump = {
            generation += 1
            autosaveOnScanFinish()
        }
        store.observe(bump)
        history.observe(bump)
        link.observe(bump)
        phone.observe(bump)
        ble.observe(bump)
        gatt.observe(bump)
        lan.observe(bump)
        traffic.observe(bump)
        connection.observe {
            bump()
            saveFinishedConnection()
        }
        usb.observe(bump)
        speed.observe { generation += 1 }
        radio.onAdapterChange { ble.availabilityChanged() }
        // Rows that leave the live lists stay in History.
        store.onEvict = { gone -> if (autosaveHistory) history.upsertNetworks(gone, PHONE_SOURCE) }
        ble.onEvict = { gone -> if (autosaveHistory) history.upsertBluetooth(gone, PHONE_SOURCE) }
        ble.keep = { gatt.deviceAddress }
        wifiJoiner.onJoined = { ssid, password -> link.rememberJoined(ssid, password) }
        wifiJoiner.start()
        phone.start()
        // Without its ✨ button the assistant still reads the Traffic screen: the live list, or the newest saved session.
        captures.recordedTraffic = {
            val flows = traffic.flows
            if (flows.isNotEmpty()) {
                cattivole.spectra.assistant.CaptureShelf.Recording(
                    if (traffic.running) "recording in progress" else "current recording",
                    flows,
                    flows.maxOf { it.lastAt },
                )
            } else {
                traffic.saved.firstOrNull()?.let { session ->
                    cattivole.spectra.assistant.CaptureShelf.Recording("saved session of ${cattivole.spectra.ui.dateTime(session.stoppedAt)}", session.flows, session.stoppedAt)
                }
            }
        }
    }

    /**
     * Folds whatever each source has found into its History session. Each
     * upsert is silent and no-ops when there is nothing to save, so it is safe
     * to call often.
     */
    fun autosave() {
        // The same tick teaches the assistant what is usual for each device, so "is this normal for it?" has an answer.
        runCatching { assistantSources.recordBaselines() }
        if (!autosaveHistory) return
        history.upsertNetworks(store, PHONE_SOURCE)
        history.upsertLan(lan.devices, lan.networkKey, lan.networkLabel, PHONE_SOURCE)
        history.upsertBluetooth(ble.devices.values, PHONE_SOURCE)
    }

    /** A finished connection check joins today's LAN session for this network. */
    private fun saveFinishedConnection() {
        if (!autosaveHistory) return
        if (connection.running) return
        val report = connection.report ?: return
        if (report.status == null || report.status == LinkStatus.NO_NETWORK) return
        if (report.checkedAt == savedCheckAt && connection.revision == savedRevision) return
        val saved = SavedConnection.from(report, connection.viaVpn, connection.route == cattivole.spectra.core.ProbeRoute.CELLULAR) ?: return
        savedCheckAt = report.checkedAt
        savedRevision = connection.revision
        history.upsertConnection(saved, lan.networkKey, lan.networkLabel, PHONE_SOURCE)
    }

    /** Enrich the open sessions when a Wi‑Fi or LAN sweep completes. */
    private fun autosaveOnScanFinish() {
        var finished = false
        if (store.scanCount != lastWifiScan) {
            lastWifiScan = store.scanCount
            finished = true
        }
        if (lan.lastScanDate != lastLanScan) {
            lastLanScan = lan.lastScanDate
            finished = true
            announceNewDevices()
        }
        if (finished) autosave()
    }

    private fun announceNewDevices() {
        if (!notifyNewDevices || lan.lastScanDate == null) return
        val fresh = lan.lastVerdict?.newIps.orEmpty().mapNotNull { ip -> lan.devices.firstOrNull { it.ipAddress == ip } }
        cattivole.spectra.platform.NewDeviceNotifier.notify(getApplication(), fresh, lan.networkLabel)
    }

    fun onForeground() {
        link.resumeFromForeground()
        ble.resumeIfWanted()
        if (ble.state is cattivole.spectra.platform.DeviceBleScanner.State.Unavailable) ble.availabilityChanged()
        phone.refresh()
        usb.refresh()
    }

    /** The permission dialog closed, or the user came back from Settings. */
    fun permissionsChanged() {
        link.refreshAvailability(connectSaved = true)
        radio.retry()
        ble.availabilityChanged()
        phone.refresh()
    }

    fun onBackground() {
        autosave()
        ble.suspend()
        link.releaseForBackground()
    }

    override fun onCleared() {
        phone.stop()
        wifiJoiner.stop()
        gatt.disconnect()
        ble.shutdown()
        lan.stop()
        traffic.close()
        connection.stop()
        speed.close()
        link.close()
        usb.close()
        radio.stop()
    }

    private companion object {
        /** English key; the UI translates it through `sourceName`, like iOS. */
        const val PHONE_SOURCE = "This phone"
        const val AUTOSAVE_HISTORY = "autosaveHistory"
        const val WATCHED = "watchedIds"
        const val HIDE_TRUSTED = "hideTrustedHosts"
        const val NOTIFY_NEW = "notifyNewDevices"
    }
}
