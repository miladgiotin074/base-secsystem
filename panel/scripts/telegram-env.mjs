import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export function loadEnvFiles() {
  for (const file of ['.env.local', '.env.production', '.env']) {
    const path = resolve(process.cwd(), file);
    if (!existsSync(path)) continue;
    for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;
      const eq = trimmed.indexOf('=');
      if (eq < 1) continue;
      const key = trimmed.slice(0, eq).trim();
      let value = trimmed.slice(eq + 1).trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      if (process.env[key] === undefined) process.env[key] = value;
    }
  }
}

export function botTokenFromEnv() {
  return (process.env.TELEGRAM_BOT_TOKEN || '').trim();
}

export function miniAppUrlFromEnv() {
  return (process.env.TELEGRAM_MINI_APP_URL || process.env.RENDER_EXTERNAL_URL || '')
    .trim()
    .replace(/\/+$/, '');
}

export async function telegramApi(token, method, body) {
  const init = body === undefined
    ? { method: 'GET' }
    : {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      };
  const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, init);
  const data = await response.json();
  if (!data.ok) {
    throw new Error(`${method}: ${data.description || JSON.stringify(data)}`);
  }
  return data.result;
}
