import type { Metadata, Viewport } from 'next';
import { Vazirmatn } from 'next/font/google';
import type { PropsWithChildren } from 'react';

import { NativeBehavior } from '@/components/NativeBehavior';
import { Root } from '@/components/Root/Root';
import { APP_BG_COLOR } from '@/core/theme';

import './globals.css';

const vazirmatn = Vazirmatn({
  subsets: ['arabic', 'latin'],
  variable: '--font-vazirmatn',
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'حضور',
  description: 'داشبورد مدیریت حضور کاربران',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  themeColor: APP_BG_COLOR,
};

export default function RootLayout({ children }: PropsWithChildren) {
  return (
    <html lang="fa" dir="rtl" className={vazirmatn.variable} suppressHydrationWarning>
      <body>
        <NativeBehavior />
        <Root>{children}</Root>
      </body>
    </html>
  );
}
