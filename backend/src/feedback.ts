import { createClient } from '@supabase/supabase-js';
import type { Env, FeedbackRejectRequest } from './types';
import { embed } from './embedding';
import { ingestReply } from './ingest';

/**
 * 추천 거절 + 사용자가 친 실제 답장 = DPO 학습 페어 + 일반 ingest 둘 다 수행.
 * - dpo_pairs 테이블: (rejected, chosen) 그대로 저장 → LoRA+DPO 학습 단계에서 가장 강력한 신호.
 * - replies 테이블: chosen_reply 는 정상 RAG 학습 데이터로도 들어감.
 */
export async function captureFeedback(
  env: Env,
  req: FeedbackRejectRequest
): Promise<{ dpo_id: number; reply_id?: number }> {
  if (!req.incoming_message || !req.chosen_reply) {
    throw new Error('incoming_message and chosen_reply are required');
  }
  const rejected = Array.isArray(req.rejected_suggestions) ? req.rejected_suggestions : [];

  // 발산도(divergence) — chosen 이 rejected 와 얼마나 다른지. 0이면 사실상 채택, 1이면 완전히 다름.
  const divergence = computeDivergence(rejected, req.chosen_reply);

  // divergence 가 너무 낮으면 (≤0.15) 사실상 추천 채택이므로 DPO 페어로 저장 안 함.
  // 일반 ingest 만 함.
  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });

  let dpoId = -1;
  if (rejected.length > 0 && divergence > 0.15) {
    const embedding = await embed(env, req.incoming_message, 'document');
    const { data, error } = await supabase
      .from('dpo_pairs')
      .insert({
        app: req.app,
        contact: req.contact ?? null,
        relationship: req.relationship ?? null,
        incoming_message: req.incoming_message,
        rejected_suggestions: rejected,
        chosen_reply: req.chosen_reply,
        conversation_context: req.conversation_context ?? null,
        embedding,
        divergence,
        reply_at: new Date().toISOString(),
      })
      .select('id')
      .single();
    if (error) {
      console.warn('[feedback] dpo insert failed', error.message);
    } else if (data) {
      dpoId = (data as { id: number }).id;
    }
  }

  // 일반 ingest 도 항상 수행 (chosen_reply 는 본인이 친 거니까 학습 데이터로 가치 있음)
  let replyId: number | undefined;
  try {
    const r = await ingestReply(env, {
      app: req.app,
      contact: req.contact,
      relationship: req.relationship,
      incoming_message: req.incoming_message,
      my_reply: req.chosen_reply,
      conversation_context: req.conversation_context,
    });
    replyId = r.id;
  } catch (e) {
    console.warn('[feedback] ingest fallthrough failed', e);
  }

  return { dpo_id: dpoId, reply_id: replyId };
}

/**
 * 0~1 score. 0 = chosen 이 rejected 후보 중 하나와 거의 같음(추천 채택), 1 = 완전히 다름.
 * Levenshtein 정규화.
 */
function computeDivergence(rejected: string[], chosen: string): number {
  if (rejected.length === 0) return 1;
  const c = chosen.trim();
  const minDist = Math.min(
    ...rejected.map((r) => normalizedLevenshtein(r.trim(), c))
  );
  return Math.max(0, Math.min(1, minDist));
}

function normalizedLevenshtein(a: string, b: string): number {
  const maxLen = Math.max(a.length, b.length);
  if (maxLen === 0) return 0;
  const d = levenshtein(a, b);
  return d / maxLen;
}

function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;
  const al = a.length, bl = b.length;
  let prev = new Array<number>(bl + 1);
  let curr = new Array<number>(bl + 1);
  for (let j = 0; j <= bl; j++) prev[j] = j;
  for (let i = 1; i <= al; i++) {
    curr[0] = i;
    for (let j = 1; j <= bl; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      curr[j] = Math.min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost);
    }
    [prev, curr] = [curr, prev];
  }
  return prev[bl];
}
