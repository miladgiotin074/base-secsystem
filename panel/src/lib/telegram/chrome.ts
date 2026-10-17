import { closingBehavior, miniApp, swipeBehavior, viewport } from '@tma.js/sdk-react';

import { APP_BG_COLOR } from '@/core/theme';

export function applyTelegramChrome() {
  miniApp.mount.ifAvailable();
  if (miniApp.setHeaderColor.isAvailable()) {
    if (miniApp.setHeaderColor.supports('rgb')) {
      miniApp.setHeaderColor(APP_BG_COLOR);
    } else {
      miniApp.setHeaderColor('bg_color');
    }
  }
  miniApp.setBgColor.ifAvailable(APP_BG_COLOR);
  miniApp.setBottomBarColor.ifAvailable(APP_BG_COLOR);
  miniApp.ready.ifAvailable();

  closingBehavior.mount.ifAvailable();
  closingBehavior.enableConfirmation.ifAvailable();

  swipeBehavior.mount.ifAvailable();
  swipeBehavior.disableVertical.ifAvailable();

  const expand = () => {
    viewport.expand.ifAvailable();
  };
  const mounted = viewport.mount.ifAvailable();
  if (mounted.ok) {
    void Promise.resolve(mounted.data).then(expand, expand);
    return;
  }
  expand();
}
