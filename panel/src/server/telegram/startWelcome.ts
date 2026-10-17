import { callTelegramApi } from '@/server/telegram/botApi';

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function startWelcomeHtml(firstName: string): string {
  const name = escapeHtml(firstName.trim() || 'اپراتور');
  return [
    `سلام <b>${name}</b>`,
    '',
    'پنل <b>SecSystem</b> — مدیریت دستگاه‌های Agent (Mini App).',
    '',
    'دکمهٔ زیر را بزن تا پنل باز شود. فقط شناسه‌های مجاز در BotFather/ENV دسترسی دارند.',
  ].join('\n');
}

export function miniAppOpenMarkup(miniAppUrl: string) {
  return {
    inline_keyboard: [[{ text: 'پنل کفتار', web_app: { url: miniAppUrl } }]],
  };
}

export async function sendStartWelcome(
  chatId: number,
  firstName: string,
  miniAppUrl: string | null,
): Promise<void> {
  const replyMarkup = miniAppUrl ? miniAppOpenMarkup(miniAppUrl) : undefined;
  await callTelegramApi('sendMessage', {
    chat_id: chatId,
    text: startWelcomeHtml(firstName),
    parse_mode: 'HTML',
    link_preview_options: { is_disabled: true },
    ...(replyMarkup ? { reply_markup: replyMarkup } : {}),
  });
}
