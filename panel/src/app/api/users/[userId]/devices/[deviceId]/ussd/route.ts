import { NextResponse } from 'next/server';

import { isAccess, requireUserAccess } from '@/server/auth/access';
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
      `${realtimeBase()}/v1/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/ussd`,
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

export async function POST(request: Request, { params }: Params) {
  const { userId, deviceId } = await params;
  const access = await requireUserAccess(request, userId);
  if (!isAccess(access)) return access;
  const body = await request.text();
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/ussd`,
      {
        method: 'POST',
        headers: { ...realtimeHeaders(), 'Content-Type': 'application/json' },
        body,
      },
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
