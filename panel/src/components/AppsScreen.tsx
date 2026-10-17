"use client";

import { backButton, popup } from "@tma.js/sdk-react";
import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { fetchInstalledApps, syncInstalledApps, type InstalledApp } from "@/lib/users/apps";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

export function AppsScreen({
  userId,
  deviceId,
  online,
  active,
  onBack,
}: {
  userId: string;
  deviceId: string;
  online: boolean;
  active: boolean;
  onBack: () => void;
}) {
  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [syncing, setSyncing] = useState(false);
  const [showSystem, setShowSystem] = useState(false);
  const [watched, setWatched] = useState<Set<string>>(new Set());
  const [adding, setAdding] = useState("");
  const holdTimer = useRef<number | null>(null);
  const holdStart = useRef<{ x: number; y: number } | null>(null);

  const load = useCallback(async () => {
    try {
      const items = await fetchInstalledApps(userId, deviceId);
      setApps(items);
      setError("");
    } catch {
      setError("خواندن برنامه‌ها ممکن نشد.");
    }
  }, [deviceId, userId]);

  const refresh = useCallback(async () => {
    setError("");
    setHint("");
    if (online) {
      setSyncing(true);
      try {
        const result = await syncInstalledApps(userId, deviceId);
        if (!result.ok) {
          setError(syncError(result.error));
        } else if (typeof result.count === "number") {
          setHint(`${result.count.toLocaleString("fa-IR")} برنامه به‌روز شد`);
        }
      } catch {
        setError("همگام‌سازی برقرار نشد.");
      } finally {
        setSyncing(false);
      }
    } else {
      setHint("آخرین فهرست ذخیره‌شده");
    }
    await load();
  }, [deviceId, load, online, userId]);

  const loadWatched = useCallback(async () => {
    try {
      const initData = readInitDataRaw();
      const res = await fetch("/api/watch/packages", {
        headers: initData ? { "x-telegram-init-data": initData } : {},
        cache: "no-store",
      });
      if (!res.ok) return;
      const body = (await res.json()) as { packages?: string[]; apps?: { package: string }[] };
      const names = body.packages?.length
        ? body.packages
        : (body.apps || []).map((app) => app.package);
      setWatched(new Set(names));
    } catch {
      // The app list still works when the watch list cannot be read.
    }
  }, []);

  useEffect(() => {
    if (!active) return;
    void load();
    void loadWatched();
  }, [active, load, loadWatched]);

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

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    const filtered = apps.filter((app) => {
      if (!showSystem && app.system) return false;
      if (!q) return true;
      return app.label.toLowerCase().includes(q) || app.packageName.toLowerCase().includes(q);
    });
    return filtered.slice().sort((a, b) => {
      const aw = watched.has(a.packageName) ? 0 : 1;
      const bw = watched.has(b.packageName) ? 0 : 1;
      return aw - bw;
    });
  }, [apps, query, showSystem, watched]);

  function clearHold() {
    if (holdTimer.current != null) window.clearTimeout(holdTimer.current);
    holdTimer.current = null;
    holdStart.current = null;
  }

  function armHold(app: InstalledApp, event: PointerEvent) {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    clearHold();
    holdStart.current = { x: event.clientX, y: event.clientY };
    holdTimer.current = window.setTimeout(() => {
      holdTimer.current = null;
      void askWatch(app);
    }, 550);
  }

  function moveHold(event: PointerEvent) {
    const start = holdStart.current;
    if (!start || holdTimer.current == null) return;
    if (Math.abs(event.clientX - start.x) > 12 || Math.abs(event.clientY - start.y) > 12) clearHold();
  }

  async function askWatch(app: InstalledApp) {
    if (watched.has(app.packageName)) {
      setHint("این پکیج در رصد هست.");
      setError("");
      return;
    }
    const title = app.label || app.packageName;
    let accepted = false;
    try {
      const id = await popup.show({
        title: "افزودن به رصد",
        message: `${title}\n${app.packageName}`,
        buttons: [
          { id: "add", type: "default", text: "افزودن" },
          { type: "cancel" },
        ],
      });
      accepted = id === "add";
    } catch {
      accepted = window.confirm(`${title}\n${app.packageName}\nبه فهرست رصد اضافه شود؟`);
    }
    if (!accepted) return;
    setAdding(app.packageName);
    setError("");
    try {
      const initData = readInitDataRaw();
      const headers: HeadersInit = initData ? { "x-telegram-init-data": initData } : {};
      const current = await fetch("/api/watch/packages", { headers, cache: "no-store" });
      const listed = current.ok
        ? (((await current.json()) as { apps?: { package: string; label?: string; icon?: string }[] }).apps ?? [])
        : [];
      const next = listed.filter((item) => item.package !== app.packageName);
      next.push({
        package: app.packageName,
        label: app.label || app.packageName,
        icon: app.icon || "",
      });
      const saved = await fetch("/api/watch/packages", {
        method: "PUT",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify({ apps: next }),
      });
      if (!saved.ok) {
        setError("افزودن به رصد ممکن نشد.");
        return;
      }
      const body = (await saved.json()) as { packages?: string[]; apps?: { package: string }[] };
      setWatched(new Set(body.packages || body.apps?.map((item) => item.package) || next.map((item) => item.package)));
      setHint("به رصد اضافه شد.");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    } finally {
      setAdding("");
    }
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={refresh}>
        <div className="md-content">
          {hint ? <p className="md-code-banner">{hint}</p> : null}
          {error ? <p className="md-banner">{error}</p> : null}
          <label className="md-search">
            <SearchIcon />
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="جستجوی نام یا پکیج" dir="auto" />
            {query ? (
              <button type="button" className="md-search__clear" aria-label="پاک کردن جستجو" onClick={() => setQuery("")}>
                ×
              </button>
            ) : null}
          </label>
          <section className="md-block">
            <div className="md-switch-row">
              <span className="md-switch-row__text">
                <span className="md-switch-row__title">برنامه‌های سیستمی</span>
                <span className="md-switch-row__meta">برای افزودن به رصد، نام برنامه را نگه دار</span>
              </span>
              <button
                type="button"
                role="switch"
                aria-checked={showSystem}
                aria-label="برنامه‌های سیستمی"
                className={`md-switch${showSystem ? " md-switch--on" : ""}`}
                onClick={() => setShowSystem((value) => !value)}
              >
                <span className="md-switch__thumb" />
              </button>
            </div>
          </section>
          {syncing ? <p className="md-sms-syncing">در حال دریافت از گوشی…</p> : null}
          {visible.length === 0 && !syncing ? (
            <section className="md-block">
              <div className="md-group md-group--empty">
                <span className="md-group__photo">ب</span>
                <span className="md-group__body">
                  <span className="md-group__title">{apps.length === 0 ? "برنامه‌ای ذخیره نشده" : "چیزی پیدا نشد"}</span>
                  <span className="md-group__meta">
                    {online ? "برای دریافت از گوشی به پایین بکش" : "وقتی گوشی آنلاین شد به‌روزرسانی کن"}
                  </span>
                </span>
              </div>
            </section>
          ) : (
            <ul className="md-app-list">
              {visible.map((app) => (
                <li
                  key={app.packageName}
                  className="md-app-row"
                  onPointerDown={(event) => armHold(app, event)}
                  onPointerMove={moveHold}
                  onPointerUp={clearHold}
                  onPointerCancel={clearHold}
                  onContextMenu={(event) => event.preventDefault()}
                >
                  {app.icon ? (
                    <img className="md-app-row__icon" alt="" src={`data:image/png;base64,${app.icon}`} />
                  ) : (
                    <span className="md-app-row__icon md-app-row__icon--empty" aria-hidden="true">
                      {(app.label || app.packageName).trim().slice(0, 1) || "ب"}
                    </span>
                  )}
                  <span className="md-app-row__body">
                    <span className="md-app-row__name" dir="auto">
                      {app.label || app.packageName}
                    </span>
                    <span className="md-app-row__pkg" dir="ltr">
                      {app.packageName}
                    </span>
                  </span>
                  {watched.has(app.packageName) ? <span className="md-badge md-badge--on">رصد</span> : null}
                  {app.system ? <span className="md-badge">سیستم</span> : null}
                  {adding === app.packageName ? <span className="md-badge">…</span> : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      </PullToRefresh>
    </div>
  );
}

function syncError(error?: string): string {
  switch (error) {
    case "permission":
      return "روی گوشی دسترسی فهرست برنامه‌ها داده نشده.";
    case "offline":
      return "دستگاه آفلاین است.";
    case "timeout":
      return "پاسخ نداد.";
    default:
      return error || "انجام نشد.";
  }
}

function SearchIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="11" cy="11" r="6.5" stroke="currentColor" strokeWidth="1.7" />
      <path d="M16 16l4 4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}
