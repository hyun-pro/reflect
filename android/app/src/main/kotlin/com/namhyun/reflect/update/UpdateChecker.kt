package com.namhyun.reflect.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.namhyun.reflect.BuildConfig
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.VersionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val INSTALL_ACTION = "com.namhyun.reflect.INSTALL_RESULT"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _progress = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val progress: StateFlow<DownloadState> = _progress

    internal fun reportInstallState(state: DownloadState) {
        _progress.value = state
    }

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        val resp = BackendApi.version().getOrNull() ?: return@withContext UpdateState.UpToDate
        if (resp.latest_version_code <= BuildConfig.VERSION_CODE) UpdateState.UpToDate
        else UpdateState.Available(resp)
    }

    /**
     * APK 백그라운드 다운로드 → PackageInstaller 세션으로 설치.
     *
     * 왜 PackageInstaller 인가: 삼성 One UI(Android 14~16)는 레거시
     * ACTION_VIEW + "application/vnd.android.package-archive" 인텐트 설치를
     * Auto Blocker 등으로 차단한다. PackageInstaller 세션 API 는 Play/F-Droid/
     * Obtainium 이 쓰는 정식 경로라 REQUEST_INSTALL_PACKAGES 만 허용돼 있으면
     * 동작한다. 실패 시에만 레거시 인텐트로 폴백.
     */
    suspend fun downloadAndInstall(context: Context, version: VersionResponse): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                _progress.value = DownloadState.Downloading(0)
                val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "reflect-${version.latest_version}.apk")

                val req = Request.Builder().url(version.apk_url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("download HTTP ${resp.code}")
                    val total = resp.body?.contentLength() ?: -1L
                    val source = resp.body!!.byteStream()
                    target.outputStream().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var sum = 0L
                        while (source.read(buf).also { read = it } != -1) {
                            sink.write(buf, 0, read)
                            sum += read
                            if (total > 0) {
                                _progress.value = DownloadState.Downloading(((sum * 100L) / total).toInt())
                            }
                        }
                        sink.flush()
                    }
                    source.close()
                }

                ensurePermissionThenInstall(context, target)
                Result.success(Unit)
            } catch (e: Throwable) {
                Log.w(TAG, "downloadAndInstall failed", e)
                _progress.value = DownloadState.Failed(e.message ?: "다운로드 실패")
                Result.failure(e)
            }
        }

    private fun ensurePermissionThenInstall(context: Context, apk: File) {
        // "출처를 알 수 없는 앱" 권한이 없으면 먼저 권한 화면을 띄운다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val grant = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(grant) }
            _progress.value = DownloadState.Failed("설치 권한을 켠 뒤 알림을 다시 탭해줘")
            return
        }
        try {
            installViaSession(context, apk)
        } catch (e: Throwable) {
            Log.w(TAG, "PackageInstaller session failed, fallback to ACTION_VIEW", e)
            installViaLegacyIntent(context, apk)
        }
    }

    /** Play/F-Droid 와 동일한 정식 설치 경로 (삼성 Android 16 차단 우회). */
    private fun installViaSession(context: Context, apk: File) {
        _progress.value = DownloadState.Installing
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("reflect.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(INSTALL_ACTION).setPackage(context.packageName),
                flags,
            )
            session.commit(callback.intentSender)
        }
        Log.i(TAG, "PackageInstaller session committed (id=$sessionId)")
    }

    /** 레거시 폴백 — 구형/타 OEM 에서만 필요. */
    private fun installViaLegacyIntent(context: Context, apk: File) {
        _progress.value = DownloadState.Installing
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(view) }
        _progress.value = DownloadState.Idle
    }

    /**
     * PackageInstaller 가 보내는 세션 상태 콜백 수신.
     * STATUS_PENDING_USER_ACTION → 시스템 설치 확인 화면을 띄운다.
     */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE,
            )) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                    }
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    reportInstallState(DownloadState.Idle)
                    Log.i(TAG, "install success")
                }
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "install failed status=$status msg=$msg")
                    reportInstallState(DownloadState.Failed(msg ?: "설치 실패 ($status)"))
                }
            }
        }
    }
}

sealed class UpdateState {
    data object UpToDate : UpdateState()
    data class Available(val info: VersionResponse) : UpdateState()
}

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val percent: Int) : DownloadState()
    data object Installing : DownloadState()
    data class Failed(val reason: String) : DownloadState()
}
