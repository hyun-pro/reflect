# Reflect — Fine-tuning pipeline

자율 LoRA + DPO 학습. 페어 1000개 이상 + 마지막 학습 이후 +500 누적되면 Worker가 트리거.

## 일회성 셋업 (사용자 손이 가는 부분)

1. **HuggingFace 계정 + 토큰** — write 권한
   ```bash
   # https://huggingface.co/settings/tokens 에서 'write' 토큰 발급
   ```

2. **Together AI 계정 + API 키**
   ```bash
   # https://api.together.xyz/settings/api-keys
   ```

3. **Modal 계정 + CLI**
   ```bash
   pip install modal
   modal token new
   ```

4. **Modal 시크릿 등록**
   ```bash
   modal secret create reflect-secrets \
     SUPABASE_URL=https://xxx.supabase.co \
     SUPABASE_SECRET_KEY=eyJ... \
     WORKER_URL=https://reflect-backend.<your>.workers.dev \
     WORKER_API_KEY=<same as backend API_KEY> \
     HF_TOKEN=hf_... \
     HF_HUB_REPO_PREFIX=hyun-pro/reflect-adapter \
     TOGETHER_API_KEY=... \
     MODAL_TRIGGER_TOKEN=<random 32 byte hex>
   ```

5. **배포**
   ```bash
   modal deploy modal_train.py
   ```
   배포 후 `trigger` endpoint URL 출력됨. 그걸 Worker `MODAL_TRIGGER_URL` 시크릿에 넣음:
   ```bash
   cd ../backend
   wrangler secret put MODAL_TRIGGER_URL
   wrangler secret put MODAL_TRIGGER_TOKEN
   ```

## 수동 학습 트리거 (테스트용)

```bash
curl -X POST https://<worker>/api/training/trigger \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"force": true}'
```

응답에 `run_id` 반환. Modal 대시보드에서 잡 진행 확인.

## 학습 흐름

1. Worker `/api/training/trigger` → `training_runs` row 생성 → Modal trigger
2. Modal: Supabase에서 `replies` + `dpo_pairs` 가져옴, 90/10 split
3. SFT LoRA (Qwen 2.5 7B-Instruct) on (incoming, reply) pairs
4. DPO (pairs ≥ 50일 때) on (chosen, rejected) — 가장 강력한 톤 학습 신호
5. holdout 60샘플 평가 — multilingual sentence transformer로 style similarity 계산
6. 어댑터 → HuggingFace private repo 푸시
7. Together AI에 어댑터 등록 (서버리스 추론용)
8. Worker `/api/training/callback` 호출 → `training_runs` 갱신 + `active_adapter` 플립
9. 다음 `/api/suggest` 호출부터 fine-tune 모델 사용 (Claude는 critic으로 강등 — TODO)

## 비용 (대략)

- Modal A100 40GB: 학습 1회 30~60분, $5~10
- Together AI 서버리스 추론: 토큰당, 메시지 100건/일 가정 시 월 $2~5
- HuggingFace private repo: 무료

## 로컬 데이터 확인 (dry-run)

학습 없이 데이터만 확인:
```bash
cd training
pip install supabase
SUPABASE_URL=... SUPABASE_SECRET_KEY=... python -c "
from modal_train import _fetch_data
r, d = _fetch_data('${SUPABASE_URL}', '${SUPABASE_SECRET_KEY}')
print(f'replies: {len(r)}, dpo: {len(d)}')
print(f'sample reply: {r[0] if r else None}')"
```
