package com.namhyun.reflect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅 완료 또는 앱 업데이트 후 KeepAliveService 자동 시작.
 * NotificationListener 는 시스템이 자동으로 재바인드하지만,
 * KeepAlive ForegroundService 는 명시적으로 띄워줘야 함.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> KeepAliveService.start(context)
            }
        }.onFailure { android.util.Log.w("BootReceiver", "boot", it) }
    }
}
