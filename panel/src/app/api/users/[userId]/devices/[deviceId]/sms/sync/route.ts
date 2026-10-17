import { NextResponse } from 'next/server';

import { isAccess, requireUserAccess } from '@/server/auth/access';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

type Params = { params: Promise<{ userId: string; deviceId: string }> };

export async function POST(request: Request, { params }: Params) {
  const { userId, deviceId } = await params;
  const access = await requireUserAccess(request, userId);
  if (!isAccess(access)) return access;
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/sms/sync`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...realtimeHeaders() },
        body: '{}',
        signal: AbortSignal.timeout(50_000),
      },
    );
    const text = await res.text();
    return new NextResponse(text, {
      status: res.status,
      headers: { 'Content-Type': 'application/json' },
    });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable', ok: false }, { status: 502 });
  }
}
