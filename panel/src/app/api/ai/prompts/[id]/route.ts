import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

type Params = { params: Promise<{ id: string }> };

function allowed(request: Request): boolean {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== 'production';
  return localDev || Boolean(user && isAdminTelegramUser(user.id));
}

async function forward(request: Request, id: string, method: string, body?: string) {
  const res = await fetch(`${realtimeBase()}/v1/ai/prompts/${encodeURIComponent(id)}`, {
    method,
    headers: { ...realtimeHeaders(), 'Content-Type': 'application/json' },
    body,
    cache: 'no-store',
  });
  const text = await res.text();
  return new NextResponse(text, {
    status: res.status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export async function PUT(request: Request, { params }: Params) {
  if (!allowed(request)) return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  const { id } = await params;
  try {
    return await forward(request, id, 'PUT', await request.text());
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}

export async function DELETE(request: Request, { params }: Params) {
  if (!allowed(request)) return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  const { id } = await params;
  try {
    return await forward(request, id, 'DELETE');
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
