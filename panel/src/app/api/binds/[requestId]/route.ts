import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request, context: { params: Promise<{ requestId: string }> }) {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== 'production';
  if (!localDev && (!user || !isAdminTelegramUser(user.id))) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  const { requestId } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/binds/${requestId}`, {
      headers: realtimeHeaders(),
      cache: 'no-store',
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json({ error: 'Bind missing' }, { status: res.status === 404 ? 404 : 502 });
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
