function trimUrl(value: string | undefined): string {
  return (value || '').trim().replace(/\/+$/, '');
}

export function publicAppUrl(): string | null {
  for (const raw of [process.env.TELEGRAM_MINI_APP_URL, process.env.RENDER_EXTERNAL_URL]) {
    const url = trimUrl(raw);
    if (url.startsWith('https://')) {
      return url;
    }
  }
  return null;
}

export function publicAppUrlFromRequest(request: Request): string | null {
  const configured = publicAppUrl();
  if (configured) {
    return configured;
  }
  const host = (request.headers.get('x-forwarded-host') || request.headers.get('host') || '')
    .split(',')[0]
    .trim();
  const proto = (request.headers.get('x-forwarded-proto') || 'https').split(',')[0].trim();
  if (host && proto === 'https' && !host.startsWith('localhost') && !host.startsWith('127.0.0.1')) {
    return `https://${host}`;
  }
  return null;
}
