package com.scannerbridge.bridge.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.scannerbridge.bridge.R

/**
 * Keeps the JPEG bridge + Wi-Fi alive while the phone streams, even if the
 * screen turns off. Holds a partial wake lock for the session.
 */
class StreamForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "scanner_bridge_stream"
        private const val NOTIF_ID = 4521
        const val EXTRA_URL = "extra_url"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            acquireWakeLock()
        } catch (t: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "ScannerBridge::Stream"
        ).also { it.acquire(3 * 60 * 60 * 1000L /* 3h cap */) }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, "Scanner Bridge Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Active while streaming to the PC" }
            nm.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scanner Bridge active")
            .setContentText("Streaming to PC")
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }
}