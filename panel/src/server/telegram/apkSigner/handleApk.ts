import { readFileSync } from 'node:fs';

import { isAdminTelegramUser } from '@/server/auth/admin';
import { callTelegramApi, downloadTelegramFile } from '@/server/telegram/botApi';
import {
  cleanupTaskDir,
  processApk,
  saveUpload,
} from '@/server/telegram/apkSigner/pipeline';

type DocMessage = {
  message_id?: number;
  chat?: { id: number; type?: string };
  from?: { id?: number; first_name?: string };
  document?: {
    file_id: string;
    file_unique_id?: string;
    file_name?: string;
    file_size?: number;
    mime_type?: string;
  };
};

const inflight = new Set<string>();

function isApkName(name: string | undefined): boolean {
  return !!name && name.toLowerCase().endsWith('.apk');
}

async function reply(chatId: number, text: string): Promise<void> {
  await callTelegramApi('sendMessage', { chat_id: chatId, text });
}

async function sendApkDocument(chatId: number, filePath: string, fileName: string): Promise<void> {
  const token = (process.env.TELEGRAM_BOT_TOKEN || '').trim();
  if (!token) throw new Error('TELEGRAM_BOT_TOKEN missing');
  const bytes = new Uint8Array(readFileSync(filePath));
  const blob = new Blob([bytes], { type: 'application/vnd.android.package-archive' });
  const form = new FormData();
  form.append('chat_id', String(chatId));
  form.append('document', blob, fileName);
  const res = await fetch(`https://api.telegram.org/bot${token}/sendDocument`, {
    method: 'POST',
    body: form,
  });
  const data = (await res.json()) as { ok?: boolean; description?: string };
  if (!data.ok) {
    throw new Error(data.description || 'sendDocument failed');
  }
}

async function runJob(message: DocMessage): Promise<void> {
  const doc = message.document!;
  const chatId = message.chat!.id;

  await reply(chatId, 'در حال کدگذاری و امضای APK…');

  if ((doc.file_size || 0) > 49 * 1024 * 1024) {
    await reply(chatId, 'حجم APK بیش از حد مجاز تلگرام برای ربات است (حداکثر حدود ۵۰ مگابایت).');
    return;
  }

  let inputPath = '';
  let taskDir = '';
  try {
    const fileRes = await downloadTelegramFile(doc.file_id);
    if (!fileRes || !fileRes.ok) {
      await reply(chatId, 'دانلود فایل از تلگرام ناموفق بود.');
      return;
    }
    const buf = Buffer.from(await fileRes.arrayBuffer());
    if (buf.length < 100) {
      await reply(chatId, 'فایل خالی یا نامعتبر است.');
      return;
    }
    inputPath = saveUpload(buf);
    const result = await processApk(inputPath);
    taskDir = result.taskDir;
    await sendApkDocument(chatId, result.path, result.fileName);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('apk signer failed', err);
    await reply(chatId, `خطا در پردازش: ${msg.slice(0, 180)}`);
  } finally {
    if (taskDir) cleanupTaskDir(taskDir);
    if (inputPath) {
      try {
        const { unlinkSync } = await import('node:fs');
        unlinkSync(inputPath);
      } catch {
        /* ignore */
      }
    }
  }
}

/**
 * Private chat + admin: encode/sign APK and send the result back.
 * Returns true when the update was consumed (job may still be running).
 */
export async function handleApkDocument(message: DocMessage): Promise<boolean> {
  const doc = message.document;
  if (!doc) return false;
  if (message.chat?.type !== 'private') return false;

  const chatId = message.chat.id;
  const fromId = message.from?.id || 0;
  if (!isAdminTelegramUser(fromId)) {
    await reply(chatId, 'فقط ادمین می‌تواند APK ارسال کند.');
    return true;
  }
  if (!isApkName(doc.file_name) && doc.mime_type !== 'application/vnd.android.package-archive') {
    await reply(chatId, 'فقط فایل APK ارسال کنید.');
    return true;
  }

  const key = doc.file_unique_id || doc.file_id;
  if (inflight.has(key)) {
    return true;
  }
  inflight.add(key);
  // Do not await the long Java pipeline — Telegram retries the webhook after ~60s.
  void runJob(message).finally(() => inflight.delete(key));
  return true;
}
