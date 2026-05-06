import { createClient } from '@supabase/supabase-js';
import type { Env, IngestRequest } from './types';
import { embed } from './embedding';

export async function ingestReply(env: Env, req: IngestRequest): Promise<{ id: number }> {
  if (!req.incoming_message || !req.my_reply) {
    throw new Error('incoming_message and my_reply are required');
  }

  const embedding = await embed(env, req.incoming_message, 'document');

  const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SECRET_KEY, {
    auth: { persistSession: false },
  });

  const { data, error } = await supabase
    .from('replies')
    .insert({
      app: req.app,
      contact: req.contact ?? null,
      relationship: req.relationship ?? null,
      incoming_message: req.incoming_message,
      my_reply: req.my_reply,
      conversation_context: req.conversation_context ?? null,
      embedding,
    })
    .select('id')
    .single();

  if (error) throw new Error(`Supabase insert error: ${error.message}`);
  return { id: data!.id };
}
