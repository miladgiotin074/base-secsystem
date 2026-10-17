import { readInitDataRaw } from '@/lib/telegram/initDataClient';

export type DeviceCommandResult = {
  ok: boolean;
  rttMs?: number;
  iconState?: string;
  error?: string;
};

export async function sendDeviceCommand(
  userId: string,
  deviceId: string,
  type: 'ping' | 'icon' | 'screenshot',
  mode?: 'normal' | 'hidden' | 'changed',
): Promise<DeviceCommandResult> {
  const initData = readInitDataRaw();
  const res = await fetch(
    `/api/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/command`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(initData ? { 'x-telegram-init-data': initData } : {}),
      },
      body: JSON.stringify(mode ? { type, mode } : { type }),
      signal: AbortSignal.timeout(type === 'screenshot' ? 35_000 : 15_000),
    },
  );
  let body: DeviceCommandResult = { ok: false };
  try {
    body = (await res.json()) as DeviceCommandResult;
  } catch {
    body = { ok: false, error: 'bad response' };
  }
  if (!res.ok) {
    return { ok: false, error: body.error || `http ${res.status}`, rttMs: body.rttMs, iconState: body.iconState };
  }
  return body;
}
