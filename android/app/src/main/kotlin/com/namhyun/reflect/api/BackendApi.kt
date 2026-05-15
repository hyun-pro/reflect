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

    private suspend fun <T> withBackoff(
        attempts: Int = 3,
        baseDelayMs: Long = 400L,
        block: suspend () -> T,
    ): T {
        var lastError: Throwable? = null
        for (i in 0 until attempts) {
            try { return block() } catch (e: Throwable) {
                lastError = e
                val msg = e.message ?: ""
                val transient = msg.contains("429") || msg.contains("5") || e is java.io.IOException
                if (!transient || i == attempts - 1) throw e
                val jitter = (Math.random() * 200).toLong()
                kotlinx.coroutines.delay(baseDelayMs * (1L shl i) + jitter)
            }
        }
        throw lastError!!
    }

    suspend fun suggest(req: SuggestRequest): Result<SuggestResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.e(TAG, "API_KEY empty — local.properties 의 REFLECT_API_KEY 가 빠졌거나 빌드 시 안 박힘")
            return@withContext Result.failure(IllegalStateException("API_KEY missing"))
        }
        val body = json.encodeToString(req).toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl/api/suggest")
            .header("X-API-Key", apiKey)
            .post(body)
            .build()
        runCatching {
            withBackoff {
                client.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "suggest failed ${resp.code}: $raw")
                        error("suggest ${resp.code}: $raw")
                    }
                    json.decodeFromString<SuggestResponse>(raw)
                }
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

    suspend fun stats(): Result<StatsResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext Result.failure(IllegalStateException("API_KEY missing"))
        val request = Request.Builder()
            .url("$baseUrl/api/stats")
            .header("X-API-Key", apiKey)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("stats ${resp.code}")
                json.decodeFromString<StatsResponse>(raw)
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

    // ─── Style profile ──────────────────────────────────────────────────────
    suspend fun getStyle(): Result<StyleProfile> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext Result.failure(IllegalStateException("API_KEY missing"))
        val request = Request.Builder()
            .url("$baseUrl/api/style")
            .header("X-API-Key", apiKey)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("style ${resp.code}")
                json.decodeFromString<StyleProfile>(raw)
            }
        }
    }

    suspend fun postBootstrap(answers: BootstrapAnswers): Result<StyleProfile> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext Result.failure(IllegalStateException("API_KEY missing"))
        val body = json.encodeToString(BootstrapRequest(answers)).toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl/api/style/bootstrap")
            .header("X-API-Key", apiKey)
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("bootstrap ${resp.code}: $raw")
                json.decodeFromString<StyleProfile>(raw)
            }
        }
    }

    // ─── Feedback (DPO) ─────────────────────────────────────────────────────
    suspend fun feedbackReject(req: FeedbackRejectRequest): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext Result.failure(IllegalStateException("API_KEY missing"))
        val body = json.encodeToString(req).toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl/api/feedback/reject")
            .header("X-API-Key", apiKey)
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("feedback ${resp.code}")
            }
        }
    }

    // ─── Training ──────────────────────────────────────────────────────────
    suspend fun trainingStatus(): Result<TrainingStatusResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext Result.failure(IllegalStateException("API_KEY missing"))
        val request = Request.Builder()
            .url("$baseUrl/api/training/status")
            .header("X-API-Key", apiKey)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("training status ${resp.code}")
                json.decodeFromString<TrainingStatusResponse>(raw)
            }
        }
    }

    suspend fun triggerTraining(force: Boolean = false): Result<TrainingTriggerResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext Result.failure(IllegalStateException("API_KEY missing"))
        val body = json.encodeToString(mapOf("force" to force)).toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl/api/training/trigger")
            .header("X-API-Key", apiKey)
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("trigger ${resp.code}: $raw")
                json.decodeFromString<TrainingTriggerResponse>(raw)
            }
        }
    }
}
