import {
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

import { resolveSignerPaths } from '@/server/telegram/apkSigner/pipeline';

const execFileAsync = promisify(execFile);

export type BrandOptions = {
  label: string;
  iconPng?: Buffer;
  configJson?: string;
  versionName?: string;
  versionCode?: number;
};

function fileOk(path: string): boolean {
  try {
    return existsSync(path) && statSync(path).size > 0;
  } catch {
    return false;
  }
}

async function runJava(java: string, args: string[], cwd: string): Promise<void> {
  try {
    await execFileAsync(java, args, {
      cwd,
      timeout: 300_000,
      maxBuffer: 16 * 1024 * 1024,
    });
  } catch (err) {
    const e = err as { stdout?: string; stderr?: string; message?: string };
    const detail = `${e.stderr || e.stdout || e.message || err}`.trim().slice(0, 500);
    throw new Error(detail || 'APKEditor failed');
  }
}

function walkFiles(dir: string, out: string[] = []): string[] {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) walkFiles(full, out);
    else out.push(full);
  }
  return out;
}

function patchAppNameInText(raw: string, label: string): string {
  let next = raw;
  // APKEditor JSON string entries
  next = next.replace(
    /("name"\s*:\s*"app_name"\s*,\s*"value"\s*:\s*")([^"]*)(")/g,
    `$1${jsonEscape(label)}$3`,
  );
  next = next.replace(
    /("name"\s*:\s*"alias_app_name"\s*,\s*"value"\s*:\s*")([^"]*)(")/g,
    `$1${jsonEscape(label)}$3`,
  );
  // XML strings
  next = next.replace(
    /(<string\s+name="app_name">)([\s\S]*?)(<\/string>)/g,
    `$1${xmlEscape(label)}$3`,
  );
  next = next.replace(
    /(<string\s+name="alias_app_name">)([\s\S]*?)(<\/string>)/g,
    `$1${xmlEscape(label)}$3`,
  );
  return next;
}

function jsonEscape(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

function xmlEscape(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function applyLabel(decodedDir: string, label: string): void {
  for (const file of walkFiles(decodedDir)) {
    const lower = file.toLowerCase();
    if (
      !lower.endsWith('.xml') &&
      !lower.endsWith('.json') &&
      !lower.endsWith('.txt')
    ) {
      continue;
    }
    let raw: string;
    try {
      raw = readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    if (!raw.includes('app_name') && !raw.includes('alias_app_name')) continue;
    const next = patchAppNameInText(raw, label);
    if (next !== raw) writeFileSync(file, next);
  }
}

function applyIcon(decodedDir: string, iconPng: Buffer): void {
  const targets: string[] = [];
  for (const file of walkFiles(decodedDir)) {
    const base = file.replace(/\\/g, '/').toLowerCase();
    if (
      base.endsWith('/ic_launcher.xml') ||
      base.endsWith('/ic_launcher.png') ||
      base.endsWith('/ic_launcher.webp') ||
      base.endsWith('/ic_alias.xml') ||
      base.endsWith('/ic_alias.png') ||
      base.endsWith('/ic_alias.webp')
    ) {
      targets.push(file);
    }
  }
  if (targets.length === 0) {
    // Fallback: drop into common drawable folder if present.
    const drawable = walkFiles(decodedDir).find((f) =>
      /[/\\]res[/\\]drawable[/\\]/i.test(f),
    );
    if (drawable) {
      const png = join(dirname(drawable), 'ic_launcher.png');
      writeFileSync(png, iconPng);
      return;
    }
    throw new Error('ic_launcher not found in decoded APK');
  }
  for (const file of targets) {
    const pngPath = file.replace(/\.(xml|webp|png)$/i, '.png');
    if (file !== pngPath && existsSync(file)) {
      try {
        unlinkSync(file);
      } catch {
        /* ignore */
      }
    }
    writeFileSync(pngPath, iconPng);
  }
}

function applyConfig(decodedDir: string, configJson: string): void {
  const hit = walkFiles(decodedDir).find((f) => /[/\\]assets[/\\]config\.json$/i.test(f));
  if (!hit) {
    throw new Error('assets/config.json not found in decoded APK');
  }
  writeFileSync(hit, configJson);
}

function patchVersionInText(raw: string, versionName: string, versionCode: number): string {
  let next = raw;
  if (versionName) {
    next = next.replace(
      /android:versionName\s*=\s*"[^"]*"/g,
      `android:versionName="${xmlEscape(versionName)}"`,
    );
    next = next.replace(
      /("versionName"\s*:\s*")([^"]*)(")/g,
      `$1${jsonEscape(versionName)}$3`,
    );
    next = next.replace(
      /("version_name"\s*:\s*")([^"]*)(")/g,
      `$1${jsonEscape(versionName)}$3`,
    );
  }
  if (versionCode > 0) {
    next = next.replace(
      /android:versionCode\s*=\s*"[^"]*"/g,
      `android:versionCode="${versionCode}"`,
    );
    next = next.replace(/("versionCode"\s*:\s*)(\d+)/g, `$1${versionCode}`);
    next = next.replace(/("version_code"\s*:\s*)(\d+)/g, `$1${versionCode}`);
  }
  return next;
}

function applyVersion(decodedDir: string, versionName: string, versionCode: number): void {
  if (!versionName && versionCode <= 0) return;
  for (const file of walkFiles(decodedDir)) {
    const base = file.replace(/\\/g, '/').toLowerCase();
    if (
      !base.endsWith('androidmanifest.xml') &&
      !base.endsWith('androidmanifest.json') &&
      !base.endsWith('.xml') &&
      !base.endsWith('.json')
    ) {
      continue;
    }
    if (
      !base.includes('androidmanifest') &&
      !base.endsWith('/apk.json') &&
      !base.endsWith('/package.json')
    ) {
      // Still scan small manifests / info files that mention version*
      let peek: string;
      try {
        peek = readFileSync(file, 'utf8');
      } catch {
        continue;
      }
      if (!/versionName|versionCode|version_name|version_code/i.test(peek)) continue;
      const next = patchVersionInText(peek, versionName, versionCode);
      if (next !== peek) writeFileSync(file, next);
      continue;
    }
    let raw: string;
    try {
      raw = readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    const next = patchVersionInText(raw, versionName, versionCode);
    if (next !== raw) writeFileSync(file, next);
  }
}

/**
 * Decode → patch label/icon/config → rebuild. Returns path to branded unsigned APK.
 */
export async function brandApk(
  inputApk: string,
  taskDir: string,
  opts: BrandOptions,
): Promise<string> {
  const paths = resolveSignerPaths();
  if (!fileOk(paths.editorJar)) {
    throw new Error('APKEditor.jar missing');
  }
  const label = opts.label.trim().slice(0, 40);
  if (!label) throw new Error('empty label');

  const decoded = join(taskDir, 'decoded');
  const branded = join(taskDir, 'branded.apk');
  mkdirSync(taskDir, { recursive: true });
  if (existsSync(decoded)) rmSync(decoded, { recursive: true, force: true });

  await runJava(
    paths.javaBin,
    ['-jar', paths.editorJar, 'd', '-i', inputApk, '-o', decoded, '-f', '-dex'],
    taskDir,
  );
  applyLabel(decoded, label);
  if (opts.iconPng && opts.iconPng.length > 0) {
    applyIcon(decoded, opts.iconPng);
  }
  if (opts.configJson) {
    applyConfig(decoded, opts.configJson);
  }
  applyVersion(decoded, (opts.versionName || '').trim(), opts.versionCode || 0);
  await runJava(
    paths.javaBin,
    ['-jar', paths.editorJar, 'b', '-i', decoded, '-o', branded, '-f'],
    taskDir,
  );
  if (!fileOk(branded)) {
    throw new Error('brand rebuild produced no apk');
  }
  return branded;
}
