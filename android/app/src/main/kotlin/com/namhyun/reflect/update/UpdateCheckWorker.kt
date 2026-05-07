package com.namhyun.reflect.update

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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.namhyun.reflect.BuildConfig
import com.namhyun.reflect.MainActivity
import java.util.concurrent.TimeUnit

/**
 * 1시간마다 새 버전 체크. 발견 시 알림 발송.
 * 알림 탭 → MainActivity 열림 → onCreate/onResume 에서 자동 다운로드+설치 인텐트.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val state = UpdateChecker.check()
        if (state is UpdateState.Available && state.info.latest_version_code > BuildConfig.VERSION_CODE) {
            notify(state.info.latest_version)
        }
        Result.success()
    } catch (e: Throwable) {
        Log.w(TAG, "version check failed", e)
        Result.success()
    }

    private fun notify(version: String) {
        runCatching {
            val ctx = applicationContext
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "업데이트", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
            val tap = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Reflect 새 버전 $version")
                .setContentText("탭하면 업데이트")
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            nm.notify(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "UpdateWorker"
        private const val CHANNEL_ID = "reflect_update"
        private const val NOTIF_ID = 7777
        const val WORK_NAME = "reflect_update_check"

        fun schedule(context: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            }
        }
    }
}
