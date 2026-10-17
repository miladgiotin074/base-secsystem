import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

type Context = { params: Promise<{ code: string }> };

export async function GET(request: Request, context: Context) {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== 'production';
  if (!localDev && (!user || !isAdminTelegramUser(user.id))) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  const { code } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/${code}/photo`, {
      headers: realtimeHeaders(),
      cache: 'no-store',
    });
    if (!res.ok || !res.body) {
      return NextResponse.json({ error: 'Photo unavailable' }, { status: res.status === 404 ? 404 : 502 });
    }
    return new NextResponse(res.body, {
      headers: {
        'Content-Type': res.headers.get('content-type') || 'image/jpeg',
        'Cache-Control': 'private, max-age=86400',
      },
    });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
