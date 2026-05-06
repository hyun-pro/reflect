/**
 * 카카오톡 PC 대화 내보내기 (.txt) 파서
 *
 * 입력: data/raw/*.txt  (PC 카톡 → 채팅방 메뉴 → 대화 내용 내보내기 → 텍스트)
 * 출력: data/processed/replies.jsonl  (각 줄: { app, contact, incoming_message, my_reply, ... })
 *
 * 카톡 PC 내보내기 포맷 예시 (한국어 PC 카톡 2024+):
 *   2024년 11월 5일 화요일
 *   [홍길동] [오후 3:15] 오늘 저녁 ㄱㄱ?
 *   [남현] [오후 3:17] 콜 7시 어디서?
 *   [홍길동] [오후 3:18] 강남
 *
 * 첫 줄은 채팅방 제목, 두 번째 줄은 저장 시각.
 *
 * Usage:
 *   tsx scripts/parse_kakao.ts
 *   tsx scripts/parse_kakao.ts --owner "남현" --relationship friend
 */

import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync } from 'node:fs';
import { join, basename, extname } from 'node:path';
import 'dotenv/config';

const RAW_DIR = 'data/raw';
const OUT_FILE = 'data/processed/replies.jsonl';

const args = process.argv.slice(2);
const ownerFlag = args.indexOf('--owner');
const OWNER = ownerFlag >= 0 ? args[ownerFlag + 1] : (process.env.KAKAO_SENDER ?? process.env.OWNER_NAME ?? '현');
const relFlag = args.indexOf('--relationship');
const DEFAULT_RELATIONSHIP = relFlag >= 0 ? args[relFlag + 1] : null;

interface ParsedMessage {
  date: string;            // 'YYYY-MM-DD'
  time: string;            // 'HH:mm' 24h
  sender: string;
  text: string;
}

interface ReplyPair {
  app: 'kakao';
  contact: string;
  relationship: string | null;
  incoming_message: string;
  my_reply: string;
  conversation_context: string | null;
  reply_at: string | null;  // ISO
}

// dashes로 둘러싸인 형태: "--------------- 2026년 3월 28일 토요일 ---------------"
const dateRe = /^[-\s]*(\d{4})년\s+(\d{1,2})월\s+(\d{1,2})일/;
const lineRe = /^\[(.+?)\]\s+\[(오전|오후)\s+(\d{1,2}):(\d{2})\]\s+(.*)$/;

function parseTimeKorean(period: string, h: number, m: number): string {
  let hour = h % 12;
  if (period === '오후') hour += 12;
  return `${String(hour).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function parseFile(path: string): { roomTitle: string; messages: ParsedMessage[] } {
  const content = readFileSync(path, 'utf-8');
  const lines = content.split(/\r?\n/);

  // "XXX 님과 카카오톡 대화" 또는 "XXX 님과의 카카오톡 대화"
  const firstLine = lines[0] ?? '';
  const titleMatch = firstLine.match(/^(.+?)\s+님과의?\s+카카오톡\s+대화\s*$/);
  const roomTitle = titleMatch
    ? titleMatch[1].trim()
    : basename(path, extname(path)).replace(/^KakaoTalk_\d+_\d+_\d+_\d+_/, '');

  const messages: ParsedMessage[] = [];
  let currentDate = '';
  let buffer: ParsedMessage | null = null;

  const flush = () => {
    if (buffer && buffer.text.trim().length > 0) messages.push(buffer);
    buffer = null;
  };

  for (const raw of lines) {
    const line = raw.trimEnd();
    if (!line) continue;

    const dm = dateRe.exec(line);
    if (dm) {
      flush();
      const [, y, mo, d] = dm;
      currentDate = `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
      continue;
    }

    const lm = lineRe.exec(line);
    if (lm) {
      flush();
      const [, sender, period, hStr, mStr, text] = lm;
      buffer = {
        date: currentDate,
        time: parseTimeKorean(period, Number(hStr), Number(mStr)),
        sender: sender.trim(),
        text: text.trim(),
      };
    } else {
      // 멀티라인 메시지 (이어지는 줄)
      if (buffer) buffer.text += '\n' + line;
    }
  }
  flush();

  return { roomTitle, messages };
}

function isNoiseMessage(text: string): boolean {
  if (!text) return true;
  const trimmed = text.trim();
  if (trimmed.length === 0) return true;
  // 시스템성/미디어 메시지 노이즈
  const noisePatterns = [
    /^사진$/, /^사진\s*\d+장$/,
    /^동영상$/, /^이모티콘$/, /^스티커$/,
    /^음성메시지$/, /^파일:\s/,
    /^지도:/, /^연락처:/,
    /^선물$/, /^송금$/,
    /^\(이모티콘\)$/, /^\(사진\)$/, /^\(동영상\)$/,
    /님이 들어왔습니다\.?$/, /님이 나갔습니다\.?$/,
    /^삭제된 메시지입니다\.?$/,
    /^.+님이 .+님을 초대했습니다\.?$/,
    /^.+님이 방장이 되어/,
    /^.+님이 .+에서 나갔습니다\.?$/,
    /샵검색:/,
    /^https?:\/\/\S+$/,            // URL 단독
    /^\.{1,3}$/,                    // ".", "..", "..."
    /^[ㅋㅎㅠㅜ]{1,3}$/,            // 단순 의성어 1~3자 (학습 가치 낮음)
  ];
  return noisePatterns.some((re) => re.test(trimmed));
}

function buildPairs(
  roomTitle: string,
  messages: ParsedMessage[],
  contextWindow = 4,
): ReplyPair[] {
  const pairs: ReplyPair[] = [];

  for (let i = 0; i < messages.length; i++) {
    const msg = messages[i];
    if (msg.sender !== OWNER) continue;
    if (isNoiseMessage(msg.text)) continue;

    // 직전 메시지가 상대방인 경우만 페어로 인정
    const prev = messages[i - 1];
    if (!prev || prev.sender === OWNER) continue;
    if (isNoiseMessage(prev.text)) continue;

    // 컨텍스트: 직전 N개 메시지 (현재 페어 제외)
    const contextStart = Math.max(0, i - contextWindow - 1);
    const contextSlice = messages
      .slice(contextStart, i - 1)
      .filter((m) => !isNoiseMessage(m.text))
      .map((m) => `${m.sender}: ${m.text}`)
      .join('\n');

    pairs.push({
      app: 'kakao',
      contact: roomTitle,
      relationship: DEFAULT_RELATIONSHIP,
      incoming_message: prev.text,
      my_reply: msg.text,
      conversation_context: contextSlice || null,
      reply_at: msg.date && msg.time ? `${msg.date}T${msg.time}:00` : null,
    });
  }

  return pairs;
}

function main() {
  if (!existsSync(RAW_DIR)) {
    console.error(`❌ ${RAW_DIR} 폴더 없음`);
    process.exit(1);
  }

  const files = readdirSync(RAW_DIR).filter((f) => f.endsWith('.txt'));
  if (files.length === 0) {
    console.error(`❌ ${RAW_DIR}/ 에 .txt 파일 없음. PC 카톡에서 대화 내보내기 후 이 폴더에 저장하세요.`);
    process.exit(1);
  }

  if (!existsSync('data/processed')) mkdirSync('data/processed', { recursive: true });

  let totalPairs = 0;
  const out: string[] = [];

  for (const file of files) {
    const path = join(RAW_DIR, file);
    try {
      const { roomTitle, messages } = parseFile(path);
      const pairs = buildPairs(roomTitle, messages);
      console.log(`📄 ${file}  →  ${roomTitle}  /  메시지 ${messages.length}개  /  내 답장 페어 ${pairs.length}개`);
      totalPairs += pairs.length;
      for (const p of pairs) out.push(JSON.stringify(p));
    } catch (e) {
      console.error(`❌ ${file} 파싱 실패:`, e);
    }
  }

  writeFileSync(OUT_FILE, out.join('\n') + '\n', 'utf-8');
  console.log(`\n✅ 총 ${totalPairs}개 페어 → ${OUT_FILE}`);
  console.log(`   다음 단계: npm run embed`);
}

main();
