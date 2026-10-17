import { adminTelegramIds, isAdminTelegramUser } from '@/server/auth/admin';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';
import { callTelegramApi } from '@/server/telegram/botApi';
import { isBotGroupAdmin } from '@/server/telegram/botMembership';
import { loadTelegramPhotoPayload } from '@/server/telegram/groupPhoto';
import { announcePortInGroup } from '@/server/telegram/portAnnounce';

const PORT_RE = /^\/port(?:@[\w_]+)?(?:\s+([A-Za-z0-9]{20}))?\s*$/i;
const UNPORT_RE = /^\/unport(?:@[\w_]+)?\s*$/i;

type GroupMessage = {
  text?: string;
  chat?: { id: number; type?: string; title?: string };
  from?: { id?: number };
  chat_shared?: {
    request_id?: number;
    chat_id?: number;
    title?: string;
    username?: string;
    photo?: { file_id?: string; width?: number; height?: number }[];
  };
};

export async function handleChatShared(message: GroupMessage): Promise<boolean> {
  const shared = message.chat_shared;
  if (!shared?.request_id || !shared.chat_id) {
    return false;
  }
  const fromId = message.from?.id || 0;
  const allowed = adminTelegramIds();
  if (allowed.size > 0 && !isAdminTelegramUser(fromId)) {
    return true;
  }
  let title = (shared.title || '').trim();
  if (!title) {
    const chat = await callTelegramApi<{ title?: string }>('getChat', { chat_id: shared.chat_id });
    title = chat.result?.title || '';
  }
  const username = (shared.username || '').replace(/^@/, '').trim();
  const groupLink = /^[A-Za-z0-9_]{4,}$/.test(username) ? `https://t.me/${username}` : '';
  const botAdminOk = await isBotGroupAdmin(shared.chat_id);
  const photoId = botAdminOk ? largestPhotoId(shared.photo) : '';
  const photoPayload = photoId ? await loadTelegramPhotoPayload(photoId) : null;
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/bind-shared`, {
      method: 'POST',
      headers: { ...realtimeHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({
        requestId: shared.request_id,
        groupId: shared.chat_id,
        groupTitle: title,
        groupLink,
        botAdminOk,
        groupPhotoData: photoPayload?.data || '',
        groupPhotoMime: photoPayload?.mime || '',
      }),
    });
    const data = (await res.json().catch(() => ({}))) as {
      pending?: boolean;
      code?: string;
      name?: string;
      description?: string;
      groupId?: number;
    };
    if (res.ok && data.code && !data.pending) {
      await announcePortInGroup({
        code: data.code,
        name: data.name,
        description: data.description,
        groupId: data.groupId || shared.chat_id,
      });
    }
  } catch {
    // Group selection stays quiet in the bot chat. The panel shows the result.
  }
  return true;
}

function largestPhotoId(photo: { file_id?: string; width?: number; height?: number }[] | undefined): string {
  let best = '';
  let area = -1;
  for (const item of photo || []) {
    const id = (item.file_id || '').trim();
    if (!id || /[\s/?#]/.test(id) || id.length > 512) {
      continue;
    }
    const next = (item.width || 0) * (item.height || 0);
    if (next >= area) {
      area = next;
      best = id;
    }
  }
  return best;
}

export async function handleGroupPortCommand(message: GroupMessage): Promise<boolean> {
  const type = message.chat?.type;
  if (type !== 'group' && type !== 'supergroup') {
    return false;
  }
  const text = (message.text || '').trim();
  const chatId = message.chat?.id;
  if (!chatId) {
    return false;
  }
  const portMatch = text.match(PORT_RE);
  const unport = UNPORT_RE.test(text);
  if (!portMatch && !unport) {
    return false;
  }
  const fromId = message.from?.id || 0;
  const allowed = adminTelegramIds();
  if (allowed.size > 0 && !isAdminTelegramUser(fromId)) {
    return true;
  }
  try {
    if (unport) {
      await fetch(`${realtimeBase()}/v1/ports/unbind-group`, {
        method: 'POST',
        headers: { ...realtimeHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ groupId: chatId }),
      });
      return true;
    }
    const code = portMatch?.[1] || '';
    if (!code) {
      return true;
    }
    const res = await fetch(`${realtimeBase()}/v1/ports/${code}/bind`, {
      method: 'POST',
      headers: { ...realtimeHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ groupId: chatId, groupTitle: message.chat?.title || '' }),
    });
    if (!res.ok) {
      return true;
    }
    const port = (await res.json().catch(() => ({}))) as {
      code?: string;
      name?: string;
      description?: string;
      groupId?: number;
    };
    await announcePortInGroup({
      code: port.code || code,
      name: port.name,
      description: port.description,
      groupId: port.groupId || chatId,
    });
  } catch {
    // Stay quiet. Only /start talks in the private chat.
  }
  return true;
}
