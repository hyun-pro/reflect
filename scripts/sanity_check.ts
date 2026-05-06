/**
 * 모든 외부 API 연결 검증
 *
 * Usage: tsx scripts/sanity_check.ts
 */

import Anthropic from '@anthropic-ai/sdk';
import { createClient } from '@supabase/supabase-js';
import 'dotenv/config';

const checks: { name: string; ok: boolean; detail: string }[] = [];

async function check(name: string, fn: () => Promise<string>) {
  try {
    const detail = await fn();
    checks.push({ name, ok: true, detail });
    console.log(`✅ ${name}: ${detail}`);
  } catch (e: any) {
    checks.push({ name, ok: false, detail: e?.message ?? String(e) });
    console.log(`❌ ${name}: ${e?.message ?? e}`);
  }
}

async function main() {
  console.log('🔎 외부 API 연결 검증 시작\n');

  await check('Supabase 연결', async () => {
    const url = process.env.SUPABASE_URL!;
    const key = process.env.SUPABASE_SECRET_KEY!;
    const supabase = createClient(url, key, { auth: { persistSession: false } });
    const { count, error } = await supabase
      .from('replies')
      .select('*', { count: 'exact', head: true });
    if (error) throw error;
    return `replies 테이블 OK (현재 ${count ?? 0}개 행)`;
  });

  await check('Voyage 임베딩', async () => {
    const res = await fetch('https://api.voyageai.com/v1/embeddings', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${process.env.VOYAGE_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: 'voyage-multilingual-2',
        input: ['안녕 잘 지내?'],
        input_type: 'query',
      }),
    });
    if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
    const data = (await res.json()) as { data: { embedding: number[] }[] };
    return `${data.data[0].embedding.length}차원 임베딩 생성됨`;
  });

  await check('Anthropic Claude', async () => {
    const anthropic = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });
    const msg = await anthropic.messages.create({
      model: 'claude-haiku-4-5',
      max_tokens: 32,
      messages: [{ role: 'user', content: '한 글자만 답해: 응' }],
    });
    const text = msg.content
      .filter((b): b is Anthropic.TextBlock => b.type === 'text')
      .map((b) => b.text)
      .join('');
    return `응답 받음: "${text.trim()}"`;
  });

  await check('Supabase RPC (match_replies)', async () => {
    const url = process.env.SUPABASE_URL!;
    const key = process.env.SUPABASE_SECRET_KEY!;
    const supabase = createClient(url, key, { auth: { persistSession: false } });
    // 더미 1024차원 zero vector로 RPC가 호출 가능한지만 검증
    const dummy = new Array(1024).fill(0);
    const { error } = await supabase.rpc('match_replies', {
      query_embedding: dummy,
      match_count: 1,
    });
    if (error) throw error;
    return 'RPC 호출 OK';
  });

  console.log('\n--- 결과 ---');
  const failed = checks.filter((c) => !c.ok);
  if (failed.length === 0) {
    console.log('🎉 모든 검증 통과. 카톡 데이터만 있으면 바로 시작 가능.');
  } else {
    console.log(`⚠️  ${failed.length}개 실패:`);
    for (const f of failed) console.log(`   - ${f.name}: ${f.detail}`);
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
