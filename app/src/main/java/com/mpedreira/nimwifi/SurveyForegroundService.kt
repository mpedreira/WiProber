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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the process in the foreground while a Continuous capture or active tests run, so they
 * continue with the screen off: Android keeps scanning at foreground rates, does not kill the
 * process, and the CPU (partial wake lock) and Wi-Fi radio (Wi-Fi lock) stay awake. It does no
 * work itself: the capture session and the test run keep running where they are.
 *
 * Several owners may need it at once ([REASON_TRACK], [REASON_TESTS]): it runs while any of them
 * holds it and stops, releasing both locks, when the last one releases it. [problem] says why it
 * could not start, so the screen can show it.
 */
class SurveyForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projectId = intent?.getStringExtra(EXTRA_PROJECT_ID)
        try {
            startForeground(NOTIFICATION_ID, notification(projectId), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            _problem.value = null
        } catch (e: RuntimeException) {
            // Missing location permission or a background start: the survey still runs, without the service.
            Log.w(TAG, "Cannot start the foreground service", e)
            _problem.value = e.javaClass.simpleName
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
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

    private fun notification(projectId: String?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.survey_service_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // Back to the open project and its session (the existing survey screen, not a new one).
        val target = if (projectId != null) {
            Intent(this, SurveyActivity::class.java)
                .putExtra(SurveyActivity.EXTRA_PROJECT_ID, projectId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
        val open = target?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val reasons = synchronized(holders) { holders.toSet() }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_route_start)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (REASON_TRACK in reasons) R.string.survey_service_track else R.string.survey_service_tests))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val REASON_TRACK = "track"
        const val REASON_TESTS = "tests"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val CHANNEL_ID = "survey"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SurveyService"
        /** Safety limit: a forgotten track never keeps the phone awake for longer. */
        private const val MAX_LOCK_MILLIS = 4L * 60 * 60 * 1000

        private val holders = mutableSetOf<String>()
        private val _problem = MutableStateFlow<String?>(null)

        /** Why the service could not start (null while it runs or is not needed). */
        val problem: StateFlow<String?> = _problem

        /** [reason] needs the service; returns why it could not start, or null. */
        fun acquire(context: Context, reason: String, projectId: String?): String? {
            synchronized(holders) { holders += reason }
            return try {
                context.startForegroundService(
                    Intent(context, SurveyForegroundService::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
                )
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot start the foreground service", e) // e.g. a start from the background
                (e.javaClass.simpleName).also { _problem.value = it }
            }
        }

        /** [reason] no longer needs it; the service stops when nobody does. */
        fun release(context: Context, reason: String) {
            val empty = synchronized(holders) { holders -= reason; holders.isEmpty() }
            if (empty) {
                runCatching { context.stopService(Intent(context, SurveyForegroundService::class.java)) }
                _problem.value = null
            }
        }
    }
}
