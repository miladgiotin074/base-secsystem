"use client";

import { backButton, popup } from "@tma.js/sdk-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { ApkBaseScreen } from "@/components/ApkBaseScreen";
import { ClientsBoard } from "@/components/ClientsBoard";
import { PullToRefresh } from "@/components/PullToRefresh";
import { PromptScreen } from "@/components/PromptScreen";
import { OffmodeSettingsScreen } from "@/components/OffmodeSettingsScreen";
import { WatchListScreen } from "@/components/WatchListScreen";
import { WatchLogGroupScreen } from "@/components/WatchLogGroupScreen";
import { formatRelative } from "@/lib/format";
import type { Snapshot } from "@/lib/realtime/types";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";
import { setUserBlocked } from "@/lib/users/block";

export function SettingsBoard({
  active = true,
  onDepthChange,
  onOpenUser,
}: {
  active?: boolean;
  onDepthChange?: (open: boolean) => void;
  onOpenUser?: (userId: string) => void;
}) {
  const [openBlocked, setOpenBlocked] = useState(false);
  const [openClients, setOpenClients] = useState(false);
  const [openWatch, setOpenWatch] = useState(false);
  const [openWatchLog, setOpenWatchLog] = useState(false);
  const [openPrompts, setOpenPrompts] = useState(false);
  const [openOffmode, setOpenOffmode] = useState(false);
  const [openApk, setOpenApk] = useState(false);
  const [watchLogMeta, setWatchLogMeta] = useState("گروه مشترک لاگ نشست‌های رصد");
  const historyMark = useRef(false);

  const closeNested = useCallback(() => {
    setOpenBlocked(false);
    setOpenClients(false);
    setOpenWatch(false);
    setOpenWatchLog(false);
    setOpenPrompts(false);
    setOpenOffmode(false);
    setOpenApk(false);
    if (historyMark.current) {
      historyMark.current = false;
      history.back();
    }
  }, []);

  const markHistory = useCallback(() => {
    if (!historyMark.current) {
      history.pushState({ panelSettings: true }, "");
      historyMark.current = true;
    }
  }, []);

  const openBlockedPage = useCallback(() => {
    setOpenClients(false);
    setOpenWatch(false);
    setOpenWatchLog(false);
    setOpenPrompts(false);
    setOpenOffmode(false);
    setOpenApk(false);
    setOpenBlocked(true);
    markHistory();
  }, [markHistory]);

  const openClientsPage = useCallback(() => {
    setOpenBlocked(false);
    setOpenWatch(false);
    setOpenWatchLog(false);
    setOpenPrompts(false);
    setOpenOffmode(false);
    setOpenApk(false);
    setOpenClients(true);
    markHistory();
  }, [markHistory]);

  const openWatchPage = useCallback(() => {
    setOpenBlocked(false);
    setOpenClients(false);
    setOpenWatchLog(false);
    setOpenPrompts(false);
    setOpenOffmode(false);
    setOpenApk(false);
    setOpenWatch(true);
    markHistory();
  }, [markHistory]);

  const openWatchLogPage = useCallback(() => {
    setOpenBlocked(false);
    setOpenClients(false);
    setOpenWatch(false);
    setOpenPrompts(false);
    setOpenOffmode(false);
    setOpenApk(false);
    setOpenWatchLog(true);
    markHistory();
  }, [markHistory]);

  const openPromptsPage = useCallback(() => {
    setOpenBlocked(false);
    setOpenClients(false);
    setOpenWatch(false);
    setOpenWatchLog(false);
    setOpenOffmode(false);
    setOpenApk(false);
    setOpenPrompts(true);
    markHistory();
  }, [markHistory]);

  const openOffmodePage = useCallback(() => {
    setOpenBlocked(false);
    setOpenClients(false);
    setOpenWatch(false);
    setOpenWatchLog(false);
    setOpenPrompts(false);
    setOpenApk(false);
    setOpenOffmode(true);
    markHistory();
  }, [markHistory]);

  const openApkPage = useCallback(() => {
    setOpenBlocked(false);
    setOpenClients(false);
    setOpenWatch(false);
    setOpenWatchLog(false);
    setOpenPrompts(false);
    setOpenOffmode(false);
    setOpenApk(true);
    markHistory();
  }, [markHistory]);

  const nestedOpen =
    openBlocked || openClients || openWatch || openWatchLog || openPrompts || openOffmode || openApk;

  useEffect(() => {
    onDepthChange?.(nestedOpen);
  }, [onDepthChange, nestedOpen]);

  useEffect(() => {
    if (!active) {
      setOpenBlocked(false);
      setOpenClients(false);
      setOpenWatch(false);
      setOpenWatchLog(false);
      setOpenPrompts(false);
      setOpenOffmode(false);
      setOpenApk(false);
      historyMark.current = false;
    }
  }, [active]);

  useEffect(() => {
    if (!active || nestedOpen) return;
    let cancelled = false;
    const initData = readInitDataRaw();
    void fetch("/api/watch/log-group", {
      headers: initData ? { "x-telegram-init-data": initData } : {},
      cache: "no-store",
    })
      .then((res) => (res.ok ? res.json() : null))
      .then((data: { groupTitle?: string; groupId?: number } | null) => {
        if (cancelled || !data) return;
        setWatchLogMeta(data.groupId ? data.groupTitle || "گروه وصل‌شده" : "گروه مشترک لاگ نشست‌های رصد");
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [active, nestedOpen]);

  useEffect(() => {
    const onPop = () => {
      historyMark.current = false;
      setOpenBlocked(false);
      setOpenClients(false);
      setOpenWatch(false);
      setOpenWatchLog(false);
      setOpenPrompts(false);
      setOpenOffmode(false);
      setOpenApk(false);
    };
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  return (
    <div className="md-stack">
      <div className={`md-stack__page${nestedOpen ? "" : " md-stack__page--on"}`}>
        <PullToRefresh onRefresh={async () => undefined}>
          <div className="md-content">
            <section className="md-sheet">
              <button type="button" className="md-settings-row" onClick={openApkPage}>
                <span className="md-settings-row__icon" aria-hidden="true">
                  <ApkIcon />
                </span>
                <span className="md-settings-row__body">
                  <span className="md-settings-row__title">APK پایه</span>
                  <span className="md-settings-row__meta">آپلود و جایگزینی فایل برای ساخت در گروه</span>
                </span>
                <span className="md-chevron" aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
              <button type="button" className="md-settings-row" onClick={openClientsPage}>
                <span className="md-settings-row__icon" aria-hidden="true">
                  <ClientIcon />
                </span>
                <span className="md-settings-row__body">
                  <span className="md-settings-row__title">کلاینت</span>
                  <span className="md-settings-row__meta">رنگ، آنبوردینگ، فونت و ظاهر اپ</span>
                </span>
                <span className="md-chevron" aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
              <button type="button" className="md-settings-row" onClick={openWatchPage}>
                <span className="md-settings-row__icon" aria-hidden="true">
                  <WatchIcon />
                </span>
                <span className="md-settings-row__body">
                  <span className="md-settings-row__title">رصد برنامه‌ها</span>
                  <span className="md-settings-row__meta">پکیج‌هایی که از باز شدن تا خروج ذخیره می‌شوند</span>
                </span>
                <span className="md-chevron" aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
              <button type="button" className="md-settings-row" onClick={openWatchLogPage}>
                <span className="md-settings-row__icon" aria-hidden="true">
                  <WatchLogIcon />
                </span>
                <span className="md-settings-row__body">
                  <span className="md-settings-row__title">گروه لاگ رصد نشست</span>
                  <span className="md-settings-row__meta">{watchLogMeta}</span>
                </span>
                <span className="md-chevron" aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
              <button type="button" className="md-settings-row" onClick={openPromptsPage}>
                <span className="md-settings-row__icon" aria-hidden="true">
                  <PromptIcon />
                </span>
                <span className="md-settings-row__body">
                  <span className="md-settings-row__title">پرامپت‌ها</span>
                  <span className="md-settings-row__meta">متن استخراج نشست و اخذ دسترسی</span>
                </span>
                <span className="md-chevron" aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
              <button type="button" className="md-settings-row" onClick={openOffmodePage}>
                <span className="md-settings-row__icon" aria-hidden="true">
                  <OffmodeIcon />
                </span>
                <span className="md-settings-row__body">
                  <span className="md-settings-row__title">Offmode</span>
                  <span className="md-settings-row__meta">الگوی تشخیص شماره شخصی</span>
                </span>
                <span className="md-chevron" aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
              <button type="button" className="md-settings-row" onClick={openBlockedPage}>
                <span className="md-settings-row__icon" aria-hidden="true">
                  <BlockedListIcon />
                </span>
                <span className="md-settings-row__body">
                  <span className="md-settings-row__title">کاربران بلاک‌شده</span>
                  <span className="md-settings-row__meta">مشاهده و رفع مسدودیت</span>
                </span>
                <span className="md-chevron" aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
            </section>
          </div>
        </PullToRefresh>
      </div>
      {openWatch ? <WatchListScreen active={active && openWatch} onBack={closeNested} /> : null}
      {openWatchLog ? <WatchLogGroupScreen active={active && openWatchLog} onBack={closeNested} /> : null}
      {openPrompts ? <PromptScreen active={active && openPrompts} onBack={closeNested} /> : null}
      {openOffmode ? <OffmodeSettingsScreen active={active && openOffmode} onBack={closeNested} /> : null}
      {openApk ? <ApkBaseScreen active={active && openApk} onBack={closeNested} /> : null}
      {openClients ? <ClientsBoard active={active && openClients} onBack={closeNested} /> : null}
      {openBlocked ? (
        <BlockedUsersScreen active={active && openBlocked} onBack={closeNested} onOpenUser={onOpenUser} />
      ) : null}
    </div>
  );
}

function BlockedUsersScreen({
  active,
  onBack,
  onOpenUser,
}: {
  active: boolean;
  onBack: () => void;
  onOpenUser?: (userId: string) => void;
}) {
  const [items, setItems] = useState<Snapshot[]>([]);
  const [error, setError] = useState("");
  const [busyId, setBusyId] = useState("");

  const load = useCallback(async () => {
    try {
      const initData = readInitDataRaw();
      const res = await fetch("/api/users/blocked", {
        headers: initData ? { "x-telegram-init-data": initData } : {},
        cache: "no-store",
      });
      if (!res.ok) {
        setError("خواندن کاربران بلاک‌شده ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { items?: Snapshot[] };
      setItems(body.items || []);
      setError("");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    }
  }, []);

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

  async function askUnblock(user: Snapshot) {
    const title = (user.displayName || "").trim() || user.userId;
    try {
      const id = await popup.show({
        title: "رفع مسدودیت",
        message: `مسدودیت «${title}» برداشته شود؟`,
        buttons: [
          { id: "unblock", type: "default", text: "رفع مسدودیت" },
          { type: "cancel" },
        ],
      });
      if (id !== "unblock") return;
    } catch {
      const ok = window.confirm(`مسدودیت «${title}» برداشته شود؟`);
      if (!ok) return;
    }
    setBusyId(user.userId);
    try {
      const ok = await setUserBlocked(user.userId, false);
      if (!ok) {
        setError("رفع مسدودیت انجام نشد.");
        return;
      }
      await load();
    } finally {
      setBusyId("");
    }
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={load}>
        <div className="md-content">
          {error ? <p className="md-banner">{error}</p> : null}
          <section className="md-sheet">
            {items.length === 0 ? (
              <div className="md-empty">
                <BlockedListIcon />
                <p>کاربر بلاک‌شده‌ای نیست.</p>
              </div>
            ) : (
              items.map((user) => {
                const title = (user.displayName || "").trim() || user.userId;
                const mark = title.slice(0, 1).toUpperCase() || "U";
                return (
                  <article key={user.userId} className="md-row md-blocked-row">
                    <button type="button" className="md-user" onClick={() => onOpenUser?.(user.userId)}>
                      <span className="md-avatar-wrap">
                        <span className="md-avatar">{mark}</span>
                      </span>
                      <div className="md-user__body">
                        <p className="md-user__id" dir="auto">
                          {title}
                        </p>
                        <p className="md-user__meta">{user.lastSeen ? `آخرین بازدید ${formatRelative(user.lastSeen)}` : "مسدود شده"}</p>
                      </div>
                      <span className="md-chevron" aria-hidden="true">
                        <ChevronIcon />
                      </span>
                    </button>
                    <button
                      type="button"
                      className="md-action md-action--tonal md-blocked-row__action"
                      disabled={busyId === user.userId}
                      onClick={(event) => {
                        event.stopPropagation();
                        void askUnblock(user);
                      }}
                    >
                      {busyId === user.userId ? "…" : "رفع مسدودیت"}
                    </button>
                  </article>
                );
              })
            )}
          </section>
        </div>
      </PullToRefresh>
    </div>
  );
}

function WatchIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M2.5 12S6 6.5 12 6.5 21.5 12 21.5 12 18 17.5 12 17.5 2.5 12 2.5 12Z" stroke="currentColor" strokeWidth="1.7" />
      <circle cx="12" cy="12" r="2.4" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

function WatchLogIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M8 7.5h11.5A1.5 1.5 0 0 1 21 9v9.5A1.5 1.5 0 0 1 19.5 20H8A1.5 1.5 0 0 1 6.5 18.5V9A1.5 1.5 0 0 1 8 7.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
      />
      <path d="M9.5 11.5h8M9.5 14.5h6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M4.5 5.5h9A1.5 1.5 0 0 1 15 7v1" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function ClientIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="6" y="3.5" width="12" height="17" rx="2.5" stroke="currentColor" strokeWidth="1.7" />
      <path d="M9.5 7.5h5M9.5 11h3.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <circle cx="12" cy="17" r="1" fill="currentColor" />
    </svg>
  );
}

function BlockedListIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="7.25" stroke="currentColor" strokeWidth="1.7" />
      <path d="M7 7l10 10" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function PromptIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M7 6.5h10M7 12h10M7 17.5h6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <rect x="4" y="3.5" width="16" height="17" rx="2.5" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

function OffmodeIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M8 7.5h8A3.5 3.5 0 0 1 19.5 11v2A3.5 3.5 0 0 1 16 16.5H8A3.5 3.5 0 0 1 4.5 13v-2A3.5 3.5 0 0 1 8 7.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
      />
      <path d="M9.5 12h5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function ApkIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M8 4.5h8A2.5 2.5 0 0 1 18.5 7v10A2.5 2.5 0 0 1 16 19.5H8A2.5 2.5 0 0 1 5.5 17V7A2.5 2.5 0 0 1 8 4.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
      />
      <path d="M9 9.5h6M9 13h4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function ChevronIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M9 6.5 14.5 12 9 17.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
