"use client";

import { backButton } from "@tma.js/sdk-react";
import { useCallback, useEffect, useState } from "react";

import { ClientScreen } from "@/components/ClientScreen";
import { PullToRefresh } from "@/components/PullToRefresh";
import type { ClientTheme } from "@/lib/realtime/types";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

export function ClientsBoard({
  active = true,
  onBack,
}: {
  active?: boolean;
  onBack: () => void;
}) {
  const [items, setItems] = useState<ClientTheme[]>([]);
  const [error, setError] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const headers = useCallback((): HeadersInit => {
    const initData = readInitDataRaw();
    return initData ? { "x-telegram-init-data": initData } : {};
  }, []);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/clients", { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("خواندن کلاینت‌ها ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { items?: ClientTheme[] };
      setItems(body.items ?? []);
      setError("");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    }
  }, [headers]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const screenOpen = creating || Boolean(openId);

  useEffect(() => {
    if (!active || screenOpen) {
      if (screenOpen) return;
      backButton.hide.ifAvailable();
      return;
    }
    backButton.mount.ifAvailable();
    backButton.show.ifAvailable();
    let off: (() => void) | undefined;
    try {
      off = backButton.onClick(onBack);
    } catch {
      // outside Telegram
    }
    return () => {
      off?.();
      backButton.hide.ifAvailable();
    };
  }, [active, onBack, screenOpen]);

  const closeDetail = useCallback(() => {
    setCreating(false);
    setOpenId(null);
  }, []);

  const selected = items.find((row) => row.id === openId) ?? null;

  return (
    <div className="md-stack__page md-stack__page--on">
      <div className="md-stack">
        <div className={`md-stack__page${screenOpen ? "" : " md-stack__page--on"}`}>
          <PullToRefresh onRefresh={load}>
            <div className="md-content md-content--fab">
              {error ? <p className="md-banner">{error}</p> : null}
              {items.length === 0 ? (
                <section className="md-sheet">
                  <div className="md-empty">
                    <ClientEmptyIcon />
                    <p>هنوز کلاینتی نیست.</p>
                  </div>
                </section>
              ) : (
                <section className="md-ports">
                  {items.map((row) => (
                    <button key={row.id} type="button" className="md-port-card" onClick={() => setOpenId(row.id)}>
                      <span className="md-port-card__name">
                        {row.primaryColor ? (
                          <span className="md-client-dot" style={{ background: row.primaryColor }} aria-hidden="true" />
                        ) : null}
                        {row.name}
                      </span>
                      {row.description?.trim() ? <span className="md-port-card__desc">{row.description.trim()}</span> : null}
                      <span className="md-port-card__desc">
                        {[
                          row.hasOnboarding ? "آنبوردینگ" : "بدون آنبوردینگ",
                          row.hasFont ? row.fontFileName || "فونت" : "بدون فونت",
                        ].join(" · ")}
                      </span>
                    </button>
                  ))}
                </section>
              )}
            </div>
          </PullToRefresh>
          <button
            type="button"
            className="md-fab"
            aria-label="کلاینت جدید"
            onClick={() => {
              setCreating(true);
              setOpenId(null);
            }}
          >
            <PlusIcon />
          </button>
        </div>

        {creating || selected ? (
          <ClientScreen item={selected} active={active && screenOpen} onBack={closeDetail} onChanged={load} />
        ) : null}
      </div>
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

function ClientEmptyIcon() {
  return (
    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="6" y="3.5" width="12" height="17" rx="2.5" stroke="currentColor" strokeWidth="1.6" />
      <path d="M9.5 7h5M9.5 10.5h3.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <circle cx="12" cy="17" r="1" fill="currentColor" />
    </svg>
  );
}
