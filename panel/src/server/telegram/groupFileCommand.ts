import { isAdminTelegramUser } from '@/server/auth/admin';
import { baseApkReady } from '@/server/telegram/apkSigner/baseStore';
import { callTelegramApi } from '@/server/telegram/botApi';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

const FILE_RE = /^(?:\/(?:file|apk)(?:@[\w_]+)?|فایل|file)\s*$/i;

type GroupMessage = {
  message_id?: number;
  text?: string;
  chat?: { id: number; type?: string; title?: string };
  from?: { id?: number };
};

type PortState = {
  ok?: boolean;
  code?: string;
  name?: string;
  isManager?: boolean;
};

async function resolveBotUsername(): Promise<string> {
  const me = await callTelegramApi<{ username?: string }>('getMe', {});
  return (me.result?.username || '').replace(/^@/, '');
}

async function loadPortByGroup(
  groupId: number,
  telegramId: number,
): Promise<PortState | null> {
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/ports/managers?groupId=${encodeURIComponent(String(groupId))}&telegramId=${encodeURIComponent(String(telegramId))}`,
      { headers: realtimeHeaders(), cache: 'no-store' },
    );
    if (!res.ok) return null;
    return (await res.json()) as PortState;
  } catch {
    return null;
  }
}

/**
 * Group command «فایل» / file — glass button opens Mini App build screen for this port.
 * Allowed: global owner or panel manager of the bound port.
 */
export async function handleGroupFileCommand(message: GroupMessage): Promise<boolean> {
  const type = message.chat?.type;
  if (type !== 'group' && type !== 'supergroup') return false;
  const text = (message.text || '').trim();
  if (!FILE_RE.test(text)) return false;
  const chatId = message.chat?.id;
  if (!chatId) return false;

  const fromId = message.from?.id || 0;
  const state = await loadPortByGroup(chatId, fromId);
  if (!state?.code) {
    await callTelegramApi('sendMessage', {
      chat_id: chatId,
      text: 'این گروه به پورتی وصل نیست.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }

  const isOwner = isAdminTelegramUser(fromId);
  const isManager = Boolean(state.isManager);
  if (!isOwner && !isManager) {
    await callTelegramApi('sendMessage', {
      chat_id: chatId,
      text: 'فقط اونر یا مدیر این پورت می‌تواند فایل بسازد.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }

  if (!baseApkReady()) {
    await callTelegramApi('sendMessage', {
      chat_id: chatId,
      text: 'APK پایه هنوز در تنظیمات پنل آپلود نشده.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }

  const username = await resolveBotUsername();
  const start = `apk_${state.code}`;
  const url = username
    ? `https://t.me/${username}?startapp=${start}`
    : `${(process.env.TELEGRAM_MINI_APP_URL || '').trim()}?tgWebAppStartParam=${start}`;

  const portLabel = state.name || state.code;
  await callTelegramApi('sendMessage', {
    chat_id: chatId,
    text: `ساخت فایل برای پورت <b>${escapeHtml(portLabel)}</b>\nاسم و آیکن را انتخاب کن، بعد بساز.`,
    parse_mode: 'HTML',
    reply_to_message_id: message.message_id,
    allow_sending_without_reply: true,
    reply_markup: {
      inline_keyboard: [
        [
          {
            text: 'ساخت فایل',
            url,
            style: 'success',
          },
        ],
      ],
    },
  });
  return true;
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
