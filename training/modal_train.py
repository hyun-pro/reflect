"""Reflect — LoRA + DPO fine-tuning pipeline on Modal.

Triggered by the Cloudflare Worker (POST to MODAL_TRIGGER_URL) when:
  - replies pair_count >= TRAIN_MIN_PAIRS (default 1000) AND
  - pair_count grew by TRAIN_DELTA_PAIRS (default 500) since last successful run.

Flow:
  1. Pull replies + dpo_pairs from Supabase via service_role.
  2. Split 90/10 train/holdout.
  3. SFT LoRA on (incoming, reply) pairs using Qwen 2.5 7B-Instruct.
  4. DPO on (incoming, rejected, chosen) using the SFT adapter as reference.
  5. Eval holdout: style similarity (sentence embeddings) + ROUGE-L.
  6. Push merged adapter to HuggingFace Hub (private repo).
  7. POST to TOGETHER_API /v1/fine-tunes to register the adapter for serverless inference.
  8. Callback to Worker: PATCH training_runs row + flip active_adapter.

Run locally to test the data prep only:
    python modal_train.py --dry-run --run-id 1

Deploy:
    modal deploy modal_train.py

Trigger URL (web endpoint) is what you put in Worker's MODAL_TRIGGER_URL secret.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

import modal

APP_NAME = "reflect-train"

# Modal image — pinned versions. Adjust as needed.
image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install(
        "torch==2.4.0",
        "transformers==4.46.2",
        "peft==0.13.2",
        "trl==0.12.0",
        "datasets==3.1.0",
        "accelerate==1.1.1",
        "bitsandbytes==0.44.1",
        "sentence-transformers==3.3.1",
        "rouge-score==0.1.2",
        "supabase==2.10.0",
        "huggingface-hub==0.26.2",
        "httpx==0.27.2",
    )
)

app = modal.App(APP_NAME, image=image)

# Secrets — set via `modal secret create reflect-secrets ...`
SECRET = modal.Secret.from_name("reflect-secrets")
# Required keys in reflect-secrets:
#   SUPABASE_URL, SUPABASE_SECRET_KEY
#   WORKER_URL, WORKER_API_KEY        (callback)
#   HF_TOKEN                          (push adapter to hub)
#   TOGETHER_API_KEY                  (register adapter for serverless)
#   HF_HUB_REPO_PREFIX                (e.g. "hyun-pro/reflect-adapter")


@dataclass
class HParams:
    base_model: str = "Qwen/Qwen2.5-7B-Instruct"
    lora_r: int = 16
    lora_alpha: int = 32
    lora_dropout: float = 0.05
    lr: float = 2e-4
    epochs: int = 3
    batch_size: int = 4
    grad_accum: int = 4
    max_seq_len: int = 1024
    dpo: bool = True
    dpo_beta: float = 0.1
    holdout_ratio: float = 0.1
    seed: int = 42


def _fetch_data(supabase_url: str, supabase_key: str):
    from supabase import create_client

    sb = create_client(supabase_url, supabase_key)
    replies = (
        sb.table("replies")
        .select("id, incoming_message, my_reply, contact, relationship, conversation_context")
        .order("created_at", desc=False)
        .limit(50000)
        .execute()
        .data
    )
    dpo = (
        sb.table("dpo_pairs")
        .select(
            "id, incoming_message, rejected_suggestions, chosen_reply, "
            "contact, relationship, conversation_context"
        )
        .is_("consumed_by", "null")
        .order("created_at", desc=False)
        .limit(50000)
        .execute()
        .data
    )
    return replies, dpo


def _format_chat(base_model_id: str, incoming: str, reply: str | None) -> str:
    # Qwen2.5 chat template
    user_block = f"받은 메시지: {incoming}\n\n이 메시지에 대한 내 자연스러운 답장만 한 줄로 출력해."
    if reply is None:
        return (
            f"<|im_start|>user\n{user_block}<|im_end|>\n<|im_start|>assistant\n"
        )
    return (
        f"<|im_start|>user\n{user_block}<|im_end|>\n"
        f"<|im_start|>assistant\n{reply}<|im_end|>\n"
    )


def _sft_dataset(replies: list[dict], hp: HParams):
    from datasets import Dataset
    samples = []
    for r in replies:
        text = _format_chat(hp.base_model, r["incoming_message"], r["my_reply"])
        samples.append({"text": text})
    return Dataset.from_list(samples)


def _dpo_dataset(dpo_rows: list[dict], hp: HParams):
    from datasets import Dataset
    samples = []
    for r in dpo_rows:
        rejected = r.get("rejected_suggestions") or []
        if not rejected or not r.get("chosen_reply"):
            continue
        prompt = _format_chat(hp.base_model, r["incoming_message"], None)
        rejected_text = rejected[0] if isinstance(rejected, list) else str(rejected)
        samples.append({
            "prompt": prompt,
            "chosen": r["chosen_reply"],
            "rejected": rejected_text,
        })
    return Dataset.from_list(samples)


def _style_similarity(refs: list[str], hyps: list[str]) -> float:
    """Mean cosine similarity between hypothesis and reference embeddings."""
    if not refs or not hyps:
        return 0.0
    from sentence_transformers import SentenceTransformer
    import numpy as np
    model = SentenceTransformer("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    ref_emb = model.encode(refs, normalize_embeddings=True)
    hyp_emb = model.encode(hyps, normalize_embeddings=True)
    sims = (ref_emb * hyp_emb).sum(axis=1)
    return float(np.mean(sims))


def _post_callback(run_id: int, payload: dict[str, Any]) -> None:
    import httpx
    url = os.environ.get("WORKER_URL")
    key = os.environ.get("WORKER_API_KEY")
    if not url or not key:
        print(f"[callback] WORKER_URL/WORKER_API_KEY missing — skipping. payload={payload}")
        return
    body = {"run_id": run_id, **payload}
    try:
        r = httpx.post(
            f"{url.rstrip('/')}/api/training/callback",
            headers={"X-API-Key": key, "Content-Type": "application/json"},
            json=body,
            timeout=30,
        )
        print(f"[callback] {r.status_code} {r.text[:300]}")
    except Exception as e:
        print(f"[callback] failed: {e}")


@app.function(
    gpu="A100-40GB",
    timeout=60 * 60 * 3,  # 3h
    secrets=[SECRET],
    volumes={"/cache": modal.Volume.from_name("reflect-hf-cache", create_if_missing=True)},
)
def train(run_id: int, hparams: dict | None = None) -> dict[str, Any]:
    import random
    import time
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, TrainingArguments
    from peft import LoraConfig, get_peft_model, TaskType
    from trl import SFTTrainer, DPOTrainer, DPOConfig
    from huggingface_hub import HfApi

    os.environ["HF_HOME"] = "/cache"
    os.environ["TRANSFORMERS_CACHE"] = "/cache"

    hp = HParams(**(hparams or {}))
    random.seed(hp.seed)
    t0 = time.time()

    print(f"[train] run_id={run_id} hp={hp}")
    replies, dpo_rows = _fetch_data(
        os.environ["SUPABASE_URL"], os.environ["SUPABASE_SECRET_KEY"]
    )
    print(f"[train] replies={len(replies)} dpo={len(dpo_rows)}")
    if len(replies) < 100:
        msg = f"too few replies ({len(replies)} < 100), aborting"
        _post_callback(run_id, {"status": "failed", "error": msg})
        return {"ok": False, "error": msg}

    # holdout split
    random.shuffle(replies)
    n_holdout = max(20, int(len(replies) * hp.holdout_ratio))
    holdout = replies[:n_holdout]
    train_replies = replies[n_holdout:]

    sft_ds = _sft_dataset(train_replies, hp)
    print(f"[train] sft samples={len(sft_ds)}")

    # Tokenizer + model
    tok = AutoTokenizer.from_pretrained(hp.base_model, trust_remote_code=True)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    model = AutoModelForCausalLM.from_pretrained(
        hp.base_model,
        torch_dtype=torch.bfloat16,
        device_map="auto",
        trust_remote_code=True,
    )
    lora_cfg = LoraConfig(
        r=hp.lora_r,
        lora_alpha=hp.lora_alpha,
        lora_dropout=hp.lora_dropout,
        bias="none",
        task_type=TaskType.CAUSAL_LM,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
    )
    model = get_peft_model(model, lora_cfg)
    model.print_trainable_parameters()

    out_dir = f"/tmp/reflect-{run_id}"
    sft_args = TrainingArguments(
        output_dir=out_dir,
        num_train_epochs=hp.epochs,
        per_device_train_batch_size=hp.batch_size,
        gradient_accumulation_steps=hp.grad_accum,
        learning_rate=hp.lr,
        bf16=True,
        logging_steps=10,
        save_strategy="no",
        report_to=[],
        seed=hp.seed,
    )
    sft = SFTTrainer(
        model=model,
        tokenizer=tok,
        train_dataset=sft_ds,
        args=sft_args,
        dataset_text_field="text",
        max_seq_length=hp.max_seq_len,
    )
    sft.train()

    # DPO 추가 학습 (DPO 페어가 50개 이상일 때만)
    if hp.dpo and len(dpo_rows) >= 50:
        dpo_ds = _dpo_dataset(dpo_rows, hp)
        print(f"[train] dpo samples={len(dpo_ds)}")
        dpo_cfg = DPOConfig(
            output_dir=out_dir + "-dpo",
            num_train_epochs=1,
            per_device_train_batch_size=2,
            gradient_accumulation_steps=hp.grad_accum,
            learning_rate=hp.lr / 4,
            bf16=True,
            beta=hp.dpo_beta,
            max_length=hp.max_seq_len,
            max_prompt_length=hp.max_seq_len // 2,
            logging_steps=10,
            save_strategy="no",
            report_to=[],
            seed=hp.seed,
        )
        dpo_trainer = DPOTrainer(
            model=sft.model,
            ref_model=None,  # peft → uses base model
            args=dpo_cfg,
            train_dataset=dpo_ds,
            tokenizer=tok,
        )
        dpo_trainer.train()
        trained_model = dpo_trainer.model
    else:
        trained_model = sft.model

    # Save adapter
    trained_model.save_pretrained(out_dir + "-final")
    tok.save_pretrained(out_dir + "-final")

    # Eval on holdout
    hyps, refs = [], []
    trained_model.eval()
    for h in holdout[:60]:  # cap eval to keep wallclock reasonable
        prompt = _format_chat(hp.base_model, h["incoming_message"], None)
        inputs = tok(prompt, return_tensors="pt").to(trained_model.device)
        with torch.no_grad():
            out = trained_model.generate(
                **inputs,
                max_new_tokens=200,
                do_sample=True,
                temperature=0.7,
                top_p=0.9,
                pad_token_id=tok.pad_token_id,
            )
        text = tok.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
        hyps.append(text.strip())
        refs.append(h["my_reply"])

    style_score = _style_similarity(refs, hyps)
    print(f"[train] holdout style_similarity={style_score:.4f} on {len(hyps)} samples")

    # Push to HF Hub
    repo_prefix = os.environ.get("HF_HUB_REPO_PREFIX", "hyun-pro/reflect-adapter")
    adapter_repo = f"{repo_prefix}-{run_id}"
    api = HfApi(token=os.environ["HF_TOKEN"])
    api.create_repo(adapter_repo, private=True, exist_ok=True, repo_type="model")
    api.upload_folder(
        folder_path=out_dir + "-final",
        repo_id=adapter_repo,
        repo_type="model",
    )
    print(f"[train] pushed adapter to {adapter_repo}")

    # Register with Together AI (best-effort — API surface evolves)
    together_ft_id = None
    together_key = os.environ.get("TOGETHER_API_KEY")
    if together_key:
        try:
            import httpx
            r = httpx.post(
                "https://api.together.xyz/v1/fine-tunes/adapter",
                headers={"Authorization": f"Bearer {together_key}"},
                json={"adapter_repo": adapter_repo, "base_model": hp.base_model},
                timeout=60,
            )
            print(f"[train] together register {r.status_code} {r.text[:300]}")
            if r.status_code < 300:
                together_ft_id = r.json().get("id")
        except Exception as e:
            print(f"[train] together register failed: {e}")

    payload = {
        "status": "succeeded",
        "adapter_name": adapter_repo,
        "together_ft_id": together_ft_id,
        "modal_run_id": os.environ.get("MODAL_TASK_ID"),
        "eval_holdout_count": len(hyps),
        "eval_score": style_score,
        "eval_details": {
            "wallclock_seconds": int(time.time() - t0),
            "sft_samples": len(sft_ds),
            "dpo_samples": len(dpo_rows) if hp.dpo else 0,
        },
        "consumed_dpo_ids": [r["id"] for r in dpo_rows],
    }
    _post_callback(run_id, payload)
    return {"ok": True, **payload}


@app.function(secrets=[SECRET])
@modal.fastapi_endpoint(method="POST")
def trigger(body: dict[str, Any]) -> dict[str, Any]:
    """HTTP entry point. Worker calls this with {run_id, hparams}.

    Authenticate via Authorization: Bearer $MODAL_TRIGGER_TOKEN.
    """
    # NOTE: Modal's @modal.fastapi_endpoint does not expose headers easily
    # without extra deps; we accept token in body OR rely on Modal's secret
    # being identical to the one we check here. Keep simple: require
    # body["token"] == env MODAL_TRIGGER_TOKEN.
    expected = os.environ.get("MODAL_TRIGGER_TOKEN", "")
    provided = (body or {}).get("token", "")
    if not expected or provided != expected:
        return {"ok": False, "error": "unauthorized"}

    run_id = int(body["run_id"])
    hparams = body.get("hparams")
    train.spawn(run_id, hparams)
    return {"ok": True, "run_id": run_id}
