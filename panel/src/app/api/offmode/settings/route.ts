import { NextResponse } from 'next/server';

import { isAccess, requireGlobalAdmin } from '@/server/auth/access';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request) {
  const access = await requireGlobalAdmin(request);
  if (!isAccess(access)) return access;
  try {
    const res = await fetch(`${realtimeBase()}/v1/offmode/settings`, {
      headers: { ...realtimeHeaders() },
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

export async function PUT(request: Request) {
  const access = await requireGlobalAdmin(request);
  if (!isAccess(access)) return access;
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Invalid body' }, { status: 400 });
  }
  try {
    const res = await fetch(`${realtimeBase()}/v1/offmode/settings`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...realtimeHeaders() },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15_000),
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
