package com.mpedreira.nimwifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the process in the foreground while a Continuous track or active tests are running, so
 * they continue with the screen off: Android keeps scanning at foreground rates, does not kill the
 * process, and the CPU (partial wake lock) and Wi-Fi radio (Wi-Fi lock) stay awake. It does no
 * work itself; the survey logic keeps running where it is.
 */
class SurveyForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: REASON_TRACK
        try {
            startForeground(NOTIFICATION_ID, notification(reason), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // Missing location permission or a background start: the survey still runs, without the service.
            Log.w(TAG, "Cannot start the foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nimWifi:survey")
                ?.apply { setReferenceCounted(false); acquire(MAX_LOCK_MILLIS) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION") // the only Wi-Fi lock mode that also works with the screen off
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nimWifi:survey")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun notification(reason: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.survey_service_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_route_start)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (reason == REASON_TESTS) R.string.survey_service_tests else R.string.survey_service_track))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val REASON_TRACK = "track"
        const val REASON_TESTS = "tests"
        private const val EXTRA_REASON = "reason"
        private const val CHANNEL_ID = "survey"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SurveyService"
        /** Safety limit: a forgotten track never keeps the phone awake for longer. */
        private const val MAX_LOCK_MILLIS = 4L * 60 * 60 * 1000

        /** Starts or updates the service for [reason], or stops it when [reason] is null. */
        fun update(context: Context, reason: String?) {
            val intent = Intent(context, SurveyForegroundService::class.java)
            try {
                if (reason == null) {
                    context.stopService(intent)
                } else {
                    context.startForegroundService(intent.putExtra(EXTRA_REASON, reason))
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot update the foreground service", e) // e.g. a start from the background
            }
        }
    }
}
