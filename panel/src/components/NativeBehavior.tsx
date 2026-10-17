'use client';

import { useEffect } from 'react';

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}

const GESTURE_START = 'gesturestart' as keyof DocumentEventMap;

export function NativeBehavior() {
  useEffect(() => {
    const prevent = (event: Event) => event.preventDefault();
    const preventUnlessEditable = (event: Event) => {
      if (!isEditable(event.target)) {
        event.preventDefault();
      }
    };
    document.addEventListener('contextmenu', prevent);
    document.addEventListener('dragstart', prevent);
    document.addEventListener(GESTURE_START, prevent);
    document.addEventListener('selectstart', preventUnlessEditable);
    return () => {
      document.removeEventListener('contextmenu', prevent);
      document.removeEventListener('dragstart', prevent);
      document.removeEventListener(GESTURE_START, prevent);
      document.removeEventListener('selectstart', preventUnlessEditable);
    };
  }, []);
  return null;
}
