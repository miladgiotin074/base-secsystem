import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { downloadTelegramFile } from '@/server/telegram/botApi';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request) {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== 'production';
  if (!localDev && (!user || !isAdminTelegramUser(user.id))) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  const fileId = new URL(request.url).searchParams.get('file') || '';
  if (!/^[^/?#\s]{8,512}$/.test(fileId)) {
    return NextResponse.json({ error: 'Invalid file' }, { status: 400 });
  }
  const file = await downloadTelegramFile(fileId);
  if (!file?.ok || !file.body) {
    return NextResponse.json({ error: 'Photo unavailable' }, { status: 502 });
  }
  return new NextResponse(file.body, {
    headers: {
      'Content-Type': file.headers.get('content-type') || 'image/jpeg',
      'Cache-Control': 'private, max-age=3600',
    },
  });
}
