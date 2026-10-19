package cattivole.spectra

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import cattivole.spectra.platform.BleGattClient
import cattivole.spectra.platform.BleRadio
import cattivole.spectra.platform.DeviceBleScanner
import cattivole.spectra.platform.LanScanner
import cattivole.spectra.platform.LinkProbe
import cattivole.spectra.platform.PhoneWifiScanner
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
    val lan = LanScanner(app)
    val connection = LinkProbe(app, lan)
    val usb = UsbEspFlasher(app)

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

    /** BSSIDs and Bluetooth addresses the user is watching for. */
    val watchedIds: Set<String>
        get() = prefs.getStringSet(WATCHED, emptySet()) ?: emptySet()

    fun isWatched(id: String): Boolean = watchedIds.contains(id)

    fun toggleWatch(id: String) {
        val next = watchedIds.toMutableSet()
        if (!next.add(id)) next.remove(id)
        prefs.edit().putStringSet(WATCHED, next).apply()
        generation += 1
    }

    private var lastWifiScan = 0
    private var lastLanScan: Long? = null
    private var savedCheckAt = 0L

    init {
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
        connection.observe {
            bump()
            saveFinishedConnection()
        }
        usb.observe(bump)
        radio.onAdapterChange { ble.availabilityChanged() }
        phone.start()
    }

    /**
     * Folds whatever each source has found into its History session. Each
     * upsert is silent and no-ops when there is nothing to save, so it is safe
     * to call often.
     */
    fun autosave() {
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
        if (report.checkedAt == savedCheckAt) return
        val saved = SavedConnection.from(report, connection.viaVpn) ?: return
        savedCheckAt = report.checkedAt
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
        }
        if (finished) autosave()
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
        gatt.disconnect()
        ble.shutdown()
        lan.stop()
        connection.stop()
        link.close()
        usb.close()
        radio.stop()
    }

    private companion object {
        /** English key; the UI translates it through `sourceName`, like iOS. */
        const val PHONE_SOURCE = "This phone"
        const val AUTOSAVE_HISTORY = "autosaveHistory"
        const val WATCHED = "watchedIds"
    }
}
