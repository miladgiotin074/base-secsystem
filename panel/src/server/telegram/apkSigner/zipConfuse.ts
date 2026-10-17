import { readFileSync, writeFileSync } from 'node:fs';

const SIG_EOCD = 0x06054b50;
const SIG_CD = 0x02014b50;

function u16(buf: Buffer, off: number): number {
  return buf.readUInt16LE(off);
}

function u32(buf: Buffer, off: number): number {
  return buf.readUInt32LE(off);
}

function findEocd(buf: Buffer): number {
  const min = Math.max(0, buf.length - (22 + 65535));
  for (let i = buf.length - 22; i >= min; i--) {
    if (u32(buf, i) === SIG_EOCD) return i;
  }
  throw new Error('EOCD not found');
}

/**
 * Soft confusion: only set the CD “encrypted” flag.
 * Do NOT randomize compression method/time — that desyncs CD vs local headers
 * and breaks APKEditor / uber-apk-signer / installers.
 */
export function applyZipConfusion(apkPath: string, entryLimit = 10): void {
  const data = Buffer.from(readFileSync(apkPath));
  const eocd = findEocd(data);
  let pos = u32(data, eocd + 16);
  if (pos < 0 || pos >= data.length) {
    throw new Error('invalid central directory offset');
  }
  let touched = 0;
  for (let i = 0; i < entryLimit; i++) {
    if (pos + 46 > data.length) break;
    if (u32(data, pos) !== SIG_CD) break;
    const gpbf = u16(data, pos + 8);
    data.writeUInt16LE(gpbf | 0x0001, pos + 8);
    touched++;
    const nameLen = u16(data, pos + 28);
    const extraLen = u16(data, pos + 30);
    const commentLen = u16(data, pos + 32);
    const next = pos + 46 + nameLen + extraLen + commentLen;
    if (next <= pos || next > data.length) break;
    pos = next;
  }
  if (touched === 0) {
    throw new Error('no central directory entries to confuse');
  }
  writeFileSync(apkPath, data);
}

export function isZipApk(buf: Buffer): boolean {
  return buf.length >= 4 && buf[0] === 0x50 && buf[1] === 0x4b && (buf[2] === 0x03 || buf[2] === 0x05 || buf[2] === 0x07);
}
