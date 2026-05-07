package com.namhyun.reflect.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.namhyun.reflect.MainActivity
import com.namhyun.reflect.Permissions
import java.util.concurrent.TimeUnit

/**
 * 30분마다 KeepAliveService 가 살아있는지 확인 + 죽었으면 재시작.
 * NotificationListener 권한이 켜져있을 때만 동작.
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (Permissions.isNotificationListenerEnabled(applicationContext)) {
                KeepAliveService.start(applicationContext)
                Log.i(TAG, "watchdog ping — keepalive started")
            }
            Result.success()
        } catch (e: Throwable) {
            Log.w(TAG, "watchdog failed", e)
            Result.success() // 실패해도 재시도 안 함 (다음 주기에 다시)
        }
    }

    companion object {
        private const val TAG = "Watchdog"
        const val WORK_NAME = "reflect_watchdog"

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
