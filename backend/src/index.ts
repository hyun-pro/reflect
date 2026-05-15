import { Hono } from 'hono';
import { cors } from 'hono/cors';
import type {
  Env,
  SuggestRequest,
  IngestRequest,
  BootstrapRequest,
  FeedbackRejectRequest,
} from './types';
import { generateSuggestions } from './suggest';
import { ingestReply } from './ingest';
import { fetchLatestVersion } from './version';
import { fetchStats } from './stats';
import { saveBootstrap, getStyleProfile, refreshAutoExtract } from './style';
import { captureFeedback } from './feedback';
import {
  getTrainingStatus,
  triggerTraining,
  trainingCallback,
  type TrainingCallbackBody,
} from './training';

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

// POST /api/feedback/reject — 추천 거절 + 사용자가 친 답장 (DPO 페어)
app.post('/api/feedback/reject', async (c) => {
  try {
    const body = await c.req.json<FeedbackRejectRequest>();
    if (!body.incoming_message || !body.chosen_reply) {
      return c.json({ error: 'incoming_message and chosen_reply required' }, 400);
    }
    const result = await captureFeedback(c.env, body);
    return c.json(result);
  } catch (e: any) {
    console.error('[/api/feedback/reject]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// GET /api/stats — 학습 통계 (replies 페어 + DPO 카운트 + 학습 상태)
app.get('/api/stats', async (c) => {
  try {
    const stats = await fetchStats(c.env);
    return c.json(stats);
  } catch (e: any) {
    console.error('[/api/stats]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// ─── 스타일 프로파일 ────────────────────────────────────────────────────────
app.post('/api/style/bootstrap', async (c) => {
  try {
    const body = await c.req.json<BootstrapRequest>();
    const profile = await saveBootstrap(c.env, body);
    return c.json(profile);
  } catch (e: any) {
    console.error('[/api/style/bootstrap]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

app.get('/api/style', async (c) => {
  try {
    const profile = await getStyleProfile(c.env);
    return c.json(profile ?? { owner: 'self' });
  } catch (e: any) {
    console.error('[/api/style]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// POST /api/style/refresh — 누적된 페어에서 통계+요약 자동 추출
app.post('/api/style/refresh', async (c) => {
  try {
    const profile = await refreshAutoExtract(c.env);
    return c.json(profile ?? { owner: 'self' });
  } catch (e: any) {
    console.error('[/api/style/refresh]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// ─── 학습 (Fine-tune) ───────────────────────────────────────────────────────
app.get('/api/training/status', async (c) => {
  try {
    const status = await getTrainingStatus(c.env);
    return c.json(status);
  } catch (e: any) {
    console.error('[/api/training/status]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

app.post('/api/training/trigger', async (c) => {
  try {
    const body = await c.req
      .json<{ force?: boolean }>()
      .catch(() => ({} as { force?: boolean }));
    const result = await triggerTraining(c.env, { force: !!body.force });
    return c.json(result);
  } catch (e: any) {
    console.error('[/api/training/trigger]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// POST /api/training/callback — Modal 학습 잡 완료 시 호출됨
//   X-API-Key 인증 통과 후 처리. (Modal 잡이 API_KEY 를 갖고 있어야 함)
app.post('/api/training/callback', async (c) => {
  try {
    const body = await c.req.json<TrainingCallbackBody>();
    const result = await trainingCallback(c.env, body);
    return c.json(result);
  } catch (e: any) {
    console.error('[/api/training/callback]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

// ─── 버전 / APK ─────────────────────────────────────────────────────────────
app.get('/api/version', async (c) => {
  try {
    const v = await fetchLatestVersion(c.env);
    return c.json(v);
  } catch (e: any) {
    console.error('[/api/version]', e);
    return c.json({ error: e?.message ?? String(e) }, 500);
  }
});

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
