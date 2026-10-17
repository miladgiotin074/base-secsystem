import { readInitDataRaw } from '@/lib/telegram/initDataClient';

export type UssdLogEntry = {
  code: string;
  slot: number;
  ok: boolean;
  text?: string;
  error?: string;
  createdAt: number;
};

export type UssdCodeEntry = {
  code: string;
  count: number;
  slot: number;
  lastUsedAt: number;
};

export type UssdBoard = {
  items: UssdLogEntry[];
  codes: UssdCodeEntry[];
};

function headers(): HeadersInit {
  const initData = readInitDataRaw();
  return {
    'Content-Type': 'application/json',
    ...(initData ? { 'x-telegram-init-data': initData } : {}),
  };
}

function base(userId: string, deviceId: string): string {
  return `/api/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/ussd`;
}

export async function fetchUssdHistory(userId: string, deviceId: string): Promise<UssdBoard> {
  const res = await fetch(base(userId, deviceId), { headers: headers(), cache: 'no-store' });
  if (!res.ok) throw new Error('ussd history');
  const body = (await res.json()) as { items?: UssdLogEntry[]; codes?: UssdCodeEntry[] };
  return { items: body.items ?? [], codes: body.codes ?? [] };
}

export async function sendUssd(
  userId: string,
  deviceId: string,
  code: string,
  slot: number,
): Promise<{ ok: boolean; text?: string; error?: string }> {
  const res = await fetch(base(userId, deviceId), {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ code, slot }),
  });
  const body = (await res.json().catch(() => ({}))) as { ok?: boolean; text?: string; error?: string };
  if (!res.ok) return { ok: false, error: body.error || 'ussd failed' };
  return { ok: Boolean(body.ok), text: body.text, error: body.error };
}
