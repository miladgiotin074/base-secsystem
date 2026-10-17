import { validate } from '@tma.js/init-data-node';

export type TelegramWebAppUser = {
  id: number;
  first_name?: string;
  username?: string;
};

export function peekInitDataUser(raw: string): TelegramWebAppUser | null {
  if (!raw.trim()) {
    return null;
  }
  try {
    const userJson = new URLSearchParams(raw).get('user');
    if (!userJson) {
      return null;
    }
    const user = JSON.parse(userJson) as TelegramWebAppUser;
    return user?.id ? user : null;
  } catch {
    return null;
  }
}

export function parseInitData(raw: string): TelegramWebAppUser | null {
  const token = (process.env.TELEGRAM_BOT_TOKEN || '').trim();
  if (!token || !raw.trim()) {
    return null;
  }

  try {
    validate(raw, token, { expiresIn: 86_400 });
  } catch {
    return null;
  }

  return peekInitDataUser(raw);
}

export function initDataFromRequest(request: Request): string {
  return (
    request.headers.get('x-telegram-init-data') ||
    request.headers.get('authorization')?.replace(/^tma\s+/i, '') ||
    ''
  );
}
