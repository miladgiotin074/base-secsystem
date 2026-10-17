import { NextResponse } from 'next/server';

import { filterOverviewItems, isAccess, requirePanelOpen } from '@/server/auth/access';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request) {
  const access = await requirePanelOpen(request);
  if (!isAccess(access)) return access;

  const base = (process.env.REALTIME_API_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
  const headers: HeadersInit = {};
  const token = (process.env.REALTIME_ADMIN_TOKEN || '').trim();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  try {
    const res = await fetch(`${base}/v1/overview`, { headers, cache: 'no-store' });
    if (!res.ok) {
      return NextResponse.json({ error: 'Realtime overview failed' }, { status: 502 });
    }
    const body = (await res.json()) as {
      items?: { devices?: { port?: string }[]; online?: boolean }[];
      users?: number;
      onlineUsers?: number;
      devices?: number;
      onlineDevices?: number;
      [key: string]: unknown;
    };
    if (access.role === 'manager') {
      const items = filterOverviewItems(body.items || [], access.ports);
      let onlineUsers = 0;
      let devices = 0;
      let onlineDevices = 0;
      for (const user of items) {
        if (user.online) onlineUsers += 1;
        for (const d of user.devices || []) {
          devices += 1;
          if ((d as { online?: boolean }).online) onlineDevices += 1;
        }
      }
      return NextResponse.json({
        ...body,
        items,
        users: items.length,
        onlineUsers,
        devices,
        onlineDevices,
      });
    }
    return NextResponse.json(body);
  } catch {
    return NextResponse.json({ error: 'Realtime unreachable' }, { status: 502 });
  }
}
