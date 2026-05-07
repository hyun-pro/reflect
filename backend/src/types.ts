export interface Env {
  // Secrets
  SUPABASE_URL: string;
  SUPABASE_SECRET_KEY: string;
  VOYAGE_API_KEY: string;
  ANTHROPIC_API_KEY: string;
  API_KEY: string;
  GITHUB_TOKEN?: string;
  OPENAI_API_KEY?: string;
  // Vars
  OWNER_NAME: string;
  OWNER_PERSONA: string;
  CLAUDE_MODEL: string;
  VOYAGE_MODEL: string;
  RAG_TOP_K: string;
  GITHUB_OWNER: string;
  GITHUB_REPO: string;
}

export interface SuggestRequest {
  app: string;             // 'kakao' | 'instagram' | 'facebook' | 'sms'
  contact?: string;        // 상대방 이름 (있으면 RAG 필터링)
  relationship?: string;   // 'friend' | 'family' | 'work' | ...
  incoming_message: string;
  conversation_context?: string;  // 직전 N턴 (선택)
}

export interface SuggestResponse {
  suggestions: string[];   // 정확히 3개
  matched_count: number;   // RAG hit 개수 (디버깅용)
  latency_ms: number;
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
  latest_version: string;       // 'v0.1.0'
  latest_version_code: number;  // 1, 2, 3, ...
  apk_url: string;              // 다운로드 URL (GitHub Releases)
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
