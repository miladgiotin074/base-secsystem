import { NextResponse } from 'next/server';

import {
  handleBotUpdate,
  resolveMiniAppUrl,
  verifyTelegramWebhook,
  type TelegramUpdate,
} from '@/server/telegram/botWebhook';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
/** APK encode/sign can take several minutes (APKEditor + uber-apk-signer). */
export const maxDuration = 600;

export async function POST(request: Request) {
  if (!process.env.TELEGRAM_BOT_TOKEN) {
    return NextResponse.json({ error: 'TELEGRAM_BOT_TOKEN is not configured' }, { status: 500 });
  }
  if (!verifyTelegramWebhook(request)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  let update: TelegramUpdate;
  try {
    update = (await request.json()) as TelegramUpdate;
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 });
  }
  try {
    await handleBotUpdate(update, resolveMiniAppUrl(request));
  } catch (error) {
    console.error('Failed to handle Telegram bot update', error);
  }
  return NextResponse.json({ ok: true });
}
