package com.namhyun.reflect

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class ReflectApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        try { ensureChannel() } catch (e: Throwable) { Log.w(TAG, "channel", e) }
        runCatching { com.namhyun.reflect.service.WatchdogWorker.schedule(this) }
        runCatching { com.namhyun.reflect.update.UpdateCheckWorker.schedule(this) }
    }

    private fun installCrashLogger() {
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching {
                    val sw = StringWriter()
                    e.printStackTrace(PrintWriter(sw))
                    val log = "thread=${t.name}\ntime=${System.currentTimeMillis()}\n$sw"
                    getExternalFilesDir(null)?.let { dir ->
                        File(dir, "last_crash.txt").writeText(log)
                    }
                    Log.e(TAG, "uncaught on ${t.name}", e)
                }
                if (previous != null) {
                    runCatching { previous.uncaughtException(t, e) }
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("reflect_suggestions") == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "reflect_suggestions",
                    getString(R.string.suggestion_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.suggestion_channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    companion object { private const val TAG = "ReflectApp" }
}
