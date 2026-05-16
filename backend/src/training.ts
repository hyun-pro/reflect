import { createClient } from '@supabase/supabase-js';
import type {
  Env,
  TrainingRun,
  TrainingStatusResponse,
  TrainingTriggerResponse,
} from './types';

const DEFAULT_MIN_PAIRS = 1000;     // 첫 학습 트리거: 페어 1000개 이상
const DEFAULT_DELTA_PAIRS = 500;    // 재학습: 마지막 학습 이후 +500 페어

function thresholds(env: Env): { minPairs: number; deltaPairs: number } {
  return {
    minPairs: Number(env.TRAIN_MIN_PAIRS) || DEFAULT_MIN_PAIRS,
    deltaPairs: Number(env.TRAIN_DELTA_PAIRS) || DEFAULT_DELTA_PAIRS,
  };
}

export async function getTrainingStatus(env: Env): Promise<TrainingStatusResponse> {
  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });
  const { minPairs, deltaPairs } = thresholds(env);

  const [countsRes, activeRes, inFlightRes, latestRes] = await Promise.all([
    supabase.rpc('reflect_counts'),
    supabase.from('active_adapter').select('*').eq('owner', 'self').maybeSingle(),
    supabase
      .from('training_runs')
      .select('*')
      .in('status', ['queued', 'running'])
      .order('started_at', { ascending: false })
      .limit(1)
      .maybeSingle(),
    supabase
      .from('training_runs')
      .select('*')
      .order('started_at', { ascending: false })
      .limit(1)
      .maybeSingle(),
  ]);

  const counts = (countsRes.data?.[0] ?? {
    pair_count: 0,
    dpo_count: 0,
    last_run_pair_count: null,
    last_run_at: null,
  }) as {
    pair_count: number;
    dpo_count: number;
    last_run_pair_count: number | null;
    last_run_at: string | null;
  };

  const pairCount = Number(counts.pair_count);
  const lastRunPairs = counts.last_run_pair_count;

  let nextThreshold = minPairs;
  let readyToTrain = false;
  if (lastRunPairs == null) {
    nextThreshold = minPairs;
    readyToTrain = pairCount >= minPairs;
  } else {
    nextThreshold = lastRunPairs + deltaPairs;
    readyToTrain = pairCount >= nextThreshold;
  }

  return {
    pair_count: pairCount,
    dpo_count: Number(counts.dpo_count),
    last_run_pair_count: lastRunPairs,
    last_run_at: counts.last_run_at,
    active_adapter: (activeRes.data as { adapter_name?: string } | null)?.adapter_name ?? null,
    min_pairs: minPairs,
    delta_pairs: deltaPairs,
    ready_to_train: readyToTrain && !inFlightRes.data,
    next_threshold: nextThreshold,
    training_enabled: Boolean(env.MODAL_TRIGGER_URL && env.MODAL_TRIGGER_TOKEN),
    in_flight: (inFlightRes.data as TrainingRun) ?? null,
    latest: (latestRes.data as TrainingRun) ?? null,
  };
}

/**
 * 학습 잡 트리거. Worker 가 직접 GPU 학습을 못 하므로 Modal endpoint 에 webhook.
 * Modal 잡이 끝나면 별도로 /api/training/callback (있다면) 또는 training_runs 직접 업데이트.
 */
export async function triggerTraining(
  env: Env,
  opts: { force?: boolean } = {}
): Promise<TrainingTriggerResponse> {
  const status = await getTrainingStatus(env);

  if (status.in_flight) {
    return { ok: false, reason: `already running (run_id=${status.in_flight.id})` };
  }
  if (!opts.force && !status.ready_to_train) {
    return {
      ok: false,
      reason: `not ready (pairs=${status.pair_count}/${status.next_threshold})`,
    };
  }

  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });

  const hparams = {
    base_model: 'Qwen/Qwen2.5-7B-Instruct',
    lora_r: 16,
    lora_alpha: 32,
    lora_dropout: 0.05,
    lr: 2e-4,
    epochs: 3,
    batch_size: 4,
    grad_accum: 4,
    max_seq_len: 1024,
    dpo: true,
    dpo_beta: 0.1,
  };

  const { data, error } = await supabase
    .from('training_runs')
    .insert({
      status: 'queued',
      base_model: hparams.base_model,
      pair_count_at_start: status.pair_count,
      dpo_count_at_start: status.dpo_count,
      hparams,
    })
    .select('id')
    .single();
  if (error) throw new Error(`run insert failed: ${error.message}`);

  const runId = (data as { id: number }).id;

  if (env.MODAL_TRIGGER_URL && env.MODAL_TRIGGER_TOKEN) {
    try {
      const res = await fetch(env.MODAL_TRIGGER_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${env.MODAL_TRIGGER_TOKEN}`,
        },
        body: JSON.stringify({
          run_id: runId,
          supabase_url: env.SUPABASE_URL,
          hparams,
        }),
      });
      if (!res.ok) {
        const txt = await res.text();
        await supabase
          .from('training_runs')
          .update({
            status: 'failed',
            error: `modal trigger ${res.status}: ${txt.slice(0, 500)}`,
            finished_at: new Date().toISOString(),
          })
          .eq('id', runId);
        return { ok: false, run_id: runId, reason: `modal trigger failed: ${res.status}` };
      }
    } catch (e: any) {
      await supabase
        .from('training_runs')
        .update({
          status: 'failed',
          error: `modal fetch: ${e?.message ?? String(e)}`,
          finished_at: new Date().toISOString(),
        })
        .eq('id', runId);
      return { ok: false, run_id: runId, reason: `modal trigger threw: ${e?.message}` };
    }
  } else {
    // Modal endpoint 미설정 — run 만 queued 로 남기고 사용자가 수동으로 Modal 실행
    return {
      ok: true,
      run_id: runId,
      reason: 'queued; MODAL_TRIGGER_URL not set, run pipeline manually',
    };
  }

  return { ok: true, run_id: runId };
}

/**
 * Modal 학습 잡이 완료되면 호출되는 webhook.
 * 인증: API_KEY 와 같은 토큰(또는 별도 MODAL_CALLBACK_TOKEN).
 */
export interface TrainingCallbackBody {
  run_id: number;
  status: 'succeeded' | 'failed';
  adapter_name?: string;
  together_ft_id?: string;
  modal_run_id?: string;
  eval_holdout_count?: number;
  eval_score?: number;
  eval_details?: Record<string, unknown>;
  error?: string;
  consumed_pair_ids?: number[];
  consumed_dpo_ids?: number[];
}

export async function trainingCallback(env: Env, body: TrainingCallbackBody): Promise<{ ok: true }> {
  if (!body.run_id) throw new Error('run_id required');
  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });

  const now = new Date().toISOString();
  await supabase
    .from('training_runs')
    .update({
      status: body.status,
      adapter_name: body.adapter_name ?? null,
      together_ft_id: body.together_ft_id ?? null,
      modal_run_id: body.modal_run_id ?? null,
      eval_holdout_count: body.eval_holdout_count ?? null,
      eval_score: body.eval_score ?? null,
      eval_details: body.eval_details ?? null,
      error: body.error ?? null,
      finished_at: now,
    })
    .eq('id', body.run_id);

  // 성공 + 어댑터 있음 → active_adapter 갱신
  if (body.status === 'succeeded' && body.adapter_name) {
    await supabase.from('active_adapter').upsert(
      {
        owner: 'self',
        training_run_id: body.run_id,
        adapter_name: body.adapter_name,
        activated_at: now,
      },
      { onConflict: 'owner' }
    );
  }

  // DPO 페어 소비 마킹
  if (body.consumed_dpo_ids && body.consumed_dpo_ids.length) {
    await supabase
      .from('dpo_pairs')
      .update({ consumed_by: body.run_id })
      .in('id', body.consumed_dpo_ids);
  }

  return { ok: true };
}

export async function getActiveAdapter(env: Env): Promise<string | null> {
  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });
  const { data } = await supabase
    .from('active_adapter')
    .select('adapter_name')
    .eq('owner', 'self')
    .maybeSingle();
  return (data as { adapter_name?: string } | null)?.adapter_name ?? null;
}
