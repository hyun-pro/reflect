import { createClient } from '@supabase/supabase-js';
import Anthropic from '@anthropic-ai/sdk';
import type { Env, SuggestRequest, SuggestResponse, MatchedReply } from './types';
import { embed } from './embedding';
import { buildSystemPrompt } from './prompt';

export async function generateSuggestions(env: Env, req: SuggestRequest): Promise<SuggestResponse> {
  const t0 = Date.now();

  const queryEmbedding = await embed(env, req.incoming_message, 'query');

  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });

  const topK = Number(env.RAG_TOP_K) || 10;
  const { data: matches, error } = await supabase.rpc('match_replies', {
    query_embedding: queryEmbedding,
    filter_app: req.app ?? null,
    filter_contact: req.contact ?? null,
    filter_relationship: req.relationship ?? null,
    match_count: topK,
  });
  if (error) throw new Error(`Supabase RPC error: ${error.message}`);

  const examples = (matches as MatchedReply[]) ?? [];
  const systemPrompt = buildSystemPrompt(env, examples, req.conversation_context);

  const anthropic = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
  const msg = await anthropic.messages.create({
    model: env.CLAUDE_MODEL,
    max_tokens: 512,
    system: systemPrompt,
    messages: [{ role: 'user', content: `받은 메시지: ${req.incoming_message}` }],
  });

  const text = msg.content
    .filter((b): b is Anthropic.TextBlock => b.type === 'text')
    .map((b) => b.text)
    .join('');

  let suggestions: string[] = [];
  const arrayMatch = text.match(/\[[\s\S]*\]/);
  if (arrayMatch) {
    try {
      const parsed = JSON.parse(arrayMatch[0]);
      if (Array.isArray(parsed)) suggestions = parsed.map(String);
    } catch {}
  }
  if (suggestions.length === 0) {
    // 폴백: 줄단위 분리
    suggestions = text.split('\n').filter((l) => l.trim()).slice(0, 3);
  }
  // 정확히 3개 보장
  while (suggestions.length < 3) suggestions.push('');
  suggestions = suggestions.slice(0, 3);

  return {
    suggestions,
    matched_count: examples.length,
    latency_ms: Date.now() - t0,
  };
}
