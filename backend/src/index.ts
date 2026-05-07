import { Hono } from 'hono';
import { cors } from 'hono/cors';
import type { Env, SuggestRequest, IngestRequest } from './types';
import { generateSuggestions } from './suggest';
import { ingestReply } from './ingest';
import { fetchLatestVersion } from './version';
import { fetchStats } from './stats';

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

// GET /api/stats — 학습 통계
app.get('/api/stats', async (c) => {
  try {
    const stats = await fetchStats(c.env);
    return c.json(stats);
  } catch (e: any) {
    console.error('[/api/stats]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// GET /api/version — GitHub Releases 에서 latest 동적 조회
app.get('/api/version', async (c) => {
  try {
    const v = await fetchLatestVersion(c.env);
    return c.json(v);
  } catch (e: any) {
    console.error('[/api/version]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// GET /apk/latest — 인증 없이 최신 APK 로 redirect (폰에서 첫 설치용)
//   /api/* 가 아니라 /apk/* 경로라 X-API-Key 미들웨어 안 탐
app.get('/apk/latest', async (c) => {
  try {
    const v = await fetchLatestVersion(c.env);
    return c.redirect(v.apk_url, 302);
  } catch (e: any) {
    console.error('[/apk/latest]', e);
    return c.text(`error: ${e?.message ?? e}`, 500);
  }
});

export default app;
