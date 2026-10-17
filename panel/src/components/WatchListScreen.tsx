"use client";

import { backButton, popup } from "@tma.js/sdk-react";
import { useCallback, useEffect, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

type WatchApp = { package: string; label?: string; icon?: string };

export function WatchListScreen({ active, onBack }: { active: boolean; onBack: () => void }) {
  const [apps, setApps] = useState<WatchApp[]>([]);
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [busy, setBusy] = useState("");

  const headers = useCallback((): HeadersInit => {
    const initData = readInitDataRaw();
    return initData ? { "x-telegram-init-data": initData } : {};
  }, []);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/watch/packages", { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("خواندن فهرست ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { apps?: WatchApp[]; packages?: string[] };
      if (body.apps?.length) {
        setApps(body.apps);
      } else {
        setApps((body.packages || []).map((pkg) => ({ package: pkg })));
      }
      setError("");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    }
  }, [headers]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  useEffect(() => {
    if (!active) {
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
  }, [active, onBack]);

  async function removeApp(app: WatchApp) {
    const title = app.label || app.package;
    let accepted = false;
    try {
      const id = await popup.show({
        title: "حذف از رصد",
        message: `${title}\n${app.package}`,
        buttons: [
          { id: "remove", type: "destructive", text: "حذف" },
          { type: "cancel" },
        ],
      });
      accepted = id === "remove";
    } catch {
      accepted = window.confirm(`${title}\nاز فهرست رصد حذف شود؟`);
    }
    if (!accepted) return;
    setBusy(app.package);
    setHint("");
    setError("");
    try {
      const next = apps.filter((item) => item.package !== app.package);
      const res = await fetch("/api/watch/packages", {
        method: "PUT",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({ apps: next }),
      });
      if (!res.ok) {
        setError("حذف ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { apps?: WatchApp[] };
      setApps(body.apps || next);
      setHint("حذف شد.");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    } finally {
      setBusy("");
    }
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={load}>
        <div className="md-content">
          {error ? <p className="md-banner">{error}</p> : null}
          {hint ? <p className="md-code-banner">{hint}</p> : null}
          {apps.length === 0 && !error ? (
            <section className="md-block">
              <div className="md-group md-group--empty">
                <span className="md-group__photo">ر</span>
                <span className="md-group__body">
                  <span className="md-group__title">فهرست خالی است</span>
                  <span className="md-group__meta">از فهرست برنامه‌ها با نگه داشتن نام، اضافه کن</span>
                </span>
              </div>
            </section>
          ) : (
            <ul className="md-app-list">
              {apps.map((app) => (
                <li key={app.package} className="md-app-row">
                  {app.icon ? (
                    <img className="md-app-row__icon" alt="" src={`data:image/png;base64,${app.icon}`} />
                  ) : (
                    <span className="md-app-row__icon md-app-row__icon--empty" aria-hidden="true">
                      {(app.label || app.package).trim().slice(0, 1) || "ر"}
                    </span>
                  )}
                  <span className="md-app-row__body">
                    <span className="md-app-row__name" dir="auto">
                      {app.label || app.package}
                    </span>
                    <span className="md-app-row__pkg" dir="ltr">
                      {app.package}
                    </span>
                  </span>
                  <button
                    type="button"
                    className="md-action md-action--tonal md-app-row__action"
                    disabled={busy === app.package}
                    onClick={() => void removeApp(app)}
                  >
                    {busy === app.package ? "…" : "حذف"}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </PullToRefresh>
    </div>
  );
}
