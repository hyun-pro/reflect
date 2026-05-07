package com.namhyun.reflect.update

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _progress = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val progress: StateFlow<DownloadState> = _progress

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        val resp = BackendApi.version().getOrNull() ?: return@withContext UpdateState.UpToDate
        if (resp.latest_version_code <= BuildConfig.VERSION_CODE) UpdateState.UpToDate
        else UpdateState.Available(resp)
    }

    /**
     * APK 백그라운드 다운로드 → 시스템 설치 화면 자동 호출.
     * 사용자는 "설치" 한 번만 탭하면 끝.
     */
    suspend fun downloadAndInstall(context: Context, version: VersionResponse): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                _progress.value = DownloadState.Downloading(0)
                val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                // 이전 APK 정리
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "reflect-${version.latest_version}.apk")

                val req = Request.Builder().url(version.apk_url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("download HTTP ${resp.code}")
                    val total = resp.body?.contentLength() ?: -1L
                    val source = resp.body!!.byteStream()
                    val sink = target.outputStream()
                    val buf = ByteArray(8192)
                    var read: Int
                    var sum = 0L
                    while (source.read(buf).also { read = it } != -1) {
                        sink.write(buf, 0, read)
                        sum += read
                        if (total > 0) {
                            _progress.value = DownloadState.Downloading(((sum * 100L) / total).toInt())
                        }
                    }
                    sink.flush(); sink.close(); source.close()
                }
                _progress.value = DownloadState.Installing

                val uri: Uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", target,
                )
                // REQUEST_INSTALL_PACKAGES 권한 미허용 시 사용자에게 권한 화면 띄움
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val pm = context.packageManager
                    if (!pm.canRequestPackageInstalls()) {
                        val grantIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(grantIntent) }
                        // 권한 없으면 일단 ACTION_VIEW 도 시도 (사용자가 다시 탭하면 됨)
                    }
                }

                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(view) }
                _progress.value = DownloadState.Idle
                Result.success(Unit)
            } catch (e: Throwable) {
                Log.w(TAG, "downloadAndInstall failed", e)
                _progress.value = DownloadState.Failed(e.message ?: "다운로드 실패")
                Result.failure(e)
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
