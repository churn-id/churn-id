package id.churn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Keeps the app running while a swap step, or another job that takes minutes, talks to the router, also with the
 * screen off.
 */
class SwapService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = getString(R.string.notification_channel)
        val channel = NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val activity = Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, activity, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, notification)
        }
        // The service stops itself, and only now: Android ends an app whose service stops before startForeground.
        val controller = (application as ChurnApp).controller
        if (controller.stepRunning) controller.onStepEnd = { stopSelf() } else stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as ChurnApp).controller.onStepEnd = null
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "swap"
        const val ID = 1
    }
}
