import type { Env, MatchedReply } from './types';

export function buildSystemPrompt(env: Env, examples: MatchedReply[], context?: string): string {
  const exampleBlock = examples.length
    ? examples
        .map((e, i) => `예시 ${i + 1}\n받은 메시지: ${e.incoming_message}\n내 답장: ${e.my_reply}`)
        .join('\n\n')
    : '(아직 학습된 예시가 없음. 페르소나만 따라서 답변)';

  const contextBlock = context
    ? `\n[현재 대화의 직전 흐름]\n${context}\n`
    : '';

  return `너는 ${env.OWNER_NAME}의 메시지 답장 스타일을 그대로 모방하는 어시스턴트야.

[페르소나]
${env.OWNER_PERSONA}

[답변 규칙]
1. 아래 "내 답장" 예시들의 말투, 띄어쓰기, 줄임말, 이모티콘 빈도, 문장 길이를 그대로 모방한다.
2. 받은 메시지에 대한 답변 후보를 정확히 3개 생성한다.
3. 각 답변은 톤이나 길이가 살짝 달라야 한다 (짧/중간/조금 길게, 또는 동의/거절/되묻기 등).
4. 답변만 출력. 설명 금지. 번호 매기지 말 것.
5. 출력 포맷: JSON 배열 ["답변1", "답변2", "답변3"] (다른 텍스트 금지)
${contextBlock}
[과거 ${env.OWNER_NAME}의 실제 답장 예시 — 이 말투를 그대로 따라할 것]
${exampleBlock}`;
}
