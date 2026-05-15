-- Reflect: 본인 답장 RAG 스키마
-- Supabase Dashboard -> SQL Editor 에 통째로 붙여넣고 Run

-- 1. pgvector 확장 활성화 (Database -> Extensions에서 이미 켰으면 no-op)
create extension if not exists vector;

-- 2. 답장 페어 테이블
create table if not exists replies (
  id bigserial primary key,

  -- 컨텍스트
  app text not null,                  -- 'kakao' | 'instagram' | 'facebook' | 'sms' ...
  contact text,                       -- 상대방 이름/식별자
  relationship text,                  -- 'friend' | 'family' | 'work' | 'partner' | null

  -- 핵심 페어
  incoming_message text not null,     -- 상대가 보낸 메시지 (직전 N개 합칠 수도)
  my_reply text not null,             -- 내가 보낸 답장

  -- 추가 컨텍스트 (선택)
  conversation_context text,          -- 직전 대화 N턴 (정밀 retrieval용)
  reply_at timestamptz,               -- 답장 시각

  -- 임베딩 (incoming_message 기반, voyage-multilingual-2 = 1024차원)
  embedding vector(1024),

  created_at timestamptz default now()
);

-- 3. 인덱스
create index if not exists replies_embedding_idx
  on replies using ivfflat (embedding vector_cosine_ops) with (lists = 100);
create index if not exists replies_app_contact_idx on replies (app, contact);
create index if not exists replies_relationship_idx on replies (relationship);

-- 4. 유사 답장 검색 RPC
create or replace function match_replies(
  query_embedding vector(1024),
  filter_app text default null,
  filter_contact text default null,
  filter_relationship text default null,
  match_count int default 10
)
returns table (
  id bigint,
  app text,
  contact text,
  relationship text,
  incoming_message text,
  my_reply text,
  similarity float
)
language sql stable
as $$
  select
    replies.id,
    replies.app,
    replies.contact,
    replies.relationship,
    replies.incoming_message,
    replies.my_reply,
    1 - (replies.embedding <=> query_embedding) as similarity
  from replies
  where
    (filter_app is null or replies.app = filter_app)
    and (filter_contact is null or replies.contact = filter_contact)
    and (filter_relationship is null or replies.relationship = filter_relationship)
  order by replies.embedding <=> query_embedding
  limit match_count;
$$;

-- 5. RLS (Row Level Security)
alter table replies enable row level security;

-- 본인만 쓰는 앱이지만, anon key가 모바일 앱에 박힐 거라 service_role로 락
-- (모바일 앱은 Edge Function 통해서만 접근, Edge Function은 service_role 사용)
drop policy if exists "service_role_only_select" on replies;
drop policy if exists "service_role_only_all" on replies;
create policy "service_role_only_all" on replies
  for all
  using (auth.role() = 'service_role')
  with check (auth.role() = 'service_role');

-- 6. (선택) 추천 답변 캐시 테이블 — 알림 도착 즉시 생성 후 키보드가 꺼내씀
create table if not exists suggestions (
  id bigserial primary key,
  app text not null,
  contact text,
  incoming_message text not null,
  suggestions jsonb not null,         -- ["답변1", "답변2", "답변3"]
  generated_at timestamptz default now(),
  used boolean default false
);
create index if not exists suggestions_app_contact_idx
  on suggestions (app, contact, generated_at desc);

alter table suggestions enable row level security;
drop policy if exists "service_role_only_suggestions" on suggestions;
create policy "service_role_only_suggestions" on suggestions
  for all
  using (auth.role() = 'service_role')
  with check (auth.role() = 'service_role');

-- ============================================================================
-- 7. 스타일 프로파일 (콜드 스타트: 페어 0개여도 본인 톤 흉내)
--    단일 사용자 앱이라 row 1개. owner='self' 고정.
-- ============================================================================
create table if not exists style_profile (
  owner text primary key default 'self',

  -- 부트스트랩 인터뷰 원본 답변 (8-10문항)
  bootstrap_answers jsonb,             -- {"avg_length": "...", "emoji_freq": "...", ...}

  -- 자동 추출 통계 (Worker가 페어 누적 시 주기적으로 갱신)
  avg_reply_chars int,                 -- 평균 답장 글자수
  emoji_per_100 int,                   -- 100자당 이모지 개수
  laughter_ratio real,                 -- ㅋㅋ/ㅎㅎ 등장 비율 (0~1)
  banmal_ratio real,                   -- 반말 비율 (0~1, 휴리스틱)
  top_endings jsonb,                   -- 자주 쓰는 종결어미 top 20 [{e:"~지", c:42}, ...]
  top_phrases jsonb,                   -- 자주 쓰는 짧은 표현 top 20

  -- 시스템 프롬프트에 그대로 박을 자연어 요약 (Claude 가 페어로부터 생성)
  style_summary text,

  bootstrap_at timestamptz,
  auto_extracted_at timestamptz,
  updated_at timestamptz default now()
);

alter table style_profile enable row level security;
drop policy if exists "service_role_only_style" on style_profile;
create policy "service_role_only_style" on style_profile
  for all
  using (auth.role() = 'service_role')
  with check (auth.role() = 'service_role');

-- ============================================================================
-- 8. 학습 실행 이력 (DPO 페어가 이걸 FK 참조하므로 먼저 정의)
-- ============================================================================
create table if not exists training_runs (
  id bigserial primary key,
  status text not null default 'queued',   -- queued|running|succeeded|failed|cancelled

  base_model text not null,                 -- 'Qwen/Qwen2.5-7B-Instruct' 등
  adapter_name text,                        -- Together AI 에 등록된 어댑터 이름

  pair_count_at_start int not null,
  dpo_count_at_start int not null,

  -- 학습 하이퍼파라미터 스냅샷
  hparams jsonb,                            -- {lr, epochs, lora_r, lora_alpha, ...}

  -- 학습 후 자동 평가
  eval_holdout_count int,
  eval_score real,                          -- 0~1, 높을수록 본인 톤 가까움
  eval_details jsonb,

  -- 외부 추적
  modal_run_id text,                        -- Modal job id
  together_ft_id text,                      -- Together AI fine-tune id

  started_at timestamptz default now(),
  finished_at timestamptz,
  error text
);
create index if not exists training_runs_status_idx on training_runs (status);
create index if not exists training_runs_started_idx on training_runs (started_at desc);

alter table training_runs enable row level security;
drop policy if exists "service_role_only_runs" on training_runs;
create policy "service_role_only_runs" on training_runs
  for all
  using (auth.role() = 'service_role')
  with check (auth.role() = 'service_role');

-- ============================================================================
-- 9. DPO 페어 (추천 거절 + 사용자가 실제로 친 답장 = 강력한 학습 신호)
--    LoRA + DPO 학습 시 (rejected, chosen) 페어로 사용.
-- ============================================================================
create table if not exists dpo_pairs (
  id bigserial primary key,
  app text not null,
  contact text,
  relationship text,

  incoming_message text not null,
  rejected_suggestions jsonb not null,  -- 모델이 추천했지만 거절된 후보들
  chosen_reply text not null,           -- 사용자가 실제로 친 답장

  conversation_context text,
  reply_at timestamptz,
  embedding vector(1024),                -- incoming 기반 (replies 와 호환)

  -- 거절 강도: edit_distance / max(len(rejected), len(chosen))
  divergence real,

  consumed_by bigint references training_runs(id) on delete set null,
  created_at timestamptz default now()
);
create index if not exists dpo_pairs_created_idx on dpo_pairs (created_at desc);
create index if not exists dpo_pairs_consumed_idx on dpo_pairs (consumed_by);

alter table dpo_pairs enable row level security;
drop policy if exists "service_role_only_dpo" on dpo_pairs;
create policy "service_role_only_dpo" on dpo_pairs
  for all
  using (auth.role() = 'service_role')
  with check (auth.role() = 'service_role');

-- ============================================================================
-- 10. 단일 활성 어댑터 포인터 (1개 row)
-- ============================================================================
create table if not exists active_adapter (
  owner text primary key default 'self',
  training_run_id bigint references training_runs(id) on delete set null,
  adapter_name text,
  activated_at timestamptz default now()
);

alter table active_adapter enable row level security;
drop policy if exists "service_role_only_active" on active_adapter;
create policy "service_role_only_active" on active_adapter
  for all
  using (auth.role() = 'service_role')
  with check (auth.role() = 'service_role');

-- ============================================================================
-- 11. 빠른 카운트 RPC (대시보드 + 학습 트리거 검사용)
-- ============================================================================
create or replace function reflect_counts()
returns table (pair_count bigint, dpo_count bigint, last_run_pair_count int, last_run_at timestamptz)
language sql stable as $$
  select
    (select count(*) from replies)::bigint as pair_count,
    (select count(*) from dpo_pairs)::bigint as dpo_count,
    (select pair_count_at_start from training_runs where status='succeeded'
       order by finished_at desc nulls last limit 1) as last_run_pair_count,
    (select finished_at from training_runs where status='succeeded'
       order by finished_at desc nulls last limit 1) as last_run_at;
$$;
