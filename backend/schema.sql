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
