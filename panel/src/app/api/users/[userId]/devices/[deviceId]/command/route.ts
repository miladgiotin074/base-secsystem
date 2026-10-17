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
  if (!userId || !deviceId || !/^[A-Za-z0-9._:-]{1,64}$/.test(userId) || !/^[A-Za-z0-9._:-]{1,64}$/.test(deviceId)) {
    return NextResponse.json({ error: 'Invalid identity' }, { status: 400 });
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Invalid body' }, { status: 400 });
  }

  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    ...realtimeHeaders(),
  };

  try {
    const res = await fetch(
      `${realtimeBase()}/v1/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/command`,
      {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(
          typeof body === 'object' && body && (body as { type?: string }).type === 'screenshot' ? 40_000 : 18_000,
        ),
      },
    );
    const text = await res.text();
    if (!res.ok) {
      try {
        const parsed = JSON.parse(text) as { error?: string };
        return NextResponse.json(
          { error: parsed.error || text || 'Command failed', ok: false },
          { status: res.status === 401 ? 502 : res.status },
        );
      } catch {
        return NextResponse.json({ error: text || 'Command failed', ok: false }, { status: res.status === 401 ? 502 : res.status });
      }
    }
    return new NextResponse(text, { status: 200, headers: { 'Content-Type': 'application/json' } });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable', ok: false }, { status: 502 });
  }
}
