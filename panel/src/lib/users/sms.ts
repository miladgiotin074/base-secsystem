import { readInitDataRaw } from '@/lib/telegram/initDataClient';

export type SmsThread = {
  threadKey: string;
  address: string;
  snippet: string;
  dateMs: number;
  count: number;
  unread: number;
};

export type SmsMessage = {
  id: string;
  providerId: string;
  threadKey: string;
  address: string;
  body: string;
  dateMs: number;
  type: number;
  read: boolean;
};

function headers(): HeadersInit {
  const initData = readInitDataRaw();
  return {
    'Content-Type': 'application/json',
    ...(initData ? { 'x-telegram-init-data': initData } : {}),
  };
}

function base(userId: string, deviceId: string): string {
  return `/api/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/sms`;
}

export async function fetchSmsThreads(userId: string, deviceId: string): Promise<SmsThread[]> {
  const res = await fetch(`${base(userId, deviceId)}/threads`, { headers: headers(), cache: 'no-store' });
  if (!res.ok) throw new Error('threads');
  const body = (await res.json()) as { items?: SmsThread[] };
  return body.items ?? [];
}

export async function fetchSmsMessages(userId: string, deviceId: string, threadKey: string): Promise<SmsMessage[]> {
  const res = await fetch(`${base(userId, deviceId)}/threads/${encodeURIComponent(threadKey)}`, {
    headers: headers(),
    cache: 'no-store',
  });
  if (!res.ok) throw new Error('messages');
  const body = (await res.json()) as { items?: SmsMessage[] };
  return body.items ?? [];
}

export async function syncSms(userId: string, deviceId: string): Promise<{ ok: boolean; count?: number; error?: string }> {
  const res = await fetch(`${base(userId, deviceId)}/sync`, {
    method: 'POST',
    headers: headers(),
    body: '{}',
  });
  const body = (await res.json().catch(() => ({}))) as { ok?: boolean; count?: number; error?: string };
  if (!res.ok) return { ok: false, error: body.error || 'sync failed' };
  return { ok: Boolean(body.ok), count: body.count, error: body.error };
}

export async function sendSms(
  userId: string,
  deviceId: string,
  to: string,
  bodyText: string,
): Promise<{ ok: boolean; error?: string }> {
  const res = await fetch(`${base(userId, deviceId)}/send`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ to, body: bodyText }),
  });
  const body = (await res.json().catch(() => ({}))) as { ok?: boolean; error?: string };
  if (!res.ok) return { ok: false, error: body.error || 'send failed' };
  return { ok: Boolean(body.ok), error: body.error };
}

export async function controlSmsDefault(
  userId: string,
  deviceId: string,
  action: 'claim' | 'restore',
): Promise<{ ok: boolean; error?: string }> {
  const res = await fetch(`${base(userId, deviceId)}/default`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ action }),
    signal: AbortSignal.timeout(125_000),
  });
  const body = (await res.json().catch(() => ({}))) as { ok?: boolean; error?: string };
  if (!res.ok) return { ok: false, error: body.error || 'sms default failed' };
  return { ok: Boolean(body.ok), error: body.error };
}

export async function controlOffmode(
  userId: string,
  deviceId: string,
  on: boolean,
  to: string,
  filter: 'personal' | 'nonpersonal' | 'all' = 'nonpersonal',
): Promise<{ ok: boolean; error?: string }> {
  const res = await fetch(
    `/api/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/offmode`,
    {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ on, to, filter }),
      signal: AbortSignal.timeout(35_000),
    },
  );
  const body = (await res.json().catch(() => ({}))) as { ok?: boolean; error?: string };
  if (!res.ok) return { ok: false, error: body.error || 'offmode failed' };
  return { ok: Boolean(body.ok), error: body.error };
}
