export function adminTelegramIds(): Set<number> {
  const raw = (process.env.ADMIN_TELEGRAM_ID || '').trim();
  const ids = new Set<number>();

  for (const part of raw.split(',')) {
    const n = Number(part.trim());
    if (Number.isFinite(n) && n > 0) {
      ids.add(n);
    }
  }

  return ids;
}

export function isAdminTelegramUser(userId: number): boolean {
  const allowed = adminTelegramIds();
  if (allowed.size === 0) {
    return process.env.NODE_ENV !== 'production';
  }
  return allowed.has(userId);
}
