import { postEvent } from '@tma.js/sdk-react';

type TelegramWebApp = {
  requestChat?: (id: string, callback?: (ok: boolean) => void) => void;
};

export function requestTelegramGroup(preparedId: string): Promise<boolean> {
  const webApp = (window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }).Telegram?.WebApp;
  if (typeof webApp?.requestChat === 'function') {
    return new Promise((resolve) => {
      try {
        webApp.requestChat?.(preparedId, (ok) => resolve(Boolean(ok)));
      } catch {
        resolve(false);
      }
    });
  }
  try {
    postEvent('web_app_request_chat' as never, { req_id: preparedId } as never);
    return Promise.resolve(true);
  } catch {
    return Promise.resolve(false);
  }
}
