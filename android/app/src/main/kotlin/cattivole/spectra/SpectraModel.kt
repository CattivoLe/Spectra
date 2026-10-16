package cattivole.spectra

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import cattivole.spectra.platform.BleRadio
import cattivole.spectra.platform.DeviceBleScanner
import cattivole.spectra.platform.LanScanner
import cattivole.spectra.platform.PhoneWifiScanner
import cattivole.spectra.platform.SensorLink
import cattivole.spectra.core.NetworkStore

class SpectraModel(app: Application) : AndroidViewModel(app) {
    val store = NetworkStore()
    val radio = BleRadio(app)
    val link = SensorLink(app, store, radio)
    val phone = PhoneWifiScanner(app, store)
    val ble = DeviceBleScanner(app, radio, link)
    val lan = LanScanner(app)

    var generation by mutableIntStateOf(0)
        private set

    init {
        val bump = { generation += 1 }
        store.observe(bump)
        link.observe(bump)
        phone.observe(bump)
        ble.observe(bump)
        lan.observe(bump)
        phone.start()
    }

    fun onForeground() {
        link.refreshAvailability(connectSaved = true)
        ble.resumeIfWanted()
        phone.refresh()
    }

    fun onBackground() {
        ble.suspend()
    }

    override fun onCleared() {
        phone.stop()
        ble.shutdown()
        lan.stop()
        link.close()
        radio.stop()
    }
}
