'use client';

import type { PropsWithChildren } from 'react';

import { PanelGate } from '@/components/PanelGate';

import './styles.css';

export function Root({ children }: PropsWithChildren) {
  return <PanelGate>{children}</PanelGate>;
}
