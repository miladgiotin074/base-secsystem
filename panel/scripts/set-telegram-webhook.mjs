/**
 * Production: register webhook + Mini App menu button.
 * Usage: npm run bot:webhook
 */
import { createHash } from 'node:crypto';

import { botTokenFromEnv, loadEnvFiles, miniAppUrlFromEnv, telegramApi } from './telegram-env.mjs';

loadEnvFiles();

function webhookSecret(token) {
  const explicit = (process.env.TELEGRAM_WEBHOOK_SECRET || '').trim();
  if (/^[A-Za-z0-9_-]{1,256}$/.test(explicit)) return explicit;
  return createHash('sha256').update(`webhook:${token}`).digest('hex').slice(0, 48);
}

const token = botTokenFromEnv();
const miniAppUrl = miniAppUrlFromEnv();
const webhookUrl = (process.env.TELEGRAM_WEBHOOK_URL || (miniAppUrl ? `${miniAppUrl}/api/bot` : '')).trim();
const secret = token ? webhookSecret(token) : '';

if (!token) {
  console.error('TELEGRAM_BOT_TOKEN is missing.');
  process.exit(1);
}
if (!webhookUrl.startsWith('https://')) {
  console.error('Set TELEGRAM_WEBHOOK_URL or TELEGRAM_MINI_APP_URL to an https URL.');
  process.exit(1);
}

const webhook = await telegramApi(token, 'setWebhook', {
  url: webhookUrl,
  secret_token: secret,
  allowed_updates: ['message', 'callback_query'],
  drop_pending_updates: true,
});

const commands = await telegramApi(token, 'setMyCommands', { commands: [] });

let menu = null;
if (miniAppUrl.startsWith('https://')) {
  menu = await telegramApi(token, 'setChatMenuButton', {
    menu_button: {
      type: 'web_app',
      text: 'پنل کفتار',
      web_app: { url: miniAppUrl },
    },
  });
}

console.log('Webhook:', webhookUrl, webhook);
console.log('Commands:', commands);
console.log('Menu button:', menu ?? 'skipped');
