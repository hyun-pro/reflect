/**
 * 종단 테스트: 가상의 받은 메시지 → RAG 검색 → Claude 답변 3개 생성
 *
 * Usage:
 *   tsx scripts/test_reply.ts "오늘 저녁 ㄱㄱ?"
 *   tsx scripts/test_reply.ts "내일 회의 몇시였지?" --contact "팀장님" --relationship work
 */

import Anthropic from '@anthropic-ai/sdk';
import { createClient } from '@supabase/supabase-js';
import 'dotenv/config';

const VOYAGE_MODEL = 'voyage-multilingual-2';
const CLAUDE_MODEL = 'claude-haiku-4-5';

const args = process.argv.slice(2);
const incoming = args.find((a) => !a.startsWith('--')) ?? '';
if (!incoming) {
  console.error('Usage: tsx scripts/test_reply.ts "받은 메시지" [--contact 이름] [--relationship friend|family|work]');
  process.exit(1);
}
const contactFlag = args.indexOf('--contact');
const CONTACT = contactFlag >= 0 ? args[contactFlag + 1] : null;
const relFlag = args.indexOf('--relationship');
const RELATIONSHIP = relFlag >= 0 ? args[relFlag + 1] : null;

const SUPABASE_URL = required('SUPABASE_URL');
const SUPABASE_SECRET_KEY = required('SUPABASE_SECRET_KEY');
const VOYAGE_API_KEY = required('VOYAGE_API_KEY');
const ANTHROPIC_API_KEY = required('ANTHROPIC_API_KEY');
const OWNER_NAME = process.env.OWNER_NAME ?? '남현';
const OWNER_PERSONA = process.env.OWNER_PERSONA ?? '캐주얼하고 직설적';

const supabase = createClient(SUPABASE_URL, SUPABASE_SECRET_KEY, {
  auth: { persistSession: false },
});
const anthropic = new Anthropic({ apiKey: ANTHROPIC_API_KEY });

function required(name: string): string {
  const v = process.env[name];
  if (!v) {
    console.error(`❌ .env 에 ${name} 가 비어있습니다.`);
    process.exit(1);
  }
  return v;
}

async function embedQuery(text: string): Promise<number[]> {
  const res = await fetch('https://api.voyageai.com/v1/embeddings', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${VOYAGE_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ model: VOYAGE_MODEL, input: [text], input_type: 'query' }),
  });
  if (!res.ok) throw new Error(`Voyage ${res.status}: ${await res.text()}`);
  const data = (await res.json()) as { data: { embedding: number[] }[] };
  return data.data[0].embedding;
}

interface MatchedReply {
  id: number;
  app: string;
  contact: string | null;
  relationship: string | null;
  incoming_message: string;
  my_reply: string;
  similarity: number;
}

async function retrieveSimilar(query: string, k = 10): Promise<MatchedReply[]> {
  const embedding = await embedQuery(query);
  const { data, error } = await supabase.rpc('match_replies', {
    query_embedding: embedding,
    filter_app: 'kakao',
    filter_contact: CONTACT,
    filter_relationship: RELATIONSHIP,
    match_count: k,
  });
  if (error) throw error;
  return (data as MatchedReply[]) ?? [];
}

function buildSystemPrompt(examples: MatchedReply[]): string {
  const exampleBlock = examples
    .map((e, i) => `예시 ${i + 1}\n받은 메시지: ${e.incoming_message}\n내 답장: ${e.my_reply}`)
    .join('\n\n');

  return `너는 ${OWNER_NAME}의 메시지 답장 스타일을 그대로 모방하는 어시스턴트야.

[페르소나]
${OWNER_PERSONA}

[답변 규칙]
1. 아래 "내 답장" 예시들의 말투, 띄어쓰기, 줄임말, 이모티콘 빈도, 문장 길이를 그대로 모방한다.
2. 받은 메시지에 대한 답변 후보를 정확히 3개 생성한다.
3. 각 답변은 톤이나 길이가 살짝 달라야 한다 (짧/중간/조금 길게, 또는 동의/거절/되묻기 등).
4. 답변만 출력. 설명 금지. 번호 매기지 말 것.
5. 출력 포맷: JSON 배열 ["답변1", "답변2", "답변3"]

[과거 ${OWNER_NAME}의 실제 답장 예시 — 이 말투를 그대로 따라할 것]
${exampleBlock}`;
}

async function generateSuggestions(incoming: string, examples: MatchedReply[]): Promise<string[]> {
  const sys = buildSystemPrompt(examples);
  const msg = await anthropic.messages.create({
    model: CLAUDE_MODEL,
    max_tokens: 512,
    system: sys,
    messages: [{ role: 'user', content: `받은 메시지: ${incoming}` }],
  });
  const text = msg.content
    .filter((b): b is Anthropic.TextBlock => b.type === 'text')
    .map((b) => b.text)
    .join('');

  const match = text.match(/\[[\s\S]*\]/);
  if (!match) {
    console.warn('⚠️ JSON 배열 파싱 실패, 원문:', text);
    return [text];
  }
  try {
    return JSON.parse(match[0]);
  } catch {
    return [text];
  }
}

async function main() {
  console.log(`\n💬 받은 메시지: "${incoming}"`);
  if (CONTACT) console.log(`   상대: ${CONTACT}`);
  if (RELATIONSHIP) console.log(`   관계: ${RELATIONSHIP}`);

  console.log('\n🔎 유사 답장 검색 중...');
  const examples = await retrieveSimilar(incoming, 10);
  console.log(`   ${examples.length}개 매칭 (top-3 미리보기):`);
  for (const e of examples.slice(0, 3)) {
    console.log(`   • ${e.similarity.toFixed(3)} | ${e.incoming_message.slice(0, 30)} → ${e.my_reply.slice(0, 30)}`);
  }

  console.log('\n🤖 Claude로 답변 생성 중...');
  const suggestions = await generateSuggestions(incoming, examples);

  console.log('\n✨ 추천 답변 3개:');
  for (let i = 0; i < suggestions.length; i++) {
    console.log(`   ${i + 1}. ${suggestions[i]}`);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
