import { NextResponse } from 'next/server';

import { resolvePanelAccess } from '@/server/auth/access';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request) {
  const realtimeApiUrl = (process.env.REALTIME_API_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
  const access = await resolvePanelAccess(request);
  if (access.role === 'none' && !access.localDev) {
    const user = parseInitData(initDataFromRequest(request));
    if (!user) {
      return NextResponse.json({ error: 'Invalid init data' }, { status: 401 });
    }
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  const u = access.user || { id: 0, first_name: 'local' };
  return NextResponse.json({
    ok: true,
    role: access.localDev && access.role === 'none' ? 'admin' : access.role === 'none' ? 'admin' : access.role,
    ports: access.ports,
    user: { id: u.id, firstName: u.first_name, username: u.username },
    realtimeApiUrl,
  });
}
