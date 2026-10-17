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

export async function POST(request: Request) {
  if (!allow(request)) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  let body: { code?: string };
  try {
    body = (await request.json()) as { code?: string };
  } catch {
    return NextResponse.json({ error: 'Invalid body' }, { status: 400 });
  }
  const code = (body.code || '').trim();
  if (!/^[A-Za-z0-9]{20}$/.test(code)) {
    return NextResponse.json({ error: 'Invalid port' }, { status: 400 });
  }
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/${code}/unbind`, {
      method: 'POST',
      headers: realtimeHeaders(),
    });
    if (res.status === 404) {
      return NextResponse.json({ error: 'Port not found' }, { status: 404 });
    }
    if (!res.ok) {
      return NextResponse.json({ error: 'Unbind failed' }, { status: 502 });
    }
    return NextResponse.json(await res.json());
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
