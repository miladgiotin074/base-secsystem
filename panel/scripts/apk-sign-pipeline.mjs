/**
 * Shared APK protect/sign pipeline for bot:poll (no Next required).
 */
import { randomBytes, createHash } from 'node:crypto';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { basename, extname, join, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const execFileAsync = promisify(execFile);
const SIG_LOCAL = 0x04034b50;
const SIG_EOCD = 0x06054b50;
const SIG_CD = 0x02014b50;

function u16(buf, off) {
  return buf.readUInt16LE(off);
}
function u32(buf, off) {
  return buf.readUInt32LE(off);
}

function putU16(n) {
  const b = Buffer.alloc(2);
  b.writeUInt16LE(n, 0);
  return b;
}
function putU32(n) {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(n >>> 0, 0);
  return b;
}

function findEocd(buf) {
  const min = Math.max(0, buf.length - (22 + 65535));
  for (let i = buf.length - 22; i >= min; i--) {
    if (u32(buf, i) === SIG_EOCD) return i;
  }
  throw new Error('EOCD not found');
}

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? (0xedb88320 ^ (c >>> 1)) : c >>> 1;
    }
  }
  return (c ^ 0xffffffff) >>> 0;
}

function appendStoreEntries(apkPath, extras) {
  if (!extras.length) return;
  const data = readFileSync(apkPath);
  const eocd = findEocd(data);
  const cdSize = u32(data, eocd + 12);
  const cdOffset = u32(data, eocd + 16);
  const totalEntries = u16(data, eocd + 10);
  if (cdOffset + cdSize > eocd) throw new Error('invalid zip central directory');

  const existingNames = new Set();
  let pos = cdOffset;
  while (pos + 46 <= cdOffset + cdSize) {
    if (u32(data, pos) !== SIG_CD) break;
    const nameLen = u16(data, pos + 28);
    const extraLen = u16(data, pos + 30);
    const commentLen = u16(data, pos + 32);
    existingNames.add(data.subarray(pos + 46, pos + 46 + nameLen).toString('utf8'));
    pos += 46 + nameLen + extraLen + commentLen;
  }

  const toAdd = extras.filter((e) => !existingNames.has(e.name));
  if (!toAdd.length) return;

  const beforeCd = data.subarray(0, cdOffset);
  const oldCd = data.subarray(cdOffset, cdOffset + cdSize);
  const chunks = [beforeCd];
  const newCdParts = [oldCd];
  let localCursor = beforeCd.length;

  for (const entry of toAdd) {
    const nameBuf = Buffer.from(entry.name, 'utf8');
    const crc = crc32(entry.data);
    const size = entry.data.length;
    const local = Buffer.concat([
      putU32(SIG_LOCAL),
      putU16(20),
      putU16(0),
      putU16(0),
      putU16(0),
      putU16(0),
      putU32(crc),
      putU32(size),
      putU32(size),
      putU16(nameBuf.length),
      putU16(0),
      nameBuf,
      entry.data,
    ]);
    const localOffset = localCursor;
    chunks.push(local);
    localCursor += local.length;
    newCdParts.push(
      Buffer.concat([
        putU32(SIG_CD),
        putU16(20),
        putU16(20),
        putU16(0),
        putU16(0),
        putU16(0),
        putU16(0),
        putU32(crc),
        putU32(size),
        putU32(size),
        putU16(nameBuf.length),
        putU16(0),
        putU16(0),
        putU16(0),
        putU16(0),
        putU32(0),
        putU32(localOffset),
        nameBuf,
      ]),
    );
  }

  const newCd = Buffer.concat(newCdParts);
  const newTotal = totalEntries + toAdd.length;
  if (newTotal > 0xffff) throw new Error('too many zip entries');
  const newEocd = Buffer.concat([
    putU32(SIG_EOCD),
    putU16(0),
    putU16(0),
    putU16(newTotal),
    putU16(newTotal),
    putU32(newCd.length),
    putU32(localCursor),
    putU16(0),
  ]);
  writeFileSync(apkPath, Buffer.concat([...chunks, newCd, newEocd]));
}

function fileOk(path) {
  try {
    return existsSync(path) && statSync(path).size > 0;
  } catch {
    return false;
  }
}

function randToken(n) {
  return randomBytes(Math.ceil(n / 2))
    .toString('hex')
    .slice(0, n);
}

function defaultSignerRoot() {
  const here = fileURLToPath(new URL('.', import.meta.url)); // panel/scripts
  const candidates = [
    process.env.APK_SIGNER_DIR,
    resolve(here, '..', '..', 'tools', 'apksigner'),
    resolve(process.cwd(), '..', 'tools', 'apksigner'),
    resolve(process.cwd(), 'tools', 'apksigner'),
  ].filter(Boolean);
  for (const dir of candidates) {
    if (
      fileOk(join(dir, 'uber-apk-signer-1.3.0.jar')) ||
      fileOk(join(dir, 'uber-apk-signer.jar')) ||
      fileOk(join(dir, 'APKEditor.jar'))
    ) {
      return dir;
    }
  }
  return resolve(here, '..', '..', 'tools', 'apksigner');
}

function resolveSignerPaths() {
  const root = defaultSignerRoot();
  const uberDefault = fileOk(join(root, 'uber-apk-signer-1.3.0.jar'))
    ? join(root, 'uber-apk-signer-1.3.0.jar')
    : join(root, 'uber-apk-signer.jar');
  return {
    workRoot: join(root, 'work'),
    uberJar: (process.env.UBER_SIGNER_JAR || '').trim() || uberDefault,
    editorJar: (process.env.APKEDITOR_JAR || '').trim() || join(root, 'APKEditor.jar'),
    javaBin: (process.env.JAVA_BIN || '').trim() || 'java',
  };
}

async function runJavaJar(java, args, cwd) {
  try {
    await execFileAsync(java, args, {
      cwd,
      timeout: 300_000,
      maxBuffer: 8 * 1024 * 1024,
    });
  } catch (err) {
    const detail = `${err.stderr || err.stdout || err.message || err}`.trim().slice(0, 400);
    throw new Error(detail || 'java failed');
  }
}

function addDummyFiles(apkPath) {
  const noise = () => Buffer.from(randomBytes(100).toString('hex'), 'utf8');
  const extras = [];
  for (let i = 0; i < 5; i++) {
    extras.push({ name: `assets/dummy_${i}.bin`, data: noise() });
  }
  appendStoreEntries(apkPath, extras);
}

export async function processApk(inputPath) {
  const paths = resolveSignerPaths();
  if (!fileOk(paths.uberJar)) {
    throw new Error(`uber-apk-signer jar missing — put it in ${paths.uberJar}`);
  }
  mkdirSync(paths.workRoot, { recursive: true });
  const taskDir = join(paths.workRoot, `${Date.now()}_${randToken(6)}`);
  mkdirSync(taskDir, { recursive: true });
  const apk = join(taskDir, 'work.apk');
  copyFileSync(inputPath, apk);

  // No zip CD "encrypted" flags — they survive resign and crash at runtime.
  addDummyFiles(apk);

  if (fileOk(paths.editorJar)) {
    const protectedPath = join(taskDir, 'protected.apk');
    await runJavaJar(
      paths.javaBin,
      ['-jar', paths.editorJar, 'p', '-i', apk, '-o', protectedPath],
      taskDir,
    );
    if (!fileOk(protectedPath)) {
      throw new Error('APKEditor produced no output');
    }
    copyFileSync(protectedPath, apk);
  }

  await runJavaJar(
    paths.javaBin,
    ['-jar', paths.uberJar, '--apks', apk, '--out', taskDir, '--allowResign'],
    taskDir,
  );

  const base = basename(apk, extname(apk));
  const files = readdirSync(taskDir);
  const signed =
    files.find(
      (f) =>
        f.startsWith(base) &&
        (f.endsWith('-aligned-debugSigned.apk') ||
          f.endsWith('-aligned-signed.apk') ||
          f.endsWith('-signed.apk')),
    ) ||
    files.find(
      (f) =>
        f.endsWith('-aligned-debugSigned.apk') ||
        f.endsWith('-aligned-signed.apk') ||
        f.endsWith('-signed.apk'),
    );
  if (!signed || !fileOk(join(taskDir, signed))) {
    throw new Error('signed apk not found after uber-apk-signer');
  }
  const fileName = `Greed-team${randToken(3)}.apk`;
  const outPath = join(taskDir, fileName);
  copyFileSync(join(taskDir, signed), outPath);
  return { path: outPath, fileName, taskDir, cleanup: () => rmSync(taskDir, { recursive: true, force: true }) };
}

/** Same derivation as panel webhookSecret.ts */
export function telegramWebhookSecret(botToken) {
  const explicit = (process.env.TELEGRAM_WEBHOOK_SECRET || '').trim();
  if (/^[A-Za-z0-9_-]{1,256}$/.test(explicit)) return explicit;
  const t = (botToken || process.env.TELEGRAM_BOT_TOKEN || '').trim();
  if (!t) return '';
  return createHash('sha256').update(`webhook:${t}`).digest('hex').slice(0, 48);
}
