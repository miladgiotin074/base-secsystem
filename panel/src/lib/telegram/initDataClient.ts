'use client';

import { retrieveLaunchParams, retrieveRawInitData, retrieveRawLaunchParams } from '@tma.js/sdk-react';

const STORAGE_KEY = 'secsystem.initData';

type TelegramWebApp = {
  initData?: string;
};

export function cacheInitDataRaw(raw: string) {
  if (!raw) {
    return;
  }
  try {
    sessionStorage.setItem(STORAGE_KEY, raw);
  } catch {
    // ignore quota / private mode
  }
}

export function readInitDataRaw(): string {
  const fresh = readFreshInitData();
  if (fresh) {
    cacheInitDataRaw(fresh);
    return fresh;
  }
  try {
    return sessionStorage.getItem(STORAGE_KEY) || '';
  } catch {
    return '';
  }
}

function readFreshInitData(): string {
  try {
    const raw = retrieveRawInitData();
    if (raw) {
      return raw;
    }
  } catch {
    // not a Mini App yet
  }
  try {
    const fromLaunch = tgWebAppData(retrieveRawLaunchParams());
    if (fromLaunch) {
      return fromLaunch;
    }
  } catch {
    // ignore
  }
  try {
    const params = retrieveLaunchParams() as { initDataRaw?: string };
    if (params.initDataRaw) {
      return params.initDataRaw;
    }
  } catch {
    // ignore
  }
  if (typeof window === 'undefined') {
    return '';
  }
  const webApp = (window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }).Telegram?.WebApp?.initData || '';
  if (webApp) {
    return webApp;
  }
  return tgWebAppData(window.location.hash) || tgWebAppData(window.location.search);
}

function tgWebAppData(raw: string): string {
  if (!raw) {
    return '';
  }
  const query = raw.startsWith('#') || raw.startsWith('?') ? raw.slice(1) : raw;
  try {
    return new URLSearchParams(query).get('tgWebAppData') || '';
  } catch {
    return '';
  }
}
