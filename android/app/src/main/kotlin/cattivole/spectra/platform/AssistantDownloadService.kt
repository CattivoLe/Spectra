package cattivole.spectra.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import cattivole.spectra.ui.tr

/**
 * Keeps the process alive while an assistant model downloads. It downloads nothing itself (the model library does):
 * without it the system would stop a multi-gigabyte download soon after the app went to the background.
 */
class AssistantDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Model downloads"), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(tr("Downloading the assistant's model"))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "assistant-downloads"
        const val ID = 43
    }
}
