package com.namhyun.reflect.api

import android.util.Log
import com.namhyun.reflect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object BackendApi {
    private const val TAG = "BackendApi"
    private val JSON = "application/json".toMediaType()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')
    private val apiKey: String = BuildConfig.API_KEY

    suspend fun suggest(req: SuggestRequest): Result<SuggestResponse> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(req).toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl/api/suggest")
            .header("X-API-Key", apiKey)
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "suggest failed ${resp.code}: $raw")
                    error("suggest ${resp.code}")
                }
                json.decodeFromString<SuggestResponse>(raw)
            }
        }.onFailure { Log.w(TAG, "suggest error", it) }
    }

    suspend fun ingest(req: IngestRequest): Result<Unit> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(req).toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl/api/ingest")
            .header("X-API-Key", apiKey)
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("ingest ${resp.code}")
            }
        }
    }

    suspend fun version(): Result<VersionResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/version")
            .header("X-API-Key", apiKey)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("version ${resp.code}")
                json.decodeFromString<VersionResponse>(raw)
            }
        }
    }
}
