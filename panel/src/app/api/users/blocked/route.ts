import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request) {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== 'production';
  if (!localDev && (!user || !isAdminTelegramUser(user.id))) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }

  const base = (process.env.REALTIME_API_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
  const headers: HeadersInit = {};
  const token = (process.env.REALTIME_ADMIN_TOKEN || '').trim();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  try {
    const res = await fetch(`${base}/v1/users/blocked`, { headers, cache: 'no-store' });
    const text = await res.text();
    if (!res.ok) {
      return NextResponse.json({ error: text || 'Blocked list failed' }, { status: res.status === 401 ? 502 : res.status });
    }
    return new NextResponse(text, { status: 200, headers: { 'Content-Type': 'application/json' } });
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
