import { createClient } from '@supabase/supabase-js';
import type { Env } from './types';

export interface StatsResponse {
  total: number;
  today: number;
  thisWeek: number;
}

export async function fetchStats(env: Env): Promise<StatsResponse> {
  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });

  const now = new Date();
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).toISOString();
  const weekStart = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000).toISOString();

  const [total, today, thisWeek] = await Promise.all([
    supabase.from('replies').select('*', { count: 'exact', head: true }),
    supabase.from('replies').select('*', { count: 'exact', head: true }).gte('created_at', todayStart),
    supabase.from('replies').select('*', { count: 'exact', head: true }).gte('created_at', weekStart),
  ]);

  return {
    total: total.count ?? 0,
    today: today.count ?? 0,
    thisWeek: thisWeek.count ?? 0,
  };
}
