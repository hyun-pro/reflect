import { createClient } from '@supabase/supabase-js';
import type { Env } from './types';

/**
 * Idempotency 캐시 — Supabase suggestions 테이블 활용.
 * 같은 (app, contact, incoming_message) 조합이 짧은 시간 내 다시 들어오면
 * 첫 번째 결과를 그대로 반환 (Voyage/Claude 호출 안 함, 비용 절약 + 일관성).
 */

export interface CachedSuggestion {
  suggestions: string[];
  generated_at: string;
}

const CACHE_TTL_SECONDS = 60 * 10; // 10분

function buildKey(app: string, contact: string | undefined, msg: string): string {
  return `${app}::${contact ?? ''}::${msg}`.toLowerCase();
}

export async function getCachedSuggestion(
  env: Env,
  app: string,
  contact: string | undefined,
  msg: string,
): Promise<string[] | null> {
  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });

  const cutoff = new Date(Date.now() - CACHE_TTL_SECONDS * 1000).toISOString();

  const { data, error } = await supabase
    .from('suggestions')
    .select('suggestions, generated_at')
    .eq('app', app)
    .eq('contact', contact ?? null)
    .eq('incoming_message', msg)
    .gte('generated_at', cutoff)
    .order('generated_at', { ascending: false })
    .limit(1)
    .maybeSingle();

  if (error || !data) return null;
  return (data.suggestions as string[]) ?? null;
}

export async function putCachedSuggestion(
  env: Env,
  app: string,
  contact: string | undefined,
  msg: string,
  suggestions: string[],
): Promise<void> {
  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });

  await supabase.from('suggestions').insert({
    app,
    contact: contact ?? null,
    incoming_message: msg,
    suggestions,
  });
}

export { buildKey };
