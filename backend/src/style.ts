import { createClient, SupabaseClient } from '@supabase/supabase-js';
import Anthropic from '@anthropic-ai/sdk';
import type { Env, BootstrapRequest, StyleProfile } from './types';

const OWNER = 'self';
const CACHE_TTL_MS = 60_000; // 60s in-process cache

let cached: { at: number; value: StyleProfile | null } | null = null;

function sb(env: Env): SupabaseClient {
  return createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });
}

export async function getStyleProfile(env: Env): Promise<StyleProfile | null> {
  if (cached && Date.now() - cached.at < CACHE_TTL_MS) return cached.value;
  const supabase = sb(env);
  const { data, error } = await supabase
    .from('style_profile')
    .select('*')
    .eq('owner', OWNER)
    .maybeSingle();
  if (error) {
    console.warn('[style] fetch failed', error.message);
    return null;
  }
  cached = { at: Date.now(), value: (data as StyleProfile | null) ?? null };
  return cached.value;
}

function invalidate() {
  cached = null;
}

export async function saveBootstrap(env: Env, req: BootstrapRequest): Promise<StyleProfile> {
  if (!req.answers || typeof req.answers !== 'object') {
    throw new Error('answers required');
  }
  const supabase = sb(env);
  const now = new Date().toISOString();
  const { data, error } = await supabase
    .from('style_profile')
    .upsert(
      {
        owner: OWNER,
        bootstrap_answers: req.answers,
        bootstrap_at: now,
        updated_at: now,
      },
      { onConflict: 'owner' }
    )
    .select('*')
    .single();
  if (error) throw new Error(`bootstrap save failed: ${error.message}`);
  invalidate();
  return data as StyleProfile;
}

/**
 * 누적된 replies 에서 통계 자동 추출 + Claude 로 자연어 요약 생성.
 * 페어 수가 30 미만이면 통계 신뢰도 낮으니 부트스트랩 답변만 의존.
 */
export async function refreshAutoExtract(env: Env): Promise<StyleProfile | null> {
  const supabase = sb(env);
  const { data: rows, error } = await supabase
    .from('replies')
    .select('my_reply, incoming_message, contact, relationship')
    .order('created_at', { ascending: false })
    .limit(500);
  if (error) {
    console.warn('[style] auto-extract fetch failed', error.message);
    return null;
  }
  const replies = (rows ?? []) as { my_reply: string }[];
  if (replies.length < 30) {
    return getStyleProfile(env);
  }

  // 통계
  const stats = computeStats(replies.map((r) => r.my_reply));

  // 자연어 요약 (Claude — 비용 절약 위해 sample 100개만)
  const sample = replies.slice(0, 100).map((r) => r.my_reply);
  let summary = '';
  try {
    const anthropic = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
    const msg = await anthropic.messages.create({
      model: env.CLAUDE_MODEL,
      max_tokens: 600,
      system:
        '다음은 한 사용자가 메신저에서 직접 보낸 답장 100개다. 이 사람의 말투 특징을 7-10줄로 간결하게 정리해라. 길이/이모지/종결어미/줄임말/감정표현/존댓말여부/상대별 차이 위주. 메타 코멘트 없이 특징만.',
      messages: [{ role: 'user', content: sample.join('\n---\n') }],
    });
    summary = msg.content
      .filter((b): b is Anthropic.TextBlock => b.type === 'text')
      .map((b) => b.text)
      .join('')
      .trim();
  } catch (e) {
    console.warn('[style] summary generation failed', e);
  }

  const now = new Date().toISOString();
  const { data, error: upErr } = await supabase
    .from('style_profile')
    .upsert(
      {
        owner: OWNER,
        avg_reply_chars: stats.avgChars,
        emoji_per_100: stats.emojiPer100,
        laughter_ratio: stats.laughterRatio,
        banmal_ratio: stats.banmalRatio,
        top_endings: stats.topEndings,
        top_phrases: stats.topPhrases,
        style_summary: summary || null,
        auto_extracted_at: now,
        updated_at: now,
      },
      { onConflict: 'owner' }
    )
    .select('*')
    .single();
  if (upErr) throw new Error(`auto-extract save failed: ${upErr.message}`);
  invalidate();
  return data as StyleProfile;
}

interface ExtractedStats {
  avgChars: number;
  emojiPer100: number;
  laughterRatio: number;
  banmalRatio: number;
  topEndings: { e: string; c: number }[];
  topPhrases: { p: string; c: number }[];
}

function computeStats(replies: string[]): ExtractedStats {
  const totalChars = replies.reduce((s, r) => s + r.length, 0);
  const avgChars = Math.round(totalChars / Math.max(1, replies.length));

  // 이모지 추정: surrogate pair + 이모지 블록 + ㅋㅋ/ㅎㅎ 별도
  const emojiRe = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/gu;
  const emojiCount = replies.reduce((s, r) => s + (r.match(emojiRe)?.length ?? 0), 0);
  const emojiPer100 = totalChars > 0 ? Math.round((emojiCount / totalChars) * 100) : 0;

  const laughterCount = replies.filter((r) => /ㅋㅋ|ㅎㅎ/.test(r)).length;
  const laughterRatio = replies.length > 0 ? laughterCount / replies.length : 0;

  // 반말 휴리스틱: '요' 로 안 끝나고 '다/지/네/야/어' 등으로 끝나면 반말
  const banmalCount = replies.filter((r) => {
    const trimmed = r.trim();
    if (!trimmed) return false;
    if (/[요죠]\s*[.!?~ㅋㅎ]*\s*$/.test(trimmed)) return false;
    if (/[니까]\s*[.!?~ㅋㅎ]*\s*$/.test(trimmed)) return false;
    return /[가-힣ㄱ-ㅎ]/.test(trimmed);
  }).length;
  const banmalRatio = replies.length > 0 ? banmalCount / replies.length : 0;

  // 종결어미 top: 마지막 한글 1~2자
  const endingMap = new Map<string, number>();
  for (const r of replies) {
    const m = r.trim().match(/([가-힣ㄱ-ㅎ]{1,2})[.!?~ㅋㅎ\s]*$/);
    if (m) endingMap.set(m[1], (endingMap.get(m[1]) ?? 0) + 1);
  }
  const topEndings = Array.from(endingMap.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 20)
    .map(([e, c]) => ({ e, c }));

  // 자주 쓰는 짧은 표현 top: 2~6자 단어
  const phraseMap = new Map<string, number>();
  for (const r of replies) {
    const tokens = r.split(/[\s.,!?~]+/).filter((t) => /^[가-힣ㄱ-ㅎ]{2,6}$/.test(t));
    for (const t of tokens) phraseMap.set(t, (phraseMap.get(t) ?? 0) + 1);
  }
  const topPhrases = Array.from(phraseMap.entries())
    .filter(([, c]) => c >= 3)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 20)
    .map(([p, c]) => ({ p, c }));

  return { avgChars, emojiPer100, laughterRatio, banmalRatio, topEndings, topPhrases };
}

/**
 * 시스템 프롬프트에 그대로 박는 자연어 블록.
 * 부트스트랩만 있을 때 / 자동 추출만 있을 때 / 둘 다 있을 때 적절히 분기.
 */
export function styleBlock(profile: StyleProfile | null): string {
  if (!profile) return '';
  const lines: string[] = [];

  const b = profile.bootstrap_answers;
  if (b) {
    if (b.avg_length) lines.push(`- 답장 길이 선호: ${b.avg_length}`);
    if (b.emoji_freq) lines.push(`- 이모지 빈도: ${b.emoji_freq}`);
    if (b.laughter) lines.push(`- 웃음 표현: ${b.laughter}`);
    if (b.banmal_jondaemal) lines.push(`- 반말/존댓말 비율: ${b.banmal_jondaemal}`);
    if (b.endings) lines.push(`- 자주 쓰는 종결어미: ${b.endings}`);
    if (b.catchphrases) lines.push(`- 자주 쓰는 표현: ${b.catchphrases}`);
    if (b.family_tone) lines.push(`- 가족 대상 톤: ${b.family_tone}`);
    if (b.friend_tone) lines.push(`- 친구 대상 톤: ${b.friend_tone}`);
    if (b.work_tone) lines.push(`- 직장/윗사람 대상 톤: ${b.work_tone}`);
    if (b.free_note) lines.push(`- 자기 묘사: ${b.free_note}`);
  }

  if (profile.avg_reply_chars != null) {
    lines.push(`- 실제 평균 답장 길이: ${profile.avg_reply_chars}자`);
  }
  if (profile.emoji_per_100 != null && profile.emoji_per_100 > 0) {
    lines.push(`- 실제 이모지 빈도: 100자당 ${profile.emoji_per_100}개`);
  }
  if (profile.laughter_ratio != null) {
    lines.push(`- 실제 ㅋㅋ/ㅎㅎ 등장 비율: ${(profile.laughter_ratio * 100).toFixed(0)}%`);
  }
  if (profile.banmal_ratio != null) {
    lines.push(`- 실제 반말 비율: ${(profile.banmal_ratio * 100).toFixed(0)}%`);
  }
  if (profile.top_endings && profile.top_endings.length) {
    const top5 = profile.top_endings.slice(0, 5).map((x) => `"${x.e}"(${x.c})`).join(', ');
    lines.push(`- 자주 쓰는 종결어미 top5: ${top5}`);
  }
  if (profile.top_phrases && profile.top_phrases.length) {
    const top5 = profile.top_phrases.slice(0, 5).map((x) => `"${x.p}"(${x.c})`).join(', ');
    lines.push(`- 자주 쓰는 짧은 표현 top5: ${top5}`);
  }

  if (lines.length === 0 && !profile.style_summary) return '';

  const header = '[본인 말투 프로파일 — 이 특징을 답장에 반드시 반영]';
  const statBlock = lines.length ? lines.join('\n') : '';
  const summaryBlock = profile.style_summary
    ? `\n[누적 데이터에서 추출한 말투 요약]\n${profile.style_summary}`
    : '';
  return `\n${header}\n${statBlock}${summaryBlock}\n`;
}
