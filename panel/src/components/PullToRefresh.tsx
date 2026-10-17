"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

const THRESHOLD = 56;

function nestedScrollTop(target: EventTarget | null, root: HTMLElement): number {
  let node = target instanceof Element ? target : null;
  while (node && node !== root) {
    const style = window.getComputedStyle(node);
    const y = style.overflowY;
    if ((y === "auto" || y === "scroll" || y === "overlay") && node.scrollHeight > node.clientHeight + 1) {
      return node.scrollTop;
    }
    node = node.parentElement;
  }
  return root.scrollTop;
}

export function PullToRefresh({
  onRefresh,
  children,
}: {
  onRefresh: () => Promise<void> | void;
  children: ReactNode;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const startY = useRef(0);
  const pulling = useRef(false);
  const armed = useRef(false);
  const offsetRef = useRef(0);
  const busyRef = useRef(false);
  const onRefreshRef = useRef(onRefresh);
  const [offset, setOffset] = useState(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    onRefreshRef.current = onRefresh;
  }, [onRefresh]);

  useEffect(() => {
    busyRef.current = busy;
  }, [busy]);

  useEffect(() => {
    const root = scroller.current;
    if (!root) return;

    function setPull(next: number) {
      offsetRef.current = next;
      setOffset(next);
    }

    function onTouchStart(event: TouchEvent) {
      if (busyRef.current || !root) {
        pulling.current = false;
        armed.current = false;
        return;
      }
      if (nestedScrollTop(event.target, root) > 0) {
        pulling.current = false;
        armed.current = false;
        return;
      }
      startY.current = event.touches[0]?.clientY ?? 0;
      pulling.current = true;
      armed.current = false;
    }

    function onTouchMove(event: TouchEvent) {
      if (!pulling.current || busyRef.current || !root) return;
      if (nestedScrollTop(event.target, root) > 0) {
        pulling.current = false;
        armed.current = false;
        setPull(0);
        return;
      }
      const dy = (event.touches[0]?.clientY ?? 0) - startY.current;
      if (dy <= 8) {
        if (dy <= 0) {
          armed.current = false;
          setPull(0);
        }
        return;
      }
      armed.current = true;
      if (event.cancelable) event.preventDefault();
      setPull(Math.min(dy * 0.45, 72));
    }

    async function onTouchEnd() {
      const reached = armed.current && offsetRef.current >= THRESHOLD;
      pulling.current = false;
      armed.current = false;
      if (!reached || busyRef.current) {
        setPull(0);
        return;
      }
      busyRef.current = true;
      setBusy(true);
      setPull(THRESHOLD);
      try {
        await onRefreshRef.current();
      } finally {
        busyRef.current = false;
        setBusy(false);
        setPull(0);
      }
    }

    function onTouchCancel() {
      pulling.current = false;
      armed.current = false;
      if (!busyRef.current) setPull(0);
    }

    root.addEventListener("touchstart", onTouchStart, { passive: true });
    root.addEventListener("touchmove", onTouchMove, { passive: false });
    root.addEventListener("touchend", onTouchEnd);
    root.addEventListener("touchcancel", onTouchCancel);
    return () => {
      root.removeEventListener("touchstart", onTouchStart);
      root.removeEventListener("touchmove", onTouchMove);
      root.removeEventListener("touchend", onTouchEnd);
      root.removeEventListener("touchcancel", onTouchCancel);
    };
  }, []);

  return (
    <div ref={scroller} className="md-pull">
      <div className={`md-pull__slot${busy || offset > 12 ? " md-pull__slot--on" : ""}`} style={{ height: offset }} aria-hidden={!busy && offset === 0}>
        {(busy || offset > 12) && <div className="md-progress" />}
      </div>
      <div className="md-pull__body">{children}</div>
    </div>
  );
}
