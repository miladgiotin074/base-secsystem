"use client";

import { useCallback, useEffect, useState } from "react";

import { PortScreen } from "@/components/PortScreen";
import { PullToRefresh } from "@/components/PullToRefresh";
import type { Port } from "@/lib/realtime/types";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

export function PortsBoard({
  active = true,
  onDepthChange,
}: {
  active?: boolean;
  onDepthChange?: (open: boolean) => void;
}) {
  const [items, setItems] = useState<Port[]>([]);
  const [error, setError] = useState("");
  const [openCode, setOpenCode] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const headers = useCallback((): HeadersInit => {
    const initData = readInitDataRaw();
    return initData ? { "x-telegram-init-data": initData } : {};
  }, []);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/ports", { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("خواندن پورت‌ها ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { items?: Port[] };
      setItems(body.items ?? []);
      setError("");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    }
  }, [headers]);

  useEffect(() => {
    if (active) {
      void load();
    }
  }, [active, load]);

  const screenOpen = creating || Boolean(openCode);
  useEffect(() => {
    onDepthChange?.(screenOpen);
  }, [onDepthChange, screenOpen]);

  const close = useCallback(() => {
    setCreating(false);
    setOpenCode(null);
    onDepthChange?.(false);
  }, [onDepthChange]);

  const selected = items.find((port) => port.code === openCode) ?? null;

  return (
    <div className="md-stack">
      <div className={`md-stack__page${screenOpen ? "" : " md-stack__page--on"}`}>
        <PullToRefresh onRefresh={load}>
          <div className="md-content md-content--fab">
            {error && <p className="md-banner">{error}</p>}
            {items.length === 0 ? (
              <section className="md-sheet">
                <div className="md-empty">
                  <PortEmptyIcon />
                  <p>هنوز پورتی نیست.</p>
                </div>
              </section>
            ) : (
              <section className="md-ports">
                {items.map((port) => (
                  <button key={port.code} type="button" className="md-port-card" onClick={() => setOpenCode(port.code)}>
                    <span className="md-port-card__name">{port.name?.trim() || "بدون اسم"}</span>
                    {port.description?.trim() ? (
                      <span className="md-port-card__desc">{port.description.trim()}</span>
                    ) : null}
                  </button>
                ))}
              </section>
            )}
          </div>
        </PullToRefresh>
        <button
          type="button"
          className="md-fab"
          aria-label="پورت جدید"
          onClick={() => {
            setCreating(true);
            setOpenCode(null);
          }}
        >
          <PlusIcon />
        </button>
      </div>

      {creating || selected ? (
        <PortScreen port={selected} active={active && screenOpen} onBack={close} onChanged={load} />
      ) : null}
    </div>
  );
}

function PlusIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

function PortEmptyIcon() {
  return (
    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4.5" y="4.5" width="6.5" height="6.5" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
      <rect x="13" y="13" width="6.5" height="6.5" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
      <path d="M11 7.8h2.6A2.6 2.6 0 0 1 16.2 10.4V13" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}
