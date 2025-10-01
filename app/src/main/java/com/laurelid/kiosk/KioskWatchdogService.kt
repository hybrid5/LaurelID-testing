package com.laurelid.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.laurelid.R
import com.laurelid.ui.ScannerActivity
import com.laurelid.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

class KioskWatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = Runnable { performCheck() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val shouldCheckImmediately = intent?.action == ACTION_CHECK_NOW
        if (shouldCheckImmediately) {
            performCheck()
        } else {
            scheduleNext()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleNext() {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, checkIntervalMs)
    }

    private fun performCheck() {
        if (!isScannerVisible.get()) {
            Logger.i(TAG, "Watchdog restarting scanner activity")
            val launchIntent = Intent(this, ScannerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(launchIntent)
        }
        scheduleNext()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.watchdog_notification_title))
            .setContentText(getString(R.string.watchdog_notification_body))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.watchdog_notification_channel),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val TAG = "KioskWatchdog"
        private const val CHANNEL_ID = "kiosk_watchdog"
        private const val NOTIFICATION_ID = 1001
        internal const val ACTION_CHECK_NOW = "com.laurelid.watchdog.ACTION_CHECK_NOW"
        private const val DEFAULT_CHECK_INTERVAL_MS = 15_000L
        private val isScannerVisible = AtomicBoolean(false)
        @Volatile
        private var checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS

        fun start(context: Context) {
            val intent = Intent(context, KioskWatchdogService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun notifyScannerVisible(visible: Boolean) {
            isScannerVisible.set(visible)
        }

        fun requestImmediateCheck(context: Context) {
            val intent = Intent(context, KioskWatchdogService::class.java).apply {
                action = ACTION_CHECK_NOW
            }
            ContextCompat.startForegroundService(context, intent)
        }

        @VisibleForTesting
        fun setCheckIntervalForTesting(intervalMs: Long) {
            checkIntervalMs = intervalMs.coerceAtLeast(100L)
        }
    }
}
