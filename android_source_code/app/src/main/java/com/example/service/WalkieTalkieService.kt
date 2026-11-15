package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.repository.P2PRepository

/**
 * Keeps the local mesh alive when the app is backgrounded. This mirrors the
 * APK's foreground service, wake lock and high-performance Wi-Fi lock.
 */
class WalkieTalkieService : Service() {
    companion object {
        private const val TAG = "WalkieTalkieService"
        private const val CHANNEL_ID = "WalkieTalkieServiceChannel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var repository: P2PRepository? = null
            private set

        fun attachRepository(repo: P2PRepository) {
            repository = repo
            repo.startP2PServices()
        }

        fun start(context: Context) {
            val intent = Intent(context, WalkieTalkieService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        acquireLocks()
        repository?.startP2PServices()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        repository?.startP2PServices()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        repository?.stopP2PServices()
        releaseLocks()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MeshTalk")
            .setContentText("Walkie Talkie Active — LAN mesh is listening")
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Walkie-Talkie Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Local Wi-Fi mesh listener and push-to-talk service"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WalkieTalkieApp::ServiceWakeLock"
            ).apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed", e)
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "WalkieTalkieApp::ServiceWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock acquire failed", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }
}
