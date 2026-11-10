package com.example.ihateadsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

internal object BlockSettings {
    private const val PREFS = "blocking"
    private const val PACKAGES = "packages"
    private const val WANTED = "wanted"
    private const val ACTIVE = "active"

    fun packages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(PACKAGES, emptySet()).orEmpty().toSet()

    fun savePackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(PACKAGES, packages.toSet()).apply()
    }

    fun wanted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WANTED, false)

    fun setWanted(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(WANTED, value).apply()
    }

    fun active(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ACTIVE, false)

    fun setActive(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ACTIVE, value).apply()
        context.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName))
    }

    const val ACTION_STATE_CHANGED = "com.example.ihateadsapp.STATE_CHANGED"
}

class BlockingVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "네트워크 차단", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BlockSettings.setWanted(this, false)
            stopBlocking()
            stopSelf()
            return Service.START_NOT_STICKY
        }

        val packages = BlockSettings.packages(this)
        if (!BlockSettings.wanted(this) || packages.isEmpty()) {
            stopBlocking()
            stopSelf()
            return Service.START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(packages.size),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        try {
            // A newly established interface replaces the old one. No packets are forwarded.
            val builder = Builder()
                .setSession("IhateAds 차단")
                .addAddress("10.77.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd77:77:77::1", 128)
                .addRoute("::", 0)
            packages.forEach(builder::addAllowedApplication)
            val next = builder.establish() ?: error("VPN 연결을 만들 수 없습니다")
            tunnel?.close()
            tunnel = next
            BlockSettings.setActive(this, true)
        } catch (_: Exception) {
            BlockSettings.setActive(this, false)
            stopBlocking()
            stopSelf()
        }
        return Service.START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep WANTED so opening the app again restores the previous blocking session.
        stopBlocking()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        stopBlocking()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopBlocking()
        super.onDestroy()
    }

    private fun stopBlocking() {
        tunnel?.close()
        tunnel = null
        BlockSettings.setActive(this, false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun notification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("네트워크 차단 중")
            .setContentText("선택한 앱 ${count}개의 연결을 차단하고 있습니다")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.ihateadsapp.STOP"
        private const val CHANNEL = "blocking_vpn"
        private const val NOTIFICATION_ID = 1
    }
}
