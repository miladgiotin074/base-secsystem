const TELEGRAM_API = 'https://api.telegram.org';

type TelegramApiResult<T = unknown> = {
  ok: boolean;
  result?: T;
  description?: string;
};

function botToken(): string {
  const token = (process.env.TELEGRAM_BOT_TOKEN || '').trim();
  if (!token) {
    throw new Error('TELEGRAM_BOT_TOKEN is not configured');
  }
  return token;
}

export async function callTelegramApi<T = unknown>(
  method: string,
  body: Record<string, unknown>,
): Promise<TelegramApiResult<T>> {
  const response = await fetch(`${TELEGRAM_API}/bot${botToken()}/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = (await response.json()) as TelegramApiResult<T>;
  if (!data.ok) {
    console.error(`Telegram ${method} failed:`, data.description || data);
  }
  return data;
}

export async function downloadTelegramFile(fileId: string): Promise<Response | null> {
  const meta = await callTelegramApi<{ file_path?: string }>('getFile', { file_id: fileId });
  const path = meta.result?.file_path;
  if (!meta.ok || !path) {
    return null;
  }
  return fetch(`${TELEGRAM_API}/file/bot${botToken()}/${path}`);
}

export async function sendTelegramDocument(
  chatId: number,
  filePath: string,
  fileName: string,
): Promise<TelegramApiResult> {
  const { readFile } = await import('node:fs/promises');
  const bytes = await readFile(filePath);
  const form = new FormData();
  form.append('chat_id', String(chatId));
  form.append('document', new Blob([bytes]), fileName);
  const response = await fetch(`${TELEGRAM_API}/bot${botToken()}/sendDocument`, {
    method: 'POST',
    body: form,
  });
  const data = (await response.json()) as TelegramApiResult;
  if (!data.ok) {
    console.error('Telegram sendDocument failed:', data.description || data);
  }
  return data;
}

export function requestChatButton(requestId: number) {
  return {
    text: 'انتخاب گروه',
    request_chat: {
      request_id: requestId,
      chat_is_channel: false,
      request_title: true,
      request_username: true,
      request_photo: true,
    },
  };
}
