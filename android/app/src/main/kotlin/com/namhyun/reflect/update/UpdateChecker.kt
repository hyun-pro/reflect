package com.namhyun.reflect.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.namhyun.reflect.BuildConfig
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.VersionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * GET /api/version 으로 최신 APK 정보 받아서
 *  - 새 버전이면 다운로드 → PackageInstaller / ACTION_VIEW intent 로 설치 유도
 */
object UpdateChecker {

    private val client = OkHttpClient()

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        val resp = BackendApi.version().getOrNull() ?: return@withContext UpdateState.UpToDate
        if (resp.latest_version_code <= BuildConfig.VERSION_CODE) return@withContext UpdateState.UpToDate
        UpdateState.Available(resp)
    }

    /**
     * APK 다운로드 후 설치 인텐트 발사. (REQUEST_INSTALL_PACKAGES 권한 + Settings 토글 필요)
     */
    suspend fun downloadAndInstall(context: Context, version: VersionResponse) = withContext(Dispatchers.IO) {
        val targetDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val targetFile = File(targetDir, "reflect-${version.latest_version}.apk")

        val req = Request.Builder().url(version.apk_url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("download ${resp.code}")
            targetFile.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            targetFile,
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(install)
    }
}

sealed class UpdateState {
    data object UpToDate : UpdateState()
    data class Available(val info: VersionResponse) : UpdateState()
}
