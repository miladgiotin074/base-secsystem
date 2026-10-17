import { NextResponse } from 'next/server';

import { isAccess, requireGlobalAdmin, requirePanelOpen } from '@/server/auth/access';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';
import { BOT_ADMIN_REQUIRED_MESSAGE, isBotGroupAdmin } from '@/server/telegram/botMembership';
import { announcePortInGroup } from '@/server/telegram/portAnnounce';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request) {
  const access = await requirePanelOpen(request);
  if (!isAccess(access)) return access;
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports`, { headers: realtimeHeaders(), cache: 'no-store' });
    if (!res.ok) {
      return NextResponse.json({ error: 'Ports list failed' }, { status: 502 });
    }
    const body = (await res.json()) as { items?: { code?: string }[] };
    if (access.role === 'manager') {
      const allowed = new Set(access.ports);
      return NextResponse.json({
        ...body,
        items: (body.items || []).filter((p) => p.code && allowed.has(p.code)),
      });
    }
    return NextResponse.json(body);
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}

export async function POST(request: Request) {
  const access = await requireGlobalAdmin(request);
  if (!isAccess(access)) return access;
  const rawBody = await request.text();
  try {
    const parsed = JSON.parse(rawBody) as { groupId?: number; requestId?: number };
    if (parsed.groupId && !parsed.requestId) {
      const ok = await isBotGroupAdmin(Number(parsed.groupId));
      if (!ok) {
        return NextResponse.json({ error: BOT_ADMIN_REQUIRED_MESSAGE }, { status: 400 });
      }
    }
  } catch {
    return NextResponse.json({ error: 'Invalid body' }, { status: 400 });
  }
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports`, {
      method: 'POST',
      headers: { ...realtimeHeaders(), 'Content-Type': 'application/json' },
      body: rawBody,
    });
    const data = (await res.json().catch(() => ({}))) as {
      error?: string;
      code?: string;
      name?: string;
      description?: string;
      groupId?: number;
    };
    if (!res.ok) {
      const message =
        res.status === 400 ? BOT_ADMIN_REQUIRED_MESSAGE : data.error || 'Port create failed';
      return NextResponse.json({ error: message }, { status: res.status === 400 ? 400 : 502 });
    }
    if (data.groupId && data.code) {
      await announcePortInGroup(data).catch(() => undefined);
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
