import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { bearerAuth } from 'hono/bearer-auth';
import type { Env, SuggestRequest, IngestRequest, VersionResponse } from './types';
import { generateSuggestions } from './suggest';
import { ingestReply } from './ingest';

const app = new Hono<{ Bindings: Env }>();

app.use('*', cors());

// 헬스체크 (인증 없음)
app.get('/', (c) => c.json({ ok: true, service: 'reflect-backend', ts: Date.now() }));

// 인증 미들웨어 — 모든 /api/* 라우트는 X-API-Key 헤더 필요
app.use('/api/*', async (c, next) => {
  const provided = c.req.header('X-API-Key') ?? c.req.header('Authorization')?.replace(/^Bearer\s+/, '');
  if (!provided || provided !== c.env.API_KEY) {
    return c.json({ error: 'unauthorized' }, 401);
  }
  await next();
});

// POST /api/suggest — 받은 메시지 → 추천 답변 3개
app.post('/api/suggest', async (c) => {
  try {
    const body = await c.req.json<SuggestRequest>();
    if (!body.incoming_message) return c.json({ error: 'incoming_message required' }, 400);
    const result = await generateSuggestions(c.env, body);
    return c.json(result);
  } catch (e: any) {
    console.error('[/api/suggest]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// POST /api/ingest — 새 답장 학습
app.post('/api/ingest', async (c) => {
  try {
    const body = await c.req.json<IngestRequest>();
    const result = await ingestReply(c.env, body);
    return c.json(result);
  } catch (e: any) {
    console.error('[/api/ingest]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// GET /api/version — APK 자동 업데이트 체크
app.get('/api/version', (c) => {
  // TODO: GitHub Releases API 연동으로 동적화 가능. 일단 하드코딩
  const response: VersionResponse = {
    latest_version: 'v0.1.0',
    latest_version_code: 1,
    apk_url: 'https://github.com/CHANGE_ME/reflect-android/releases/latest/download/reflect.apk',
    changelog: '초기 빌드',
    force_update: false,
  };
  return c.json(response);
});

export default app;
