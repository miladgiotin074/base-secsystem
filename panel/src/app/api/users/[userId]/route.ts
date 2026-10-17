import { NextResponse } from 'next/server';

import { isAccess, requireUserAccess } from '@/server/auth/access';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

type Params = { params: Promise<{ userId: string }> };

export async function PATCH(request: Request, { params }: Params) {
  const { userId } = await params;
  const access = await requireUserAccess(request, userId);
  if (!isAccess(access)) return access;
  if (!userId || !/^[A-Za-z0-9._:-]{1,64}$/.test(userId)) {
    return NextResponse.json({ error: 'Invalid userId' }, { status: 400 });
  }

  const base = (process.env.REALTIME_API_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
  const headers: HeadersInit = { 'Content-Type': 'application/json' };
  const token = (process.env.REALTIME_ADMIN_TOKEN || '').trim();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Invalid body' }, { status: 400 });
  }

  try {
    const res = await fetch(`${base}/v1/users/${encodeURIComponent(userId)}`, {
      method: 'PATCH',
      headers,
      body: JSON.stringify(body),
    });
    const text = await res.text();
    if (!res.ok) {
      return NextResponse.json({ error: text || 'Update failed' }, { status: res.status === 401 ? 502 : res.status });
    }
    return new NextResponse(text, { status: 200, headers: { 'Content-Type': 'application/json' } });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
