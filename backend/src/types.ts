export interface Env {
  // Secrets
  SUPABASE_URL: string;
  SUPABASE_SECRET_KEY: string;
  VOYAGE_API_KEY: string;
  ANTHROPIC_API_KEY: string;
  API_KEY: string;
  GITHUB_TOKEN?: string;
  OPENAI_API_KEY?: string;
  // Fine-tune / training (선택 — 없으면 RAG 단독)
  TOGETHER_API_KEY?: string;        // Together AI: 어댑터 추론 + 학습
  MODAL_TRIGGER_URL?: string;       // Modal 학습 잡 트리거 endpoint
  MODAL_TRIGGER_TOKEN?: string;     // Modal 호출 인증
  // Vars
  OWNER_NAME: string;
  OWNER_PERSONA: string;
  CLAUDE_MODEL: string;
  VOYAGE_MODEL: string;
  RAG_TOP_K: string;
  GITHUB_OWNER: string;
  GITHUB_REPO: string;
  // 학습 트리거 임계값 (선택 — 기본 1000 페어, +500 마다 재학습)
  TRAIN_MIN_PAIRS?: string;
  TRAIN_DELTA_PAIRS?: string;
}

export interface SuggestRequest {
  app: string;
  contact?: string;
  relationship?: string;
  incoming_message: string;
  conversation_context?: string;
}

export interface SuggestResponse {
  suggestions: string[];
  matched_count: number;
  latency_ms: number;
  source?: 'rag' | 'finetune' | 'fallback';  // 어느 경로로 생성됐는지
  adapter?: string | null;                    // fine-tune 경로면 어댑터 이름
}

export interface IngestRequest {
  app: string;
  contact?: string;
  relationship?: string;
  incoming_message: string;
  my_reply: string;
  conversation_context?: string;
}

export interface VersionResponse {
  latest_version: string;
  latest_version_code: number;
  apk_url: string;
  changelog: string;
  force_update: boolean;
}

export interface MatchedReply {
  id: number;
  app: string;
  contact: string | null;
  relationship: string | null;
  incoming_message: string;
  my_reply: string;
  similarity: number;
}

// ─────────────────────────────────────────────────────────────────────────────
// Style profile (콜드 스타트 부트스트랩 + 자동 추출)
// ─────────────────────────────────────────────────────────────────────────────
export interface BootstrapAnswers {
  avg_length?: string;          // "짧게" | "보통" | "길게"
  emoji_freq?: string;          // "거의 안 씀" | "가끔" | "자주"
  laughter?: string;            // "ㅋㅋ" | "ㅎㅎ" | "안 씀" | "기타"
  banmal_jondaemal?: string;    // "거의 반말" | "반반" | "거의 존댓말"
  endings?: string;             // 자주 쓰는 종결어미 (사용자 직접 입력)
  catchphrases?: string;        // 자주 쓰는 표현
  family_tone?: string;
  friend_tone?: string;
  work_tone?: string;
  free_note?: string;           // 자유 서술 (성격/말투 특징)
}

export interface StyleProfile {
  owner: string;
  bootstrap_answers?: BootstrapAnswers | null;
  avg_reply_chars?: number | null;
  emoji_per_100?: number | null;
  laughter_ratio?: number | null;
  banmal_ratio?: number | null;
  top_endings?: { e: string; c: number }[] | null;
  top_phrases?: { p: string; c: number }[] | null;
  style_summary?: string | null;
  bootstrap_at?: string | null;
  auto_extracted_at?: string | null;
  updated_at?: string | null;
}

export interface BootstrapRequest {
  answers: BootstrapAnswers;
}

// ─────────────────────────────────────────────────────────────────────────────
// Feedback (DPO 페어)
// ─────────────────────────────────────────────────────────────────────────────
export interface FeedbackRejectRequest {
  app: string;
  contact?: string;
  relationship?: string;
  incoming_message: string;
  rejected_suggestions: string[];   // 모델이 추천했던 후보들 (1~3개)
  chosen_reply: string;             // 사용자가 실제로 친 답장
  conversation_context?: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// Training
// ─────────────────────────────────────────────────────────────────────────────
export interface TrainingRun {
  id: number;
  status: 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';
  base_model: string;
  adapter_name?: string | null;
  pair_count_at_start: number;
  dpo_count_at_start: number;
  hparams?: Record<string, unknown> | null;
  eval_holdout_count?: number | null;
  eval_score?: number | null;
  modal_run_id?: string | null;
  together_ft_id?: string | null;
  started_at?: string | null;
  finished_at?: string | null;
  error?: string | null;
}

export interface TrainingStatusResponse {
  pair_count: number;
  dpo_count: number;
  last_run_pair_count: number | null;
  last_run_at: string | null;
  active_adapter: string | null;
  min_pairs: number;          // 첫 학습 트리거 임계값
  delta_pairs: number;        // 재학습 트리거 (마지막 학습 후 +N)
  ready_to_train: boolean;
  next_threshold: number;     // 사용자가 보는 게이지 목표값
  training_enabled: boolean;  // Modal 학습 인프라 연결 여부 (false면 페어 수집·RAG 단계)
  in_flight?: TrainingRun | null;
  latest?: TrainingRun | null;
}

export interface TrainingTriggerResponse {
  ok: boolean;
  run_id?: number;
  reason?: string;
}
