package com.namhyun.reflect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.namhyun.reflect.MainActivity
import com.namhyun.reflect.R

/**
 * 백그라운드에서 NotificationListener 가 살아있도록 보장하는 ForegroundService.
 *
 * NotificationListenerService 는 보통 시스템이 안정적으로 유지하지만, 일부 OEM (삼성, 샤오미 등)
 * 의 적극적 절전 정책에서 강제 종료될 수 있어, 별도 ForegroundService 로 "활성 상태" 신호를 유지.
 *
 * 알림은 IMPORTANCE_MIN 으로 표시 (소리/진동 없음, 알림 영역 하단에 minimal).
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        runCatching { ensureChannel() }
        runCatching { startInForeground() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startInForeground() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Reflect")
            .setContentText("메시지 답장 준비 중")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Reflect 동작 중",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "백그라운드 답장 추천 서비스 유지"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        const val NOTIF_ID = 9001
        const val CHANNEL_ID = "reflect_keepalive"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
