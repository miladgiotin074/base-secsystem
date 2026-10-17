import { NextResponse } from 'next/server';

import { adminTelegramIds, isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData, peekInitDataUser } from '@/server/auth/initData';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';
import { callTelegramApi, requestChatButton } from '@/server/telegram/botApi';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(request: Request) {
  let body: { code?: string; initData?: string };
  try {
    body = (await request.json()) as { code?: string; initData?: string };
  } catch {
    return NextResponse.json({ error: 'Invalid body' }, { status: 400 });
  }

  const raw = initDataFromRequest(request) || body.initData || '';
  const localDev = process.env.NODE_ENV !== 'production';
  const user =
    parseInitData(raw) ||
    (localDev ? peekInitDataUser(raw) : null) ||
    (localDev ? fallbackAdminUser() : null);
  if (!localDev && (!user || !isAdminTelegramUser(user.id))) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  if (!user?.id) {
    return NextResponse.json({ error: 'Mini App required' }, { status: 401 });
  }
  const code = (body.code || '').trim();
  if (!/^[A-Za-z0-9]{20}$/.test(code)) {
    return NextResponse.json({ error: 'Invalid port' }, { status: 400 });
  }

  let requestId = 0;
  try {
    const prepared = await fetch(`${realtimeBase()}/v1/ports/${code}/prepare-bind`, {
      method: 'POST',
      headers: realtimeHeaders(),
    });
    if (prepared.status === 404) {
      return NextResponse.json({ error: 'Port not found' }, { status: 404 });
    }
    if (!prepared.ok) {
      return NextResponse.json({ error: 'Prepare failed' }, { status: 502 });
    }
    const data = (await prepared.json()) as { requestId?: number };
    requestId = Number(data.requestId || 0);
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
  if (!requestId) {
    return NextResponse.json({ error: 'Prepare failed' }, { status: 502 });
  }

  const button = requestChatButton(requestId);
  const saved = await callTelegramApi<{ id: string }>('savePreparedKeyboardButton', {
    user_id: user.id,
    button,
  });
  if (saved.ok && saved.result?.id) {
    return NextResponse.json({ mode: 'miniapp', id: saved.result.id, requestId });
  }
  return NextResponse.json({ error: 'Telegram picker failed' }, { status: 502 });
}

function fallbackAdminUser(): { id: number } | null {
  const id = [...adminTelegramIds()][0];
  return id ? { id } : null;
}
