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
      `${realtimeBase()}/v1/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/sms/threads`,
      { headers: realtimeHeaders(), cache: 'no-store' },
    );
    const text = await res.text();
    return new NextResponse(text, {
      status: res.status,
      headers: { 'Content-Type': 'application/json' },
    });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
