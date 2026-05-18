package com.example.navipilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * MediaProjection 前台服务
 *
 * Android 14+ 要求 MediaProjection 必须在 foreground service 中运行，
 * 且 foregroundServiceType 必须为 mediaProjection，否则 getMediaProjection()
 * 会抛出 SecurityException。
 *
 * 启动顺序（严格）：
 *   1. createScreenCaptureIntent() → 用户授权
 *   2. startForegroundService(intent) + startForeground(id, notification, type)
 *   3. getMediaProjection() → createVirtualDisplay()
 */
class AmapProjectionService : Service() {

    companion object {
        private const val TAG = "AmapProjectionSvc"
        private const val CHANNEL_ID = "amap_projection_channel"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 14+ 必须指定 foregroundServiceType = mediaProjection
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.i(TAG, "✅ AmapProjectionService 已启动 (foreground)")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "🧹 AmapProjectionService 已销毁")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "高德地图投射",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("地图显示中")
            .setContentText("正在投射高德地图导航画面")
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setOngoing(true)
            .build()
}
