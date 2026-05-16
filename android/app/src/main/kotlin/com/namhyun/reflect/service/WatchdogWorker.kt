package com.namhyun.reflect.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.namhyun.reflect.MainActivity
import com.namhyun.reflect.Permissions
import com.namhyun.reflect.R
import java.util.concurrent.TimeUnit

/**
 * 30분마다:
 *  1. KeepAliveService 가 살아있는지 확인 + 죽었으면 재시작.
 *  2. 핵심 권한(알림 접근 / 접근성)이 OS·업데이트로 몰래 꺼졌는지 감지.
 *     꺼졌으면 "Reflect 가 멈췄어요" 알림 1회 — 안 그러면 사용자는
 *     앱이 조용히 죽은 줄 모르고 고장 났다고 생각함. 복구되면 알림 자동 해제.
 *
 * 알림 스팸 방지: 직전 상태를 SharedPreferences 에 기억해 OK→상실 전이에만 알림.
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            val notifEnabled = Permissions.isNotificationListenerEnabled(ctx)
            val a11yEnabled = runCatching { Permissions.isAccessibilityEnabled(ctx) }.getOrDefault(true)

            if (notifEnabled) {
                KeepAliveService.start(ctx)
                Log.i(TAG, "watchdog ping — keepalive started")
            }

            // 핵심 권한 상실 감지. 알림 접근이 꺼지면 메시지 자체를 못 받음(치명).
            // 접근성이 꺼지면 오버레이·자가학습이 멈춤(중요).
            val missing = buildList {
                if (!notifEnabled) add("알림 접근")
                if (!a11yEnabled) add("접근성")
            }
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wasHealthy = prefs.getBoolean(KEY_HEALTHY, true)

            if (missing.isEmpty()) {
                if (!wasHealthy) {
                    cancelAlert(ctx)
                    prefs.edit().putBoolean(KEY_HEALTHY, true).apply()
                }
            } else {
                // OK→상실 전이일 때만 알림(매 30분 스팸 방지).
                if (wasHealthy) {
                    notifyDegraded(ctx, missing)
                }
                prefs.edit().putBoolean(KEY_HEALTHY, false).apply()
            }
            Result.success()
        } catch (e: Throwable) {
            Log.w(TAG, "watchdog failed", e)
            Result.success() // 실패해도 재시도 안 함 (다음 주기에 다시)
        }
    }

    private fun notifyDegraded(ctx: Context, missing: List<String>) {
        runCatching {
            ensureAlertChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val what = missing.joinToString(", ")
            val n = NotificationCompat.Builder(ctx, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Reflect 가 멈췄어요")
                .setContentText("$what 권한이 꺼졌어요 — 탭해서 다시 켜주세요")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$what 권한이 꺼져서 답장 추천이 동작하지 않아요. " +
                            "탭하면 Reflect 가 열려요. 해당 권한을 다시 켜면 자동 복구됩니다."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(open)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(ALERT_ID, n)
            Log.i(TAG, "degraded alert posted: missing=$missing")
        }
    }

    private fun cancelAlert(ctx: Context) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(ALERT_ID)
            Log.i(TAG, "permissions restored — alert cleared")
        }
    }

    private fun ensureAlertChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ALERT_CHANNEL) != null) return
        val ch = NotificationChannel(
            ALERT_CHANNEL,
            "Reflect 상태 경고",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "권한이 꺼져 동작이 멈췄을 때 알림"
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "Watchdog"
        const val WORK_NAME = "reflect_watchdog"
        private const val PREFS = "reflect_watchdog"
        private const val KEY_HEALTHY = "perms_healthy"
        private const val ALERT_CHANNEL = "reflect_alerts"
        private const val ALERT_ID = 9100

        fun schedule(context: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<WatchdogWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().build())
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            }
        }
    }
}
