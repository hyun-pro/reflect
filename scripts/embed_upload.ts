/**
 * data/processed/replies.jsonl 을 Voyage 임베딩 → Supabase replies 테이블 업로드
 *
 * Usage:
 *   tsx scripts/embed_upload.ts
 *   tsx scripts/embed_upload.ts --limit 100   # 일부만 테스트
 *   tsx scripts/embed_upload.ts --truncate    # 기존 데이터 삭제 후 새로 업로드
 */

import { readFileSync, existsSync } from 'node:fs';
import { createClient } from '@supabase/supabase-js';
import 'dotenv/config';

const VOYAGE_MODEL = 'voyage-multilingual-2';   // 한국어 강함, 1024차원
const VOYAGE_BATCH = 128;                       // Voyage 단일 요청당 최대 input
const SUPABASE_BATCH = 200;                     // Supabase insert batch

const args = process.argv.slice(2);
const limitFlag = args.indexOf('--limit');
const LIMIT = limitFlag >= 0 ? Number(args[limitFlag + 1]) : Infinity;
const TRUNCATE = args.includes('--truncate');

const SUPABASE_URL = required('SUPABASE_URL');
const SUPABASE_SECRET_KEY = required('SUPABASE_SECRET_KEY');
const VOYAGE_API_KEY = required('VOYAGE_API_KEY');

const supabase = createClient(SUPABASE_URL, SUPABASE_SECRET_KEY, {
  auth: { persistSession: false },
});

interface Pair {
  app: 'kakao';
  contact: string;
  relationship: string | null;
  incoming_message: string;
  my_reply: string;
  conversation_context: string | null;
  reply_at: string | null;
}

function required(name: string): string {
  const v = process.env[name];
  if (!v) {
    console.error(`❌ .env 에 ${name} 가 비어있습니다.`);
    process.exit(1);
  }
  return v;
}

async function embedBatch(texts: string[]): Promise<number[][]> {
  const res = await fetch('https://api.voyageai.com/v1/embeddings', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${VOYAGE_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: VOYAGE_MODEL,
      input: texts,
      input_type: 'document',
    }),
  });
  if (!res.ok) {
    throw new Error(`Voyage ${res.status}: ${await res.text()}`);
  }
  const data = (await res.json()) as { data: { embedding: number[] }[] };
  return data.data.map((d) => d.embedding);
}

async function main() {
  const path = 'data/processed/replies.jsonl';
  if (!existsSync(path)) {
    console.error(`❌ ${path} 없음. 먼저 npm run parse 실행하세요.`);
    process.exit(1);
  }

  const lines = readFileSync(path, 'utf-8').split('\n').filter((l) => l.trim());
  const pairs: Pair[] = lines.map((l) => JSON.parse(l));
  const slice = pairs.slice(0, LIMIT);

  console.log(`📦 페어 ${pairs.length}개 로드, ${slice.length}개 처리 예정`);

  if (TRUNCATE) {
    console.log('🗑️  기존 replies 데이터 삭제...');
    const { error } = await supabase.from('replies').delete().neq('id', 0);
    if (error) {
      console.error('❌ truncate 실패:', error);
      process.exit(1);
    }
  }

  let totalInserted = 0;
  for (let i = 0; i < slice.length; i += VOYAGE_BATCH) {
    const batch = slice.slice(i, i + VOYAGE_BATCH);
    const texts = batch.map((p) => p.incoming_message);

    process.stdout.write(`   [${i + 1}-${Math.min(i + VOYAGE_BATCH, slice.length)}/${slice.length}] 임베딩 중...`);
    let embeddings: number[][];
    try {
      embeddings = await embedBatch(texts);
    } catch (e) {
      console.error(`\n❌ 임베딩 실패 (batch ${i}):`, e);
      console.log('   60초 후 재시도...');
      await new Promise((r) => setTimeout(r, 60_000));
      embeddings = await embedBatch(texts);
    }

    const rows = batch.map((p, idx) => ({ ...p, embedding: embeddings[idx] }));

    for (let j = 0; j < rows.length; j += SUPABASE_BATCH) {
      const chunk = rows.slice(j, j + SUPABASE_BATCH);
      const { error } = await supabase.from('replies').insert(chunk);
      if (error) {
        console.error(`\n❌ Supabase insert 실패:`, error);
        process.exit(1);
      }
      totalInserted += chunk.length;
    }

    process.stdout.write(` ✅\n`);
  }

  console.log(`\n🎉 총 ${totalInserted}개 페어 업로드 완료`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
