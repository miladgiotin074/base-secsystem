import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function allow(request: Request): boolean {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== 'production';
  return localDev || (!!user && isAdminTelegramUser(user.id));
}

type Context = { params: Promise<{ code: string }> };

export async function PATCH(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  const { code } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/${code}`, {
      method: 'PATCH',
      headers: { ...realtimeHeaders(), 'Content-Type': 'application/json' },
      body: await request.text(),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json({ error: 'Port update failed' }, { status: res.status === 404 ? 404 : 502 });
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}

export async function DELETE(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  const { code } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/${code}`, {
      method: 'DELETE',
      headers: realtimeHeaders(),
    });
    if (!res.ok) {
      return NextResponse.json({ error: 'Port delete failed' }, { status: res.status === 404 ? 404 : 502 });
    }
    return NextResponse.json({ ok: true });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
