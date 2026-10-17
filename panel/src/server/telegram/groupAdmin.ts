import { adminTelegramIds, isAdminTelegramUser } from '@/server/auth/admin';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';
import { callTelegramApi } from '@/server/telegram/botApi';
import type { CallbackQuery } from '@/server/telegram/watchCallback';

const MANAGE_RE = /^(?:\/(?:admin|modiriat)(?:@[\w_]+)?|admin|مدیریت)\s*$/i;

type GroupMessage = {
  message_id?: number;
  text?: string;
  chat?: { id: number; type?: string; title?: string };
  from?: { id?: number; first_name?: string; username?: string };
  reply_to_message?: {
    message_id?: number;
    from?: { id?: number; first_name?: string; username?: string; is_bot?: boolean };
  };
};

type ManagerBody = {
  ok?: boolean;
  code?: string;
  name?: string;
  isManager?: boolean;
};

function displayName(u?: { first_name?: string; username?: string; id?: number }): string {
  if (!u) return 'کاربر';
  if (u.username) return `@${u.username}`;
  return u.first_name || String(u.id || '');
}

function glassKeyboard(targetId: number, _isManager: boolean) {
  return {
    inline_keyboard: [
      [
        {
          text: 'مدیر کردن',
          callback_data: `pm:a:${targetId}`,
          style: 'success',
        },
        {
          text: 'عزل از مدیریت',
          callback_data: `pm:r:${targetId}`,
          style: 'danger',
        },
      ],
    ],
  };
}

function statusHtml(who: string, portLabel: string, isManager: boolean): string {
  const state = isManager ? 'هم‌اکنون <b>مدیر پنل</b> این پورت است.' : 'مدیر پنل این پورت <b>نیست</b>.';
  return `مدیریت دسترسی پنل برای ${who}\nپورت: <b>${escapeHtml(portLabel)}</b>\n${state}`;
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

async function loadManagerState(groupId: number, telegramId: number): Promise<ManagerBody | null> {
  try {
    const res = await fetch(
      `${realtimeBase()}/v1/ports/managers?groupId=${encodeURIComponent(String(groupId))}&telegramId=${encodeURIComponent(String(telegramId))}`,
      { headers: realtimeHeaders(), cache: 'no-store' },
    );
    if (!res.ok) return null;
    return (await res.json()) as ManagerBody;
  } catch {
    return null;
  }
}

async function setManager(
  groupId: number,
  telegramId: number,
  action: 'add' | 'remove',
): Promise<{ ok: boolean; notFound?: boolean; body?: ManagerBody }> {
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/managers`, {
      method: 'POST',
      headers: { ...realtimeHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ groupId, telegramId, action }),
    });
    if (res.status === 404) return { ok: false, notFound: true };
    if (!res.ok) return { ok: false };
    return { ok: true, body: (await res.json()) as ManagerBody };
  } catch {
    return { ok: false };
  }
}

/** ریپلای روی پیام عضو گروه + نوشتن «مدیریت» → دکمه‌های شیشه‌ای انتصاب/عزل. */
export async function handleGroupAdminCommand(message: GroupMessage): Promise<boolean> {
  const type = message.chat?.type;
  if (type !== 'group' && type !== 'supergroup') {
    return false;
  }
  const text = (message.text || '').trim();
  if (!MANAGE_RE.test(text)) {
    return false;
  }
  const chatId = message.chat?.id;
  if (!chatId) {
    return false;
  }
  const fromId = message.from?.id || 0;
  const allowed = adminTelegramIds();
  if (allowed.size > 0 && !isAdminTelegramUser(fromId)) {
    return true;
  }
  const target = message.reply_to_message?.from;
  const targetId = target?.id || 0;
  if (!targetId || target.is_bot) {
    await callTelegramApi('sendMessage', {
      chat_id: chatId,
      text: 'روی پیام همان نفر ریپلای کن و بنویس مدیریت.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }
  const state = await loadManagerState(chatId, targetId);
  if (!state) {
    await callTelegramApi('sendMessage', {
      chat_id: chatId,
      text: 'این گروه به پورتی وصل نیست.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }
  const who = displayName(target);
  const portLabel = state.name || state.code || 'پورت';
  const isManager = Boolean(state.isManager);
  await callTelegramApi('sendMessage', {
    chat_id: chatId,
    text: statusHtml(who, portLabel, isManager),
    parse_mode: 'HTML',
    reply_to_message_id: message.message_id,
    allow_sending_without_reply: true,
    reply_markup: glassKeyboard(targetId, isManager),
  });
  return true;
}

export async function handleManagerCallback(cq: CallbackQuery): Promise<boolean> {
  const data = (cq.data || '').trim();
  if (!data.startsWith('pm:')) {
    return false;
  }
  const fromId = cq.from?.id ?? 0;
  if (!isAdminTelegramUser(fromId)) {
    await answerCallback(cq.id, 'اجازه ندارید');
    return true;
  }
  const parts = data.split(':');
  if (parts.length !== 3 || (parts[1] !== 'a' && parts[1] !== 'r')) {
    await answerCallback(cq.id, 'داده نامعتبر');
    return true;
  }
  const action = parts[1] === 'a' ? 'add' : 'remove';
  const targetId = Number(parts[2]);
  const chatId = cq.message?.chat?.id;
  const messageId = cq.message?.message_id;
  if (!Number.isFinite(targetId) || targetId <= 0 || !chatId || !messageId) {
    await answerCallback(cq.id, 'خطا');
    return true;
  }
  const result = await setManager(chatId, targetId, action);
  if (result.notFound) {
    await answerCallback(cq.id, 'پورت پیدا نشد');
    return true;
  }
  if (!result.ok || !result.body) {
    await answerCallback(cq.id, 'اعمال نشد');
    return true;
  }
  const isManager = Boolean(result.body.isManager);
  const portLabel = result.body.name || result.body.code || 'پورت';
  const who = whoFromMessage(cq.message?.text) || String(targetId);
  await callTelegramApi('editMessageText', {
    chat_id: chatId,
    message_id: messageId,
    text: statusHtml(who, portLabel, isManager),
    parse_mode: 'HTML',
    reply_markup: glassKeyboard(targetId, isManager),
  });
  await answerCallback(cq.id, action === 'add' ? 'مدیر شد' : 'عزل شد');
  return true;
}

function whoFromMessage(text: string | undefined): string {
  if (!text) return '';
  const m = text.match(/برای\s+(@?\S+)/);
  return m?.[1] || '';
}

async function answerCallback(id: string, text: string) {
  await callTelegramApi('answerCallbackQuery', {
    callback_query_id: id,
    text,
    show_alert: false,
  });
}
