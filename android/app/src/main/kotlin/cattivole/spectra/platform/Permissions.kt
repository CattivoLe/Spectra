package cattivole.spectra.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    fun missing(context: Context): List<String> {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun canScanBluetooth(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return granted(context, Manifest.permission.BLUETOOTH_SCAN) &&
            granted(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun canScanWifi(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
