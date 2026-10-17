import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function allowed(request: Request): boolean {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== 'production';
  return localDev || Boolean(user && isAdminTelegramUser(user.id));
}

export async function GET(request: Request) {
  if (!allowed(request)) return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  try {
    const res = await fetch(`${realtimeBase()}/v1/ai/prompts`, {
      headers: realtimeHeaders(),
      cache: 'no-store',
    });
    const text = await res.text();
    return new NextResponse(text, {
      status: res.status,
      headers: { 'Content-Type': 'application/json' },
    });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
