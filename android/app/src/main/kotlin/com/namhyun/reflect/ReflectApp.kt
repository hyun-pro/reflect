package com.namhyun.reflect

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ReflectApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            "reflect_suggestions",
            getString(R.string.suggestion_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.suggestion_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }
}
