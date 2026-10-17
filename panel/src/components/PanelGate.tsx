'use client';

import { init } from '@tma.js/sdk-react';
import { useEffect, useState, type PropsWithChildren } from 'react';

import { applyTelegramChrome } from '@/lib/telegram/chrome';
import { cacheInitDataRaw, readInitDataRaw } from '@/lib/telegram/initDataClient';

type MeResponse = {
  ok: boolean;
  role?: 'admin' | 'manager';
  ports?: string[];
  user?: { id: number; firstName?: string };
  realtimeApiUrl?: string;
};

export function PanelGate({ children }: PropsWithChildren) {
  const [state, setState] = useState<'loading' | 'denied' | 'ready'>('loading');
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        let initData = '';
        try {
          init();
          applyTelegramChrome();
          initData = readInitDataRaw();
          cacheInitDataRaw(initData);
        } catch {
          if (process.env.NODE_ENV === 'production') {
            if (!cancelled) {
              setError('این صفحه فقط داخل Telegram Mini App باز می‌شود.');
              setState('denied');
            }
            return;
          }
        }
        if (!initData && process.env.NODE_ENV === 'production') {
          if (!cancelled) {
            setError('این صفحه فقط داخل Telegram Mini App باز می‌شود.');
            setState('denied');
          }
          return;
        }
        const res = await fetch('/api/me', {
          headers: initData ? { 'x-telegram-init-data': initData } : {},
        });
        if (!res.ok) {
          if (!cancelled) {
            setError(res.status === 403 ? 'دسترسی پنل برای این حساب مجاز نیست.' : 'احراز هویت ناموفق.');
            setState('denied');
          }
          return;
        }
        const body = (await res.json()) as MeResponse;
        if (body.realtimeApiUrl) {
          sessionStorage.setItem('secsystem.realtimeApiUrl', body.realtimeApiUrl);
        }
        try {
          sessionStorage.setItem('secsystem.panelRole', body.role || 'admin');
          sessionStorage.setItem('secsystem.panelPorts', JSON.stringify(body.ports || []));
        } catch {
          /* ignore */
        }
        if (!cancelled) {
          setState('ready');
        }
      } catch {
        if (!cancelled) {
          setError('اتصال به سرور پنل برقرار نشد.');
          setState('denied');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (state === 'loading') {
    return (
      <div className="md-state">
        <div className="md-progress" />
        <p>در حال بارگذاری</p>
      </div>
    );
  }
  if (state === 'denied') {
    return (
      <div className="md-state">
        <p>{error || 'دسترسی رد شد.'}</p>
      </div>
    );
  }
  return children;
}
