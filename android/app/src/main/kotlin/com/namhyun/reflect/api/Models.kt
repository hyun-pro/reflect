package com.namhyun.reflect.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    val source: String? = null,        // 'rag' | 'finetune' | 'fallback'
    val adapter: String? = null,
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

@Serializable
data class StatsResponse(
    val total: Int = 0,
    val today: Int = 0,
    val thisWeek: Int = 0,
)

// ─── 스타일 프로파일 (콜드 스타트 부트스트랩) ────────────────────────────────
@Serializable
data class BootstrapAnswers(
    val avg_length: String? = null,         // "짧게" | "보통" | "길게"
    val emoji_freq: String? = null,         // "거의 안 씀" | "가끔" | "자주"
    val laughter: String? = null,           // "ㅋㅋ" | "ㅎㅎ" | "안 씀" | "기타"
    val banmal_jondaemal: String? = null,   // "거의 반말" | "반반" | "거의 존댓말"
    val endings: String? = null,
    val catchphrases: String? = null,
    val family_tone: String? = null,
    val friend_tone: String? = null,
    val work_tone: String? = null,
    val free_note: String? = null,
)

@Serializable
data class BootstrapRequest(val answers: BootstrapAnswers)

@Serializable
data class StyleProfile(
    val owner: String = "self",
    val bootstrap_answers: BootstrapAnswers? = null,
    val avg_reply_chars: Int? = null,
    val emoji_per_100: Int? = null,
    val laughter_ratio: Float? = null,
    val banmal_ratio: Float? = null,
    val style_summary: String? = null,
    val bootstrap_at: String? = null,
    val auto_extracted_at: String? = null,
    val updated_at: String? = null,
)

// ─── 피드백 (DPO 페어) ──────────────────────────────────────────────────────
@Serializable
data class FeedbackRejectRequest(
    val app: String,
    val contact: String? = null,
    val relationship: String? = null,
    val incoming_message: String,
    val rejected_suggestions: List<String>,
    val chosen_reply: String,
    val conversation_context: String? = null,
)

// ─── 학습 상태 ─────────────────────────────────────────────────────────────
@Serializable
data class TrainingRun(
    val id: Long,
    val status: String,
    val base_model: String,
    val adapter_name: String? = null,
    val pair_count_at_start: Int,
    val dpo_count_at_start: Int,
    val eval_score: Float? = null,
    val started_at: String? = null,
    val finished_at: String? = null,
    val error: String? = null,
)

@Serializable
data class TrainingStatusResponse(
    val pair_count: Int,
    val dpo_count: Int,
    val last_run_pair_count: Int? = null,
    val last_run_at: String? = null,
    val active_adapter: String? = null,
    val min_pairs: Int,
    val delta_pairs: Int,
    val ready_to_train: Boolean,
    val next_threshold: Int,
    val training_enabled: Boolean = false,
    val in_flight: TrainingRun? = null,
    val latest: TrainingRun? = null,
)

@Serializable
data class TrainingTriggerResponse(
    val ok: Boolean,
    val run_id: Long? = null,
    val reason: String? = null,
)
