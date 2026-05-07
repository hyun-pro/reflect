package com.namhyun.reflect

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.namhyun.reflect.service.ReflectNotificationListener
import com.namhyun.reflect.update.UpdateChecker
import com.namhyun.reflect.update.UpdateState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    @Volatile private var lastCheckMs = 0L
    private val CHECK_THROTTLE_MS = 60_000L  // 1분 throttle

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { enableEdgeToEdge() }
        super.onCreate(savedInstanceState)
        setContent {
            com.namhyun.reflect.ui.theme.ReflectTheme {
                com.namhyun.reflect.ui.HomeScreen()
            }
        }
        triggerUpdateCheck()
    }

    override fun onResume() {
        super.onResume()
        triggerUpdateCheck()
    }

    private fun triggerUpdateCheck() {
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < CHECK_THROTTLE_MS) return
        lastCheckMs = now
        lifecycleScope.launch {
            runCatching {
                val state = UpdateChecker.check()
                if (state is UpdateState.Available) {
                    UpdateBus.update.value = state
                    runCatching { UpdateChecker.downloadAndInstall(this@MainActivity, state.info) }
                }
            }
        }
    }
}

object UpdateBus {
    val update = androidx.compose.runtime.mutableStateOf<com.namhyun.reflect.update.UpdateState?>(null)
}

object Permissions {
    fun isNotificationListenerEnabled(context: Context): Boolean = runCatching {
        val cn = ComponentName(context, ReflectNotificationListener::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        enabled.split(":").any { it == cn }
    }.getOrDefault(false)

    fun openNotificationListenerSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun isPostNotificationsGranted(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@runCatching true
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun isAccessibilityEnabled(context: Context): Boolean = runCatching {
        val cn = ComponentName(context, com.namhyun.reflect.service.ReflectAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        enabled.split(":").any { it.equals(cn, ignoreCase = true) }
    }.getOrDefault(false)

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        runCatching {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (direct.resolveActivity(context.packageManager) != null) {
                context.startActivity(direct); return
            }
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (list.resolveActivity(context.packageManager) != null) {
                context.startActivity(list); return
            }
            val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(info)
        }
    }
}
