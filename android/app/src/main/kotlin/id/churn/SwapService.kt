package id.churn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager

/**
 * Keeps the app running while a swap step, or another job that takes minutes, talks to the router, also with the
 * screen off, and the phone awake.
 */
class SwapService : Service() {

    /**
     * Keeps the phone from sleeping while a step runs with the screen off, when a stalled read could outlast its
     * command's timeout. Not counted, so that each step's acquire starts the backstop anew and one release ends it.
     */
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = getString(R.string.notification_channel)
        val channel = NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val activity = Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, activity, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
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
        if (controller.stepRunning) {
            wakeLock.acquire(WAKE_LOCK_MILLIS)
            controller.onStepEnd = { stopSelf() }
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as ChurnApp).controller.onStepEnd = null
        wakeLock.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "swap"
        const val ID = 1
        const val WAKE_LOCK_TAG = "churn:step"

        /**
         * The wake lock's backstop, in case nothing stops the service when a step ends: an hour, longer than step 3
         * takes with a first clearing and its question, or the removal test.
         */
        const val WAKE_LOCK_MILLIS = 60 * 60 * 1000L
    }
}
