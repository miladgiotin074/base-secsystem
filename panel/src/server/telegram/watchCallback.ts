import { isAdminTelegramUser } from '@/server/auth/admin';
import { callTelegramApi } from '@/server/telegram/botApi';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

type InlineButton = {
  text?: string;
  url?: string;
  callback_data?: string;
};

export type CallbackQuery = {
  id: string;
  from?: { id?: number };
  data?: string;
  message?: {
    message_id?: number;
    chat?: { id?: number };
    text?: string;
    reply_markup?: { inline_keyboard?: InlineButton[][] };
  };
};

type ActionResult = {
  ok?: boolean;
  watchMuted?: boolean;
  blocked?: boolean;
  skipLog?: boolean;
  skipExtract?: boolean;
  packageRef?: string;
  setToken?: string;
  appLabel?: string;
  action?: string;
};

export async function handleWatchCallback(cq: CallbackQuery): Promise<boolean> {
  const data = (cq.data || '').trim();
  if (!data.startsWith('w')) {
    return false;
  }
  const fromId = cq.from?.id ?? 0;
  if (!isAdminTelegramUser(fromId)) {
    await answerCallback(cq.id, 'اجازه ندارید');
    return true;
  }
  const parsed = parseWatchCallback(data);
  if (!parsed) {
    return false;
  }
  try {
    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...realtimeHeaders(),
    };
    const res = await fetch(`${realtimeBase()}/v1/watch/action`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        action: parsed.action,
        setToken: parsed.setToken,
        packageRef: parsed.packageRef || undefined,
      }),
      cache: 'no-store',
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      await answerCallback(cq.id, text.includes('package') ? 'پکیج پیدا نشد' : 'خطا در اعمال');
      return true;
    }
    const body = (await res.json()) as ActionResult;
    const state = {
      muted: Boolean(body.watchMuted),
      blocked: Boolean(body.blocked),
      skipLog: Boolean(body.skipLog),
      skipExtract: Boolean(body.skipExtract),
    };
    const appLabel =
      (body.appLabel || '').trim() ||
      appLabelFromMarkup(cq.message?.reply_markup) ||
      appLabelFromText(cq.message?.text) ||
      'برنامه';
    await refreshWatchMessage(cq, parsed.setToken, parsed.packageRef || body.packageRef || '', appLabel, state);
    await answerCallback(cq.id, toastFor(parsed.action, state));
  } catch {
    await answerCallback(cq.id, 'سرور در دسترس نیست');
  }
  return true;
}

function parseWatchCallback(data: string): {
  action: string;
  setToken: string;
  packageRef?: string;
} | null {
  const parts = data.split(':');
  if (parts.length < 2) {
    return null;
  }
  const kind = parts[0];
  const setToken = (parts[1] || '').toLowerCase();
  if (!/^[0-9a-f]{16}$/.test(setToken)) {
    return null;
  }
  const packageRef = parts.slice(2).join(':');
  switch (kind) {
    case 'wl':
      if (!packageRef) return null;
      return { action: 'skip_log', setToken, packageRef };
    case 'we':
      if (!packageRef) return null;
      return { action: 'skip_extract', setToken, packageRef };
    case 'wb':
      return { action: 'block', setToken, packageRef: packageRef || undefined };
    case 'wm':
      return { action: 'mute', setToken, packageRef: packageRef || undefined };
    default:
      return null;
  }
}

function toastFor(
  action: string,
  state: { muted: boolean; blocked: boolean; skipLog: boolean; skipExtract: boolean },
): string {
  switch (action) {
    case 'mute':
      return state.muted ? 'بی‌صدا شد' : 'صدا دار شد';
    case 'block':
      return state.blocked ? 'بلاک شد' : 'رفع بلاک';
    case 'skip_log':
      return state.skipLog ? 'لاگ اپ معاف شد' : 'لاگ اپ فعال شد';
    case 'skip_extract':
      return state.skipExtract ? 'استخراج معاف شد' : 'استخراج فعال شد';
    default:
      return 'اعمال شد';
  }
}

function pickUrls(markup?: { inline_keyboard?: InlineButton[][] }): {
  sessionUrl?: string;
  manageUrl?: string;
} {
  const rows = markup?.inline_keyboard || [];
  let sessionUrl: string | undefined;
  let manageUrl: string | undefined;
  for (const row of rows) {
    for (const btn of row) {
      const url = (btn.url || '').trim();
      if (!url) continue;
      if (url.includes('startapp=ws_')) sessionUrl = url;
      else if (url.includes('startapp=u_')) manageUrl = url;
    }
  }
  return { sessionUrl, manageUrl };
}

function appLabelFromMarkup(markup?: { inline_keyboard?: InlineButton[][] }): string {
  for (const row of markup?.inline_keyboard || []) {
    for (const btn of row) {
      const text = (btn.text || '').trim();
      const marked = text.match(/📱\s*(.+?)\s*📱/);
      if (marked?.[1]) return marked[1].trim();
      const legacy = text.match(/(?:معاف لاگ|فعال لاگ|معاف استخراج|فعال استخراج)(?: این)? (.+) برای کاربر$/);
      if (legacy?.[1]) return legacy[1].replace(/^📱\s*|\s*📱$/g, '').trim();
    }
  }
  return '';
}

function appLabelFromText(text?: string): string {
  const line = (text || '')
    .split('\n')
    .map((part) => part.trim())
    .find((part) => part.startsWith('برنامه'));
  if (!line) return '';
  return line.replace(/^برنامه\s*:?\s*/u, '').trim();
}

function watchAppButton(prefix: string, app: string, suffix: string): string {
  let name = (app || '').trim() || 'برنامه';
  const join = (value: string) => `${prefix} 📱 ${value} 📱 ${suffix}`.trim();
  let label = join(name);
  if ([...label].length <= 64) return label;
  const overhead = [...prefix].length + [...suffix].length + [...' 📱  📱 '].length;
  const keep = Math.max(4, 64 - overhead);
  const runes = [...name];
  if (runes.length > keep) name = `${runes.slice(0, keep - 1).join('')}…`;
  label = join(name);
  return [...label].length <= 64 ? label : [...label].slice(0, 64).join('');
}

function buildKeyboard(
  setToken: string,
  packageRef: string,
  appLabel: string,
  urls: { sessionUrl?: string; manageUrl?: string },
  state: { muted: boolean; blocked: boolean; skipLog: boolean; skipExtract: boolean },
): { inline_keyboard: InlineButton[][] } {
  const rows: InlineButton[][] = [];
  const top: InlineButton[] = [];
  if (urls.sessionUrl) top.push({ text: '📂 نشست', url: urls.sessionUrl });
  if (urls.manageUrl) top.push({ text: '👤 مدیریت کاربر', url: urls.manageUrl });
  if (top.length) rows.push(top);
  if (packageRef) {
    rows.push([
      {
        text: state.skipLog
          ? watchAppButton('✅ فعال لاگ', appLabel, 'برای کاربر')
          : watchAppButton('📋 معاف لاگ', appLabel, 'برای کاربر'),
        callback_data: `wl:${setToken}:${packageRef}`,
      },
    ]);
    rows.push([
      {
        text: state.skipExtract
          ? watchAppButton('✅ فعال استخراج', appLabel, 'برای کاربر')
          : watchAppButton('🤖 معاف استخراج', appLabel, 'برای کاربر'),
        callback_data: `we:${setToken}:${packageRef}`,
      },
    ]);
  }
  const blockData = packageRef ? `wb:${setToken}:${packageRef}` : `wb:${setToken}`;
  const muteData = packageRef ? `wm:${setToken}:${packageRef}` : `wm:${setToken}`;
  rows.push([
    {
      text: state.blocked ? '✅ رفع بلاک کاربر' : '🚫 بلاک کاربر',
      callback_data: blockData,
    },
  ]);
  rows.push([
    {
      text: state.muted ? '🔊 صدا دار کردن کاربر' : '🔇 بی‌صدا کردن کاربر',
      callback_data: muteData,
    },
  ]);
  return { inline_keyboard: rows };
}

async function refreshWatchMessage(
  cq: CallbackQuery,
  setToken: string,
  packageRef: string,
  appLabel: string,
  state: { muted: boolean; blocked: boolean; skipLog: boolean; skipExtract: boolean },
): Promise<void> {
  const chatId = cq.message?.chat?.id;
  const messageId = cq.message?.message_id;
  if (!chatId || !messageId) {
    return;
  }
  const urls = pickUrls(cq.message?.reply_markup);
  if (!packageRef) {
    for (const row of cq.message?.reply_markup?.inline_keyboard || []) {
      for (const btn of row) {
        const data = (btn.callback_data || '').trim();
        if (data.startsWith('wl:') || data.startsWith('we:')) {
          packageRef = data.split(':').slice(2).join(':');
          break;
        }
      }
      if (packageRef) break;
    }
  }
  const keyboard = buildKeyboard(setToken, packageRef, appLabel, urls, state);
  await callTelegramApi('editMessageReplyMarkup', {
    chat_id: chatId,
    message_id: messageId,
    reply_markup: keyboard,
  });
}

async function answerCallback(id: string, text: string): Promise<void> {
  await callTelegramApi('answerCallbackQuery', {
    callback_query_id: id,
    text,
    show_alert: false,
  });
}
