package com.namhyun.reflect.api

import kotlinx.serialization.Serializable

@Serializable
data class SuggestRequest(
    val app: String,
    val contact: String? = null,
    val relationship: String? = null,
    val incoming_message: String,
    val conversation_context: String? = null,
)

@Serializable
data class SuggestResponse(
    val suggestions: List<String>,
    val matched_count: Int = 0,
    val latency_ms: Long = 0,
)

@Serializable
data class IngestRequest(
    val app: String,
    val contact: String? = null,
    val relationship: String? = null,
    val incoming_message: String,
    val my_reply: String,
    val conversation_context: String? = null,
)

@Serializable
data class VersionResponse(
    val latest_version: String,
    val latest_version_code: Int,
    val apk_url: String,
    val changelog: String = "",
    val force_update: Boolean = false,
)
