"use client";

import { backButton, readTextFromClipboard } from "@tma.js/sdk-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import type { GroupCard } from "@/lib/realtime/types";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";
import { requestTelegramGroup } from "@/lib/telegram/requestChat";

type WatchLogGroup = {
  groupId?: number;
  groupTitle?: string;
  groupLink?: string;
  hasGroupPhoto?: boolean;
  boundAt?: string;
};

const PENDING_KEY = "secsystem.pendingBind.watchLog";
const BOT_ADMIN_REQUIRED_MESSAGE =
  "ربات در این گروه ادمین نیست. اول ربات را به‌عنوان ادمین گروه اضافه کن، بعد دوباره گروه را انتخاب کن.";

export function WatchLogGroupScreen({
  active = true,
  onBack,
}: {
  active?: boolean;
  onBack: () => void;
}) {
  const [row, setRow] = useState<WatchLogGroup | null>(null);
  const [group, setGroup] = useState<GroupCard | null>(null);
  const [groupId, setGroupId] = useState<number | undefined>();
  const [groupTitle, setGroupTitle] = useState("");
  const [groupLink, setGroupLink] = useState("");
  const [requestId, setRequestId] = useState<number | null>(null);
  const [pendingHasPhoto, setPendingHasPhoto] = useState(false);
  const [hint, setHint] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const resumedBind = useRef(false);

  const headers = useCallback(() => {
    const initData = readInitDataRaw();
    return initData ? { "x-telegram-init-data": initData } : {};
  }, []);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/watch/log-group", { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("بارگذاری گروه ممکن نشد.");
        return;
      }
      const data = (await res.json()) as WatchLogGroup;
      setRow(data);
      setGroupId(data.groupId || undefined);
      setGroupTitle(data.groupTitle || "");
      setGroupLink(data.groupLink || "");
      setError("");
    } catch {
      setError("بارگذاری گروه ممکن نشد.");
    }
  }, [headers]);

  const loadGroupCard = useCallback(async () => {
    if (!groupId) {
      setGroup(null);
      return;
    }
    const storedPhoto =
      row?.hasGroupPhoto
        ? "/api/watch/log-group/photo"
        : requestId && pendingHasPhoto
          ? `/api/binds/${requestId}/photo`
          : "";
    try {
      const res = await fetch(`/api/groups/${groupId}`, { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setGroup({ id: groupId, title: groupTitle || "گروه", link: "", photoUrl: storedPhoto });
        return;
      }
      const card = (await res.json()) as GroupCard;
      if (!card.photoUrl && storedPhoto) {
        card.photoUrl = storedPhoto;
      }
      setGroup(card);
      setGroupLink((current) => current || card.link || "");
    } catch {
      setGroup({ id: groupId, title: groupTitle || "گروه", link: "", photoUrl: storedPhoto });
    }
  }, [groupId, groupTitle, headers, pendingHasPhoto, requestId, row?.hasGroupPhoto]);

  useEffect(() => {
    if (!active) return;
    void load();
  }, [active, load]);

  useEffect(() => {
    void loadGroupCard();
  }, [loadGroupCard]);

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
      // Mini App back button is unavailable outside Telegram
    }
    return () => {
      off?.();
      backButton.hide.ifAvailable();
    };
  }, [active, onBack]);

  useEffect(() => {
    if (!active || resumedBind.current) return;
    resumedBind.current = true;
    const saved = sessionStorage.getItem(PENDING_KEY);
    const id = Number(saved || 0);
    if (!id) return;
    setRequestId(id);
    void watchBind(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  async function watchBind(
    request: number,
    baseline: { groupId?: number; boundAt: string },
  ) {
    const started = Date.now();
    let first = true;
    while (Date.now() - started < 45_000) {
      if (!first) await new Promise((r) => setTimeout(r, 1500));
      first = false;
      const current = await fetch("/api/watch/log-group", { headers: headers(), cache: "no-store" })
        .then((r) => (r.ok ? r.json() : null))
        .catch(() => null) as WatchLogGroup | null;
      if (
        current?.groupId &&
        (current.groupId !== baseline.groupId || (current.boundAt || "") !== baseline.boundAt)
      ) {
        sessionStorage.removeItem(PENDING_KEY);
        setRow(current);
        setGroupId(current.groupId);
        setGroupTitle(current.groupTitle || "");
        setGroupLink(current.groupLink || "");
        setHint(`وصل شد به ${current.groupTitle || "گروه"}`);
        return;
      }
      const pending = await fetch(`/api/binds/${request}`, { headers: headers(), cache: "no-store" });
      if (!pending.ok) continue;
      const bindRow = (await pending.json()) as {
        groupId?: number;
        groupTitle?: string;
        groupLink?: string;
        hasGroupPhoto?: boolean;
        botAdminOk?: boolean;
      };
      if (bindRow.groupId && bindRow.botAdminOk === false) {
        sessionStorage.removeItem(PENDING_KEY);
        setError(BOT_ADMIN_REQUIRED_MESSAGE);
        setGroupId(undefined);
        setGroupTitle("");
        setPendingHasPhoto(false);
        return;
      }
      if (bindRow.groupId && bindRow.botAdminOk) {
        sessionStorage.removeItem(PENDING_KEY);
        setGroupId(bindRow.groupId);
        setGroupTitle(bindRow.groupTitle || "");
        setPendingHasPhoto(Boolean(bindRow.hasGroupPhoto));
        if (bindRow.groupLink) {
          setGroupLink((currentLink) => currentLink || bindRow.groupLink || "");
        }
        setHint(`وصل شد به ${bindRow.groupTitle || "گروه"}`);
        await load();
        return;
      }
    }
    setError("گروه هنوز ثبت نشد. یک‌بار دیگر انتخاب کن.");
  }

  async function pickGroup() {
    setBusy(true);
    setError("");
    const baseline = { groupId, boundAt: row?.boundAt || "" };
    try {
      const res = await fetch("/api/watch/log-group/prepare-bind", {
        method: "POST",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({ initData: readInitDataRaw() }),
      });
      if (!res.ok) {
        setError("آماده‌سازی انتخاب گروه ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { mode?: string; id?: string; requestId?: number };
      if (body.requestId) {
        setRequestId(body.requestId);
        sessionStorage.setItem(PENDING_KEY, String(body.requestId));
      }
      // Poll in parallel: requestChat callback may never resolve on some clients.
      const waitBind = body.requestId ? watchBind(body.requestId, baseline) : Promise.resolve();
      if (body.mode === "miniapp" && body.id) {
        setHint("لیست چت تلگرام باز شد. گروه را انتخاب کن.");
        void requestTelegramGroup(body.id).then((opened) => {
          if (!opened) setHint("لیست چت باز نشد.");
        });
      } else {
        setHint("دکمهٔ انتخاب گروه در چت ربات آمد.");
      }
      setBusy(false);
      await waitBind;
    } catch {
      setError("انتخاب گروه ممکن نشد.");
    } finally {
      setBusy(false);
    }
  }

  async function saveLink() {
    if (groupLink.trim() && !normalizeTelegramLink(groupLink)) {
      setError("لینک گروه باید با https://t.me/ شروع شود.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      const res = await fetch("/api/watch/log-group", {
        method: "PATCH",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({ groupLink: normalizeTelegramLink(groupLink) }),
      });
      if (!res.ok) {
        setError("ذخیرهٔ لینک ممکن نشد.");
        return;
      }
      const data = (await res.json()) as WatchLogGroup;
      setRow(data);
      setGroupLink(data.groupLink || "");
      setHint("لینک ذخیره شد.");
    } catch {
      setError("ذخیرهٔ لینک ممکن نشد.");
    } finally {
      setSaving(false);
    }
  }

  function openGroup() {
    const link = normalizeTelegramLink(groupLink) || normalizeTelegramLink(group?.link || "");
    if (!link) {
      setHint("برای ورود به گروه، لینک را در کادر بنویس.");
      return;
    }
    setHint("");
    const webApp = (window as unknown as { Telegram?: { WebApp?: { openTelegramLink?: (url: string) => void } } })
      .Telegram?.WebApp;
    if (typeof webApp?.openTelegramLink === "function") {
      webApp.openTelegramLink(link);
      return;
    }
    window.open(link, "_blank", "noopener");
  }

  async function pasteGroupLink() {
    let text = "";
    try {
      if (readTextFromClipboard.isAvailable()) {
        text = (await readTextFromClipboard()) || "";
      } else if (navigator.clipboard?.readText) {
        text = await navigator.clipboard.readText();
      }
    } catch {
      text = "";
    }
    if (!text.trim()) {
      setHint("چیزی در کلیپ‌بورد نبود.");
      return;
    }
    setGroupLink(text.trim());
    setHint("");
  }

  async function refresh() {
    await load();
    await loadGroupCard();
  }

  const enteredLink = normalizeTelegramLink(groupLink) || normalizeTelegramLink(group?.link || "");
  const linkInvalid = groupLink.trim() !== "" && !normalizeTelegramLink(groupLink);
  const savedLink = normalizeTelegramLink(row?.groupLink || "");
  const dirty = normalizeTelegramLink(groupLink) !== savedLink;

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={refresh}>
        <div className="md-content">
          {hint && <p className="md-code-banner">{hint}</p>}
          {error && <p className="md-banner">{error}</p>}

          <section className="md-block">
            {groupId ? (
              <button type="button" className="md-group" onClick={openGroup}>
                <GroupPhoto url={group?.photoUrl} title={group?.title || groupTitle} />
                <span className="md-group__body">
                  <span className="md-group__title">{group?.title || groupTitle || "گروه"}</span>
                  <span className="md-group__meta">
                    {group?.username ? `@${group.username}` : "گروه خصوصی"}
                    {typeof group?.members === "number" ? ` · ${group.members} عضو` : ""}
                  </span>
                  {enteredLink ? (
                    <span className="md-group__link" dir="ltr">
                      {enteredLink.replace(/^https:\/\//, "")}
                    </span>
                  ) : (
                    <span className="md-group__meta">برای ورود، لینک را پایین بنویس</span>
                  )}
                  {group?.description ? <span className="md-group__desc">{group.description}</span> : null}
                </span>
                <ChevronIcon />
              </button>
            ) : (
              <div className="md-group md-group--empty">
                <span className="md-group__photo">گ</span>
                <span className="md-group__body">
                  <span className="md-group__title">گروهی انتخاب نشده</span>
                  <span className="md-group__meta">برای لاگ رصد نشست، یک گروه وصل کن</span>
                </span>
              </div>
            )}
            <label className="md-field">
              <span>لینک ورود به گروه</span>
              <span className="md-field__row">
                <input
                  value={groupLink}
                  onChange={(e) => {
                    setGroupLink(e.target.value);
                    setHint("");
                  }}
                  placeholder="https://t.me/+..."
                  dir="ltr"
                  inputMode="url"
                  aria-invalid={linkInvalid}
                />
                <button
                  type="button"
                  className="md-paste"
                  aria-label="چسباندن لینک"
                  onClick={() => void pasteGroupLink()}
                  disabled={busy || saving}
                >
                  <PasteIcon />
                </button>
              </span>
            </label>
            <p className={`md-field__note${linkInvalid ? " md-field__note--bad" : ""}`}>
              {linkInvalid
                ? "لینک باید به شکل https://t.me/... باشد."
                : "زدن روی گروه، همین لینک را در تلگرام باز می‌کند."}
            </p>
            <button type="button" className="md-action md-action--tonal" onClick={() => void pickGroup()} disabled={busy}>
              {groupId ? "تغییر گروه" : "انتخاب گروه"}
            </button>
            {dirty ? (
              <button type="button" className="md-action" onClick={() => void saveLink()} disabled={saving || busy}>
                ذخیرهٔ لینک
              </button>
            ) : null}
          </section>
        </div>
      </PullToRefresh>
    </div>
  );
}

function normalizeTelegramLink(value: string): string {
  const raw = value.trim();
  if (!raw) return "";
  if (/^https:\/\/(t|telegram)\.me\//i.test(raw)) {
    return raw.replace(/^https:\/\/telegram\.me\//i, "https://t.me/");
  }
  if (/^http:\/\/(t|telegram)\.me\//i.test(raw)) {
    return raw.replace(/^http:\/\/(t|telegram)\.me\//i, "https://t.me/");
  }
  if (/^(t|telegram)\.me\//i.test(raw)) {
    return `https://${raw.replace(/^telegram\.me\//i, "t.me/")}`;
  }
  if (/^@[A-Za-z0-9_]{4,}$/.test(raw)) {
    return `https://t.me/${raw.slice(1)}`;
  }
  return "";
}

function GroupPhoto({ url, title }: { url?: string; title: string }) {
  const [src, setSrc] = useState("");
  useEffect(() => {
    if (!url) {
      setSrc("");
      return;
    }
    let objectUrl = "";
    let cancelled = false;
    const initData = readInitDataRaw();
    void fetch(url, { headers: initData ? { "x-telegram-init-data": initData } : {} })
      .then((res) => (res.ok ? res.blob() : null))
      .then((blob) => {
        if (!blob || cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setSrc(objectUrl);
      })
      .catch(() => setSrc(""));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [url]);
  return (
    <span className={`md-group__photo${src ? " md-group__photo--img" : ""}`}>
      {src ? <img src={src} alt="" /> : (title || "گ").slice(0, 1)}
    </span>
  );
}

function ChevronIcon() {
  return (
    <svg className="md-group__chev" width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M14.5 6.5 9 12l5.5 5.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function PasteIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M9 5.5h6a2 2 0 0 1 2 2V7h1.5A1.5 1.5 0 0 1 20 8.5v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-10A1.5 1.5 0 0 1 5.5 7H7V7.5a2 2 0 0 1 2-2Z"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinejoin="round"
      />
      <path d="M9 4.5A1.5 1.5 0 0 1 10.5 3h3A1.5 1.5 0 0 1 15 4.5V7H9V4.5Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
    </svg>
  );
}
