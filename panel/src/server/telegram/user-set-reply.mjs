function escapeHtml(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/** RTL-safe field line; LTR mark keeps technical values aligned. */
function field(label, value) {
  const text = String(value || '').trim();
  if (!text) return null;
  return `<b>${escapeHtml(label)}</b>: \u200E${escapeHtml(text)}`;
}

function iconLabel(state) {
  if (state === 'hidden') return 'مخفی';
  if (state === 'changed') return 'عوض‌شده';
  if (state === 'normal') return 'معمولی';
  return state ? 'معمولی' : '';
}

function deviceTitle(device) {
  const parts = [device.manufacturer, device.model].map((part) => String(part || '').trim()).filter(Boolean);
  return parts.join(' ') || String(device.device || '').trim();
}

function localeLine(device) {
  const locale = String(device.locale || '').trim();
  const tz = String(device.timeZone || '').trim();
  if (locale && tz) return `${locale} · ${tz}`;
  return locale || tz;
}

function setCommandFooter(setToken) {
  const token = String(setToken || '').trim().toLowerCase();
  if (!/^[0-9a-f]{16}$/.test(token)) return '';
  return `\n\n──────────────\n<b>دستور تنظیم</b>:\n<code>/set_${escapeHtml(token)}</code>`;
}

function joinLines(parts) {
  return parts.filter((line) => line !== null && line !== undefined && line !== '').join('\n');
}

export const MANAGE_USER_BUTTON_TEXT = '👤 مدیریت کاربر';

export function userSpecsHtml(card) {
  const blocks = [
    '👤 <b>مشخصات کاربر</b>',
    '',
    field('نام کاربر', card.displayName),
    field('توضیحات', card.description),
    field('شناسه کاربر', card.userId),
  ];

  for (const device of card.devices || []) {
    const android = device.androidRelease
      ? device.sdk
        ? `${device.androidRelease} (API ${device.sdk})`
        : device.androidRelease
      : '';
    const operator = String(device.operator || '').trim() || String(device.simOperator || '').trim();
    const portName = String(device.portName || device.port || '').trim();
    blocks.push(
      '',
      '📱 <b>دستگاه</b>',
      '',
      field('نام اپ', device.appLabel),
      field('مدل', deviceTitle(device)),
      field('آیکن', iconLabel(device.iconState) || 'معمولی'),
      field('اپراتور', operator),
      field('IP', device.clientIp),
      field('پورت', portName),
      field('شناسه دستگاه', device.deviceId),
      field('اندروید', android),
      field('زبان', localeLine(device)),
      field('پکیج', device.packageName),
    );
  }

  return joinLines(blocks) + setCommandFooter(card.setToken);
}

export function manageKeyboard(botUsername, setToken) {
  const user = String(botUsername || '').replace(/^@/, '');
  const token = String(setToken || '').trim();
  if (!user || !token) return undefined;
  return {
    inline_keyboard: [[{ text: MANAGE_USER_BUTTON_TEXT, url: `https://t.me/${user}?startapp=u_${token}` }]],
  };
}
