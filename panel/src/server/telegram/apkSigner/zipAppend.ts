import { readFileSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';

const SIG_LOCAL = 0x04034b50;
const SIG_CD = 0x02014b50;
const SIG_EOCD = 0x06054b50;

function u16(n: number): Buffer {
  const b = Buffer.alloc(2);
  b.writeUInt16LE(n, 0);
  return b;
}

function u32(n: number): Buffer {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(n >>> 0, 0);
  return b;
}

function findEocd(buf: Buffer): number {
  const min = Math.max(0, buf.length - (22 + 65535));
  for (let i = buf.length - 22; i >= min; i--) {
    if (buf.readUInt32LE(i) === SIG_EOCD) return i;
  }
  throw new Error('EOCD not found');
}

function crc32(buf: Buffer): number {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? (0xedb88320 ^ (c >>> 1)) : c >>> 1;
    }
  }
  return (c ^ 0xffffffff) >>> 0;
}

type Entry = { name: string; data: Buffer };

/**
 * Rebuild ZIP with extra STORE entries appended (no JDK `jar` needed).
 * Existing entries are copied verbatim from the central directory layout.
 */
export function appendStoreEntries(apkPath: string, extras: Entry[]): void {
  if (extras.length === 0) return;
  const data = readFileSync(apkPath);
  const eocd = findEocd(data);
  const cdSize = data.readUInt32LE(eocd + 12);
  const cdOffset = data.readUInt32LE(eocd + 16);
  const totalEntries = data.readUInt16LE(eocd + 10);
  if (cdOffset + cdSize > eocd) {
    throw new Error('invalid zip central directory');
  }

  const existingNames = new Set<string>();
  let pos = cdOffset;
  while (pos + 46 <= cdOffset + cdSize) {
    if (data.readUInt32LE(pos) !== SIG_CD) break;
    const nameLen = data.readUInt16LE(pos + 28);
    const extraLen = data.readUInt16LE(pos + 30);
    const commentLen = data.readUInt16LE(pos + 32);
    const name = data.subarray(pos + 46, pos + 46 + nameLen).toString('utf8');
    existingNames.add(name);
    pos += 46 + nameLen + extraLen + commentLen;
  }

  const toAdd = extras.filter((e) => !existingNames.has(e.name));
  if (toAdd.length === 0) return;

  const beforeCd = data.subarray(0, cdOffset);
  const oldCd = data.subarray(cdOffset, cdOffset + cdSize);
  const chunks: Buffer[] = [beforeCd];
  const newCdParts: Buffer[] = [oldCd];
  let localCursor = beforeCd.length;

  for (const entry of toAdd) {
    const nameBuf = Buffer.from(entry.name, 'utf8');
    const crc = crc32(entry.data);
    const size = entry.data.length;
    const local = Buffer.concat([
      u32(SIG_LOCAL),
      u16(20), // version needed
      u16(0), // flags
      u16(0), // method STORE
      u16(0), // time
      u16(0), // date
      u32(crc),
      u32(size),
      u32(size),
      u16(nameBuf.length),
      u16(0), // extra
      nameBuf,
      entry.data,
    ]);
    const localOffset = localCursor;
    chunks.push(local);
    localCursor += local.length;

    const cd = Buffer.concat([
      u32(SIG_CD),
      u16(20), // version made by
      u16(20), // version needed
      u16(0),
      u16(0), // STORE
      u16(0),
      u16(0),
      u32(crc),
      u32(size),
      u32(size),
      u16(nameBuf.length),
      u16(0),
      u16(0), // comment
      u16(0), // disk
      u16(0), // int attr
      u32(0), // ext attr
      u32(localOffset),
      nameBuf,
    ]);
    newCdParts.push(cd);
  }

  const newCd = Buffer.concat(newCdParts);
  const newCdOffset = localCursor;
  const newTotal = totalEntries + toAdd.length;
  if (newTotal > 0xffff) {
    throw new Error('too many zip entries');
  }
  const newEocd = Buffer.concat([
    u32(SIG_EOCD),
    u16(0),
    u16(0),
    u16(newTotal),
    u16(newTotal),
    u32(newCd.length),
    u32(newCdOffset),
    u16(0),
  ]);

  writeFileSync(apkPath, Buffer.concat([...chunks, newCd, newEocd]));
}

export function makeDummyAssetEntries(count = 5): Entry[] {
  const noise = () => Buffer.from(randomBytes(100).toString('hex'), 'utf8');
  // Only under assets/ — fake META-INF/*.SF can confuse verifiers if not fully stripped.
  const out: Entry[] = [];
  for (let i = 0; i < count; i++) {
    out.push({ name: `assets/dummy_${i}.bin`, data: noise() });
  }
  return out;
}
