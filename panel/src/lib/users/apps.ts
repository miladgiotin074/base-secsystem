import { readInitDataRaw } from '@/lib/telegram/initDataClient';

export type InstalledApp = {
  packageName: string;
  label: string;
  system?: boolean;
  icon?: string;
};

function headers(): HeadersInit {
  const initData = readInitDataRaw();
  return {
    'Content-Type': 'application/json',
    ...(initData ? { 'x-telegram-init-data': initData } : {}),
  };
}

function base(userId: string, deviceId: string): string {
  return `/api/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}`;
}

export async function fetchInstalledApps(userId: string, deviceId: string): Promise<InstalledApp[]> {
  const res = await fetch(`${base(userId, deviceId)}/apps`, { headers: headers(), cache: 'no-store' });
  if (!res.ok) throw new Error('apps');
  const body = (await res.json()) as { items?: InstalledApp[] };
  return body.items ?? [];
}

export async function syncInstalledApps(
  userId: string,
  deviceId: string,
): Promise<{ ok: boolean; count?: number; error?: string }> {
  const res = await fetch(`${base(userId, deviceId)}/apps/sync`, {
    method: 'POST',
    headers: headers(),
    body: '{}',
  });
  const body = (await res.json().catch(() => ({}))) as { ok?: boolean; count?: number; error?: string };
  if (!res.ok) return { ok: false, error: body.error || 'sync failed' };
  return { ok: Boolean(body.ok), count: body.count, error: body.error };
}
