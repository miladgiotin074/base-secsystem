import { downloadTelegramFile } from '@/server/telegram/botApi';

const maxPhotoBytes = 512 * 1024;

export async function loadTelegramPhotoPayload(
  fileId: string,
): Promise<{ data: string; mime: string } | null> {
  const id = fileId.trim();
  if (!id || /[\s/?#]/.test(id) || id.length > 512) {
    return null;
  }
  const file = await downloadTelegramFile(id);
  if (!file?.ok || !file.body) {
    return null;
  }
  const mime = normalizePhotoMime(file.headers.get('content-type') || '');
  if (!mime) {
    return null;
  }
  const bytes = Buffer.from(await file.arrayBuffer());
  if (bytes.length === 0 || bytes.length > maxPhotoBytes) {
    return null;
  }
  return { data: bytes.toString('base64'), mime };
}

function normalizePhotoMime(value: string): string {
  const mime = value.split(';')[0]?.trim().toLowerCase() || '';
  if (mime === 'image/jpg') {
    return 'image/jpeg';
  }
  if (mime === 'image/jpeg' || mime === 'image/png' || mime === 'image/webp') {
    return mime;
  }
  return 'image/jpeg';
}
