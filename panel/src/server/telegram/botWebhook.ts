import { timingSafeEqual } from 'node:crypto';

import { handleApkDocument } from '@/server/telegram/apkSigner/handleApk';
import { handleGroupAdminCommand, handleManagerCallback } from '@/server/telegram/groupAdmin';
import { handleGroupFileCommand } from '@/server/telegram/groupFileCommand';
import { handleChatShared, handleGroupPortCommand } from '@/server/telegram/groupPort';
import { handleGroupSetCommand } from '@/server/telegram/userSetCommand';
import { publicAppUrlFromRequest } from '@/server/telegram/publicUrl';
import { sendStartWelcome } from '@/server/telegram/startWelcome';
import { handleWatchCallback, type CallbackQuery } from '@/server/telegram/watchCallback';
import { telegramWebhookSecret } from '@/server/telegram/webhookSecret';

export type TelegramUpdate = {
  message?: {
    message_id?: number;
    text?: string;
    chat?: { id: number; type?: string; title?: string };
    from?: { id?: number; first_name?: string };
    chat_shared?: { request_id?: number; chat_id?: number; title?: string };
    document?: {
      file_id: string;
      file_unique_id?: string;
      file_name?: string;
      file_size?: number;
      mime_type?: string;
    };
    reply_to_message?: {
      message_id?: number;
      from?: { id?: number; first_name?: string; username?: string; is_bot?: boolean };
    };
  };
  callback_query?: CallbackQuery;
};

function secretsEqual(left: string, right: string): boolean {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  if (a.length !== b.length) {
    return false;
  }
  return timingSafeEqual(a, b);
}

export function verifyTelegramWebhook(request: Request): boolean {
  const expected = telegramWebhookSecret();
  const received = request.headers.get('x-telegram-bot-api-secret-token') || '';
  if (!expected) {
    return process.env.NODE_ENV !== 'production';
  }
  return secretsEqual(expected, received);
}

function isStartCommand(text: string | undefined): boolean {
  if (!text) {
    return false;
  }
  return /^\/start(?:@[\w_]+)?(?:\s|$)/i.test(text.trim());
}

export async function handleBotUpdate(update: TelegramUpdate, miniAppUrl: string | null): Promise<void> {
  if (update.callback_query) {
    if (await handleManagerCallback(update.callback_query)) {
      return;
    }
    await handleWatchCallback(update.callback_query);
    return;
  }
  const message = update.message;
  if (!message) {
    return;
  }
  if (await handleChatShared(message)) {
    return;
  }
  if (await handleGroupSetCommand(message)) {
    return;
  }
  if (await handleGroupAdminCommand(message)) {
    return;
  }
  if (await handleGroupFileCommand(message)) {
    return;
  }
  if (await handleGroupPortCommand(message)) {
    return;
  }
  if (await handleApkDocument(message)) {
    return;
  }
  if (message.chat?.type !== 'private' || !isStartCommand(message.text)) {
    return;
  }
  const chatId = message.chat?.id;
  if (!chatId) {
    return;
  }
  await sendStartWelcome(chatId, message.from?.first_name || 'اپراتور', miniAppUrl);
}

export function resolveMiniAppUrl(request: Request): string | null {
  return publicAppUrlFromRequest(request);
}
