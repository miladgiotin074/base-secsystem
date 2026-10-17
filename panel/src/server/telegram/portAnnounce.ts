import { callTelegramApi } from '@/server/telegram/botApi';

type PortNotice = {
  code?: string;
  name?: string;
  description?: string;
  groupId?: number;
};

let cachedUsername = '';

function escapeMarkdownV2(value: string): string {
  return value.replace(/[_*[\]()~`>#+\-=|{}.!\\]/g, '\\$&');
}

function escapeCode(value: string): string {
  return value.replace(/[\\`]/g, '\\$&');
}

export function portAssignedMarkdown(port: PortNotice): string {
  const name = escapeMarkdownV2((port.name || '').trim() || 'بدون اسم');
  const description = (port.description || '').trim();
  const code = escapeCode((port.code || '').trim());
  const lines = [
    '🟢 *پورت SecSystem روشن شد*',
    '',
    'از این لحظه لاگ کلاینت‌های همین پورت، زنده، داخل این گروه می‌آید\\.',
    '',
    '🏷 *اسم*',
    name,
  ];
  if (description) {
    lines.push('', '📝 *توضیحات*', escapeMarkdownV2(description));
  }
  if (code) {
    lines.push('', '🔑 *کد پورت*', `\`${code}\``);
  }
  lines.push('', '👇 کد را کپی کن، یا پنل را باز کن\\.');
  return lines.join('\n');
}

async function panelUrl(): Promise<string> {
  if (!cachedUsername) {
    const me = await callTelegramApi<{ username?: string }>('getMe', {});
    cachedUsername = (me.result?.username || '').replace(/^@/, '');
  }
  if (cachedUsername) {
    return `https://t.me/${cachedUsername}?startapp`;
  }
  return (process.env.TELEGRAM_MINI_APP_URL || '').trim();
}

export async function announcePortInGroup(port: PortNotice): Promise<void> {
  const groupId = Number(port.groupId || 0);
  const code = (port.code || '').trim();
  if (!groupId || !code) {
    return;
  }
  const url = await panelUrl();
  const row: Record<string, unknown>[] = [{ text: '📋 کپی کد', copy_text: { text: code } }];
  if (url.startsWith('https://')) {
    row.push({ text: '🎛 باز کردن پنل', url });
  }
  await callTelegramApi('sendMessage', {
    chat_id: groupId,
    text: portAssignedMarkdown(port),
    parse_mode: 'MarkdownV2',
    link_preview_options: { is_disabled: true },
    reply_markup: { inline_keyboard: [row] },
  });
}
