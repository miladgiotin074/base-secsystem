import { callTelegramApi } from '@/server/telegram/botApi';

type ChatMember = {
  status?: string;
};

let cachedBotId = 0;

async function botUserId(): Promise<number> {
  if (cachedBotId) {
    return cachedBotId;
  }
  const me = await callTelegramApi<{ id?: number }>('getMe', {});
  cachedBotId = me.result?.id || 0;
  return cachedBotId;
}

const adminStatuses = new Set(['creator', 'administrator']);

function candidateChatIds(chatId: number): number[] {
  const ids = [chatId];
  if (chatId > 0) {
    const supergroup = Number(`-100${chatId}`);
    if (Number.isSafeInteger(supergroup)) {
      ids.push(supergroup);
    }
    ids.push(-chatId);
  }
  return [...new Set(ids)];
}

export async function isBotGroupAdmin(chatId: number): Promise<boolean> {
  if (!Number.isFinite(chatId) || chatId === 0) {
    return false;
  }
  const botId = await botUserId();
  if (!botId) {
    return false;
  }
  for (const id of candidateChatIds(chatId)) {
    const member = await callTelegramApi<ChatMember>('getChatMember', {
      chat_id: id,
      user_id: botId,
    });
    const status = member.result?.status || '';
    if (member.ok && adminStatuses.has(status)) {
      return true;
    }
    if (member.ok && status) {
      return false;
    }
    const admins = await callTelegramApi<Array<{ status?: string; user?: { id?: number } }>>('getChatAdministrators', {
      chat_id: id,
    });
    if (
      admins.ok &&
      Array.isArray(admins.result) &&
      admins.result.some((row) => row.user?.id === botId && adminStatuses.has(row.status || ''))
    ) {
      return true;
    }
  }
  return false;
}

export const BOT_ADMIN_REQUIRED_MESSAGE =
  'ربات در این گروه ادمین نیست. اول ربات را به‌عنوان ادمین گروه اضافه کن، بعد دوباره گروه را انتخاب کن.';
