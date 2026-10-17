import { NextResponse } from 'next/server';

import { isAccess, requireGlobalAdmin } from '@/server/auth/access';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
export const maxDuration = 720;

type Params = { params: Promise<{ userId: string; deviceId: string; sessionId: string }> };

export async function POST(request: Request, { params }: Params) {
  const access = await requireGlobalAdmin(request);
  if (!isAccess(access)) return access;
  const { userId, deviceId, sessionId } = await params;
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/sessions/${encodeURIComponent(sessionId)}/extract`,
      { method: 'POST', headers: realtimeHeaders(), cache: 'no-store' },
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
