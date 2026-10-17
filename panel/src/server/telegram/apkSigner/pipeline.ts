import { randomBytes } from 'node:crypto';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { join, basename, extname } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { appendStoreEntries, makeDummyAssetEntries } from '@/server/telegram/apkSigner/zipAppend';

const execFileAsync = promisify(execFile);

export type SignerPaths = {
  workRoot: string;
  uberJar: string;
  editorJar: string;
  javaBin: string;
};

/** Prefer repo `tools/apksigner` over accidental `panel/tools/apk-signer`. */
export function signerRootCandidates(): string[] {
  const cwd = process.cwd();
  const env = (process.env.APK_SIGNER_DIR || '').trim();
  return [
    env,
    join(cwd, '..', 'tools', 'apksigner'),
    join(cwd, '..', 'tools', 'apk-signer'),
    join(cwd, 'tools', 'apksigner'),
    join(cwd, 'tools', 'apk-signer'),
  ].filter(Boolean);
}

function defaultSignerRoot(): string {
  for (const dir of signerRootCandidates()) {
    if (
      existsSync(join(dir, 'uber-apk-signer-1.3.0.jar')) ||
      existsSync(join(dir, 'uber-apk-signer.jar')) ||
      existsSync(join(dir, 'APKEditor.jar'))
    ) {
      return dir;
    }
  }
  return join(process.cwd(), '..', 'tools', 'apksigner');
}

export function resolveSignerPaths(): SignerPaths {
  const root = (process.env.APK_SIGNER_DIR || '').trim() || defaultSignerRoot();
  const uberDefault = existsSync(join(root, 'uber-apk-signer-1.3.0.jar'))
    ? join(root, 'uber-apk-signer-1.3.0.jar')
    : join(root, 'uber-apk-signer.jar');
  return {
    workRoot: join(root, 'work'),
    uberJar: (process.env.UBER_SIGNER_JAR || '').trim() || uberDefault,
    editorJar: (process.env.APKEDITOR_JAR || '').trim() || join(root, 'APKEditor.jar'),
    javaBin: (process.env.JAVA_BIN || '').trim() || 'java',
  };
}

function fileOk(path: string): boolean {
  try {
    return existsSync(path) && statSync(path).size > 0;
  } catch {
    return false;
  }
}

function randToken(n: number): string {
  return randomBytes(Math.ceil(n / 2))
    .toString('hex')
    .slice(0, n);
}

async function runJavaJar(java: string, args: string[], cwd: string): Promise<void> {
  try {
    await execFileAsync(java, args, {
      cwd,
      timeout: 300_000,
      maxBuffer: 8 * 1024 * 1024,
    });
  } catch (err) {
    const e = err as { stdout?: string; stderr?: string; message?: string };
    const detail = `${e.stderr || e.stdout || e.message || err}`.trim().slice(0, 400);
    throw new Error(detail || 'java failed');
  }
}

/** Noise META-INF + assets via pure ZIP append (no JDK `jar`). */
function addDummyFiles(apkPath: string): void {
  appendStoreEntries(apkPath, makeDummyAssetEntries(5));
}

export type ProcessResult = { path: string; fileName: string; taskDir: string };

export type ProcessApkOptions = {
  outFileName?: string;
  /** When set, use this work dir instead of creating a new one. */
  reuseTaskDir?: string;
};

export async function processApk(
  inputPath: string,
  opts: ProcessApkOptions = {},
): Promise<ProcessResult> {
  const paths = resolveSignerPaths();
  if (!fileOk(paths.uberJar)) {
    throw new Error(`uber-apk-signer jar missing — put it in ${paths.uberJar}`);
  }
  mkdirSync(paths.workRoot, { recursive: true });
  const taskDir =
    opts.reuseTaskDir || join(paths.workRoot, `${Date.now()}_${randToken(6)}`);
  mkdirSync(taskDir, { recursive: true });
  const apk = join(taskDir, 'work.apk');
  copyFileSync(inputPath, apk);

  // Do not apply zip "encrypted" CD flags on installable APKs — Android
  // libziparchive refuses those entries at runtime and the process dies after launch.
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
  const rawName = (opts.outFileName || `Greed-team${randToken(3)}.apk`).trim();
  const fileName = rawName.toLowerCase().endsWith('.apk') ? rawName : `${rawName}.apk`;
  const outPath = join(taskDir, fileName);
  copyFileSync(join(taskDir, signed), outPath);
  return { path: outPath, fileName, taskDir };
}

export function saveUpload(buffer: Buffer): string {
  const root = resolveSignerPaths().workRoot;
  mkdirSync(root, { recursive: true });
  const path = join(root, `in_${Date.now()}_${randToken(6)}.apk`);
  writeFileSync(path, buffer);
  return path;
}

export function cleanupTaskDir(taskDir: string): void {
  try {
    rmSync(taskDir, { recursive: true, force: true });
  } catch {
    /* ignore */
  }
}
