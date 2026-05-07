/**
 * Claude/OpenAI 답변 후처리 — 길이 정규화, 중복 제거, 금지 표현 제거.
 */

const SAFE_FALLBACK_SHORT = '응';
const SAFE_FALLBACK_MID = '알겠어 잠깐만';
const SAFE_FALLBACK_LONG = '응 알겠어 좀 더 생각해보고 다시 말할게';

export function postprocess(raw: string[]): string[] {
  // 1. 트림 + 빈 거 제거
  let cleaned = raw
    .map((s) => (s ?? '').trim())
    .filter((s) => s.length > 0);

  // 2. "사" 끝말 자동 제거
  cleaned = cleaned.map((s) => removeSaSuffix(s));

  // 3. 너무 길면 자르기 (200자)
  cleaned = cleaned.map((s) => (s.length > 200 ? s.slice(0, 200) : s));

  // 4. 중복 제거 (정확히 같거나 매우 유사)
  const dedup: string[] = [];
  for (const s of cleaned) {
    if (!dedup.some((existing) => similar(s, existing))) dedup.push(s);
  }

  // 5. 길이 다양성 보장 — [짧, 중, 긴] 순으로 정렬
  const sorted = [...dedup].sort((a, b) => a.length - b.length);
  const result: string[] = [];
  if (sorted.length > 0) result.push(sorted[0]);
  if (sorted.length > 1) result.push(sorted[Math.floor(sorted.length / 2)]);
  if (sorted.length > 2) result.push(sorted[sorted.length - 1]);

  // 6. 부족하면 안전 폴백 채우기
  while (result.length < 3) {
    const fallback = result.length === 0 ? SAFE_FALLBACK_SHORT
      : result.length === 1 ? SAFE_FALLBACK_MID : SAFE_FALLBACK_LONG;
    if (!result.includes(fallback)) result.push(fallback);
    else result.push(`(준비 중)`);
  }

  return result.slice(0, 3);
}

function removeSaSuffix(s: string): string {
  // "갈게사", "콜사" 같은 어미 제거 (공백 또는 끝)
  return s
    .replace(/([가-힣ㄱ-ㅎ])사(\s|$)/g, '$1$2')
    .replace(/([가-힣ㄱ-ㅎ])사([!?,.])/g, '$1$2')
    .trim();
}

function similar(a: string, b: string): boolean {
  if (a === b) return true;
  if (a.length === 0 || b.length === 0) return false;
  // 짧으면 정확 일치만
  if (Math.min(a.length, b.length) < 5) return a === b;
  // 길면 normalized levenshtein < 0.3 면 유사
  const dist = levenshtein(a.toLowerCase(), b.toLowerCase());
  return dist / Math.max(a.length, b.length) < 0.3;
}

function levenshtein(a: string, b: string): number {
  const m = a.length, n = b.length;
  if (m === 0) return n;
  if (n === 0) return m;
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 0; i <= m; i++) dp[i][0] = i;
  for (let j = 0; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      dp[i][j] = Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost);
    }
  }
  return dp[m][n];
}
