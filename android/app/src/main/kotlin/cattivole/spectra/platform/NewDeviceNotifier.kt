package cattivole.spectra.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cattivole.spectra.MainActivity
import cattivole.spectra.R
import cattivole.spectra.core.LanDevice
import cattivole.spectra.ui.tr

/** One notice per finished LAN scan, for the hosts that scan marked new. */
object NewDeviceNotifier {
    private const val CHANNEL = "new-devices"
    private const val NOTICE_ID = 4201

    /** Android 13 and later ask the user before any notification is shown. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(context: Context, devices: List<LanDevice>, networkLabel: String?) {
        if (devices.isEmpty() || !allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("New devices on the network"), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val lines = devices.take(4).map { device ->
            if (device.displayName == device.ipAddress) device.ipAddress else "${device.displayName} · ${device.ipAddress}"
        }.toMutableList()
        if (devices.size > 4) lines.add(tr("and %d more", devices.size - 4))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_traffic)
            .setContentTitle(if (devices.size == 1) tr("New device on your network") else tr("%d new devices on your network", devices.size))
            .setContentText(lines.first())
            .setSubText(networkLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTICE_ID, notice)
    }
}
