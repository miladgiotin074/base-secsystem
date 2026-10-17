import { NextResponse } from 'next/server';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { initDataFromRequest, parseInitData } from '@/server/auth/initData';
import { callTelegramApi } from '@/server/telegram/botApi';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

type Chat = {
  id: number;
  title?: string;
  username?: string;
  type?: string;
  description?: string;
  invite_link?: string;
  photo?: { small_file_id?: string; big_file_id?: string };
};

export async function GET(request: Request, context: { params: Promise<{ id: string }> }) {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== 'production';
  if (!localDev && (!user || !isAdminTelegramUser(user.id))) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  const { id } = await context.params;
  const chatId = Number(id);
  if (!Number.isFinite(chatId) || chatId === 0) {
    return NextResponse.json({ error: 'Invalid group' }, { status: 400 });
  }
  const chat = await callTelegramApi<Chat>('getChat', { chat_id: chatId });
  if (!chat.ok || !chat.result) {
    return NextResponse.json({ error: 'Group unavailable' }, { status: 502 });
  }
  const count = await callTelegramApi<number>('getChatMemberCount', { chat_id: chatId });
  const photoId = chat.result.photo?.big_file_id || chat.result.photo?.small_file_id || '';
  return NextResponse.json({
    id: chat.result.id,
    title: chat.result.title || '',
    username: chat.result.username || '',
    description: chat.result.description || '',
    members: typeof count.result === 'number' ? count.result : undefined,
    photoUrl: photoId ? `/api/telegram/photo?file=${encodeURIComponent(photoId)}` : '',
    link: groupLink(chat.result),
  });
}

function groupLink(chat: Chat): string {
  if (chat.username) {
    return `https://t.me/${chat.username}`;
  }
  if (chat.invite_link) {
    return chat.invite_link;
  }
  const id = String(chat.id);
  if (id.startsWith('-100')) {
    return `https://t.me/c/${id.slice(4)}`;
  }
  return '';
}
