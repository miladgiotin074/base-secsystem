import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData, type TelegramWebAppUser } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export type PanelRole = 'admin' | 'manager' | 'none';

export type PanelAccess = {
  role: PanelRole;
  ports: string[];
  user: TelegramWebAppUser | null;
  localDev: boolean;
};

type AccessBody = {
  manager?: boolean;
  ports?: string[];
  userOk?: boolean;
};

export async function loadManagedPorts(telegramId: number): Promise<string[]> {
  if (!telegramId) return [];
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/panel/access?telegramId=${encodeURIComponent(String(telegramId))}`,
      { headers: realtimeHeaders(), cache: 'no-store' },
    );
    if (!res.ok) return [];
    const body = (await res.json()) as AccessBody;
    return Array.isArray(body.ports) ? body.ports.filter(Boolean) : [];
  } catch {
    return [];
  }
}

export async function resolvePanelAccess(request: Request): Promise<PanelAccess> {
  const localDev = process.env.NODE_ENV !== 'production';
  const user = parseInitData(initDataFromRequest(request));
  if (localDev && !user) {
    return { role: 'admin', ports: [], user: { id: 0, first_name: 'local' }, localDev: true };
  }
  if (!user) {
    return { role: 'none', ports: [], user: null, localDev };
  }
  if (isAdminTelegramUser(user.id)) {
    return { role: 'admin', ports: [], user, localDev };
  }
  const ports = await loadManagedPorts(user.id);
  if (ports.length > 0) {
    return { role: 'manager', ports, user, localDev };
  }
  return { role: 'none', ports: [], user, localDev };
}

export function forbidden(): NextResponse {
  return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
}

/** Global owner or port manager may open the panel. */
export async function requirePanelOpen(request: Request): Promise<PanelAccess | NextResponse> {
  const access = await resolvePanelAccess(request);
  if (access.localDev || access.role === 'admin' || access.role === 'manager') {
    return access;
  }
  return forbidden();
}

/** Only ADMIN_TELEGRAM_ID (port create/bind/settings). */
export async function requireGlobalAdmin(request: Request): Promise<PanelAccess | NextResponse> {
  const access = await resolvePanelAccess(request);
  if (access.localDev || access.role === 'admin') {
    return access;
  }
  return forbidden();
}

export function isAccess(value: PanelAccess | NextResponse): value is PanelAccess {
  return !(value instanceof NextResponse);
}

/** Manager may only touch users installed on their ports. */
export async function canAccessUser(access: PanelAccess, userId: string): Promise<boolean> {
  if (access.localDev || access.role === 'admin') return true;
  if (access.role !== 'manager' || access.ports.length === 0 || !userId) return false;
  const telegramId = access.user?.id || 0;
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/panel/access?telegramId=${encodeURIComponent(String(telegramId))}&userId=${encodeURIComponent(userId)}`,
      { headers: realtimeHeaders(), cache: 'no-store' },
    );
    if (!res.ok) return false;
    const body = (await res.json()) as AccessBody;
    return Boolean(body.userOk);
  } catch {
    return false;
  }
}

export async function requireUserAccess(
  request: Request,
  userId: string,
): Promise<PanelAccess | NextResponse> {
  const access = await requirePanelOpen(request);
  if (!isAccess(access)) return access;
  if (!(await canAccessUser(access, userId))) {
    return forbidden();
  }
  return access;
}

export function filterOverviewItems<T extends { devices?: { port?: string }[] }>(
  items: T[],
  ports: string[],
): T[] {
  if (ports.length === 0) return items;
  const allowed = new Set(ports);
  return items.filter((user) => (user.devices || []).some((d) => d.port && allowed.has(d.port)));
}
