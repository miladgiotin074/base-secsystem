import { NextResponse } from 'next/server';

import { isAccess, requireGlobalAdmin } from '@/server/auth/access';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

type Params = { params: Promise<{ userId: string; deviceId: string }> };

export async function POST(request: Request, { params }: Params) {
  const access = await requireGlobalAdmin(request);
  if (!isAccess(access)) return access;
  const { userId, deviceId } = await params;
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Invalid body' }, { status: 400 });
  }
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/sms/default`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...realtimeHeaders() },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(125_000),
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
