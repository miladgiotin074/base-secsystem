import { adminTelegramIds, isAdminTelegramUser } from '@/server/auth/admin';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';
import { callTelegramApi } from '@/server/telegram/botApi';
import { manageKeyboard, userSpecsHtml } from '@/server/telegram/user-set-reply.mjs';

const SET_RE = /^\/set_([0-9a-fA-F]{16})(?:@[\w_]+)?\s*$/;

type GroupMessage = {
  message_id?: number;
  text?: string;
  chat?: { id: number; type?: string };
  from?: { id?: number };
};

type PortItem = { code?: string; name?: string; groupId?: number };
type DeviceCard = { port?: string; portName?: string };
type UserCard = {
  userId?: string;
  displayName?: string;
  description?: string;
  setToken?: string;
  devices?: DeviceCard[];
};

export async function handleGroupSetCommand(message: GroupMessage): Promise<boolean> {
  const type = message.chat?.type;
  if (type !== 'group' && type !== 'supergroup') {
    return false;
  }
  const match = (message.text || '').trim().match(SET_RE);
  if (!match) {
    return false;
  }
  const chatId = message.chat?.id;
  if (!chatId) {
    return true;
  }
  const allowed = adminTelegramIds();
  const fromId = message.from?.id || 0;
  if (allowed.size > 0 && !isAdminTelegramUser(fromId)) {
    return true;
  }
  const setToken = match[1].toLowerCase();
  try {
    const portsRes = await fetch(`${realtimeBase()}/v1/ports`, { headers: realtimeHeaders(), cache: 'no-store' });
    if (!portsRes.ok) {
      return true;
    }
    const ports = (await portsRes.json()) as { items?: PortItem[] };
    const port = (ports.items || []).find((item) => Number(item.groupId) === Number(chatId));
    if (!port?.code) {
      return true;
    }
    const userRes = await fetch(`${realtimeBase()}/v1/users/set/${setToken}`, {
      headers: realtimeHeaders(),
      cache: 'no-store',
    });
    if (!userRes.ok) {
      return true;
    }
    const card = (await userRes.json()) as UserCard;
    const portLabel = [port.name, port.code].map((part) => String(part || '').trim()).filter(Boolean).join(' · ');
    const devices = (card.devices || [])
      .filter((device) => device.port === port.code)
      .map((device) => ({ ...device, portName: portLabel || device.port }));
    if (devices.length === 0) {
      return true;
    }
    const me = await callTelegramApi<{ username?: string }>('getMe', {});
    const username = (me.result?.username || '').replace(/^@/, '');
    const payload: Record<string, unknown> = {
      chat_id: chatId,
      text: userSpecsHtml({ ...card, setToken, devices }),
      parse_mode: 'HTML',
      link_preview_options: { is_disabled: true },
      reply_markup: manageKeyboard(username, setToken),
    };
    if (message.message_id) {
      payload.reply_to_message_id = message.message_id;
      payload.allow_sending_without_reply = true;
    }
    await callTelegramApi('sendMessage', payload);
  } catch {
    // Stay quiet in the group when the lookup fails.
  }
  return true;
}
