'use client';

import { retrieveLaunchParams } from '@tma.js/sdk-react';

export function readStartParam(): string {
  try {
    const params = retrieveLaunchParams();
    const fromSdk = (params.tgWebAppStartParam || '').trim();
    if (fromSdk) {
      return fromSdk;
    }
  } catch {
    // outside Telegram
  }
  if (typeof window === 'undefined') {
    return '';
  }
  const fromQuery = paramFrom(window.location.search) || paramFrom(window.location.hash);
  return fromQuery;
}

export function userTokenFromStart(param: string): string {
  const value = param.trim();
  if (!value.startsWith('u_')) {
    return '';
  }
  const token = value.slice(2);
  return /^[0-9a-fA-F]{16}$/.test(token) ? token.toLowerCase() : '';
}

/** startapp=apk_<portCode> opens APK build screen for that port. */
export function apkBuildFromStart(param: string): string {
  const value = param.trim();
  if (!value.startsWith('apk_')) {
    return '';
  }
  const code = value.slice(4);
  return /^[A-Za-z0-9]{20}$/.test(code) ? code : '';
}

/** startapp=ws_<setToken>_<deviceId> opens user sessions. */
export function watchOpenFromStart(param: string): { token: string; deviceId: string } | null {
  const value = param.trim();
  if (!value.startsWith('ws_')) {
    return null;
  }
  const rest = value.slice(3);
  if (rest.length < 18 || rest[16] !== '_') {
    return null;
  }
  const token = rest.slice(0, 16);
  const deviceId = rest.slice(17).trim();
  if (!/^[0-9a-fA-F]{16}$/.test(token) || !deviceId) {
    return null;
  }
  return { token: token.toLowerCase(), deviceId };
}

function paramFrom(raw: string): string {
  if (!raw) {
    return '';
  }
  const query = raw.startsWith('#') || raw.startsWith('?') ? raw.slice(1) : raw;
  try {
    const params = new URLSearchParams(query);
    return (params.get('tgWebAppStartParam') || params.get('startapp') || '').trim();
  } catch {
    return '';
  }
}
