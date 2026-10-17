import { NextResponse } from 'next/server';

import { isAccess, requireUserAccess } from '@/server/auth/access';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

type Params = { params: Promise<{ userId: string; deviceId: string }> };

export async function GET(request: Request, { params }: Params) {
  const { userId, deviceId } = await params;
  const access = await requireUserAccess(request, userId);
  if (!isAccess(access)) return access;
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/screenshot`,
      { headers: realtimeHeaders(), cache: 'no-store' },
    );
    if (!res.ok) {
      return NextResponse.json({ error: 'not found' }, { status: res.status === 404 ? 404 : 502 });
    }
    const buf = await res.arrayBuffer();
    const type = res.headers.get('content-type') || 'image/jpeg';
    return new NextResponse(buf, {
      status: 200,
      headers: {
        'Content-Type': type,
        'Cache-Control': 'no-store',
      },
    });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
