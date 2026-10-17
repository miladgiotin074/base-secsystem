"use client";

import { backButton } from "@tma.js/sdk-react";
import { useCallback, useEffect, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { formatRelative } from "@/lib/format";
import type { DeviceScreen, SimSlot } from "@/lib/realtime/types";
import { fetchUssdHistory, sendUssd, type UssdCodeEntry, type UssdLogEntry } from "@/lib/users/ussd";

type Mode = "often" | "history";

export function UssdScreen({
  userId,
  deviceId,
  online,
  sims,
  screen,
  active,
  onBack,
}: {
  userId: string;
  deviceId: string;
  online: boolean;
  sims?: SimSlot[];
  screen?: DeviceScreen;
  active: boolean;
  onBack: () => void;
}) {
  const [code, setCode] = useState("");
  const [slot, setSlot] = useState(0);
  const [history, setHistory] = useState<UssdLogEntry[]>([]);
  const [codes, setCodes] = useState<UssdCodeEntry[]>([]);
  const [mode, setMode] = useState<Mode>("often");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);
  const [sendingCode, setSendingCode] = useState("");
  const [copied, setCopied] = useState<number | null>(null);
  const known = Boolean(sims?.length);
  const visibleSims = known
    ? sims!
    : [
        { slot: 0, state: "on" },
        { slot: 1, state: "on" },
      ];
  const ready = visibleSims.filter((item) => simUsable(item));
  const frequent = [...(codes.length > 0 ? codes : codesFromHistory(history))].sort(
    (a, b) => b.count - a.count || b.lastUsedAt - a.lastUsedAt,
  );

  const load = useCallback(async () => {
    try {
      const board = await fetchUssdHistory(userId, deviceId);
      setHistory(board.items);
      setCodes(board.codes);
      setError("");
    } catch {
      setError("خواندن سابقه ممکن نشد.");
    }
  }, [deviceId, userId]);

  useEffect(() => {
    if (!active) return;
    void load();
  }, [active, load]);

  const readyKey = ready.map((item) => item.slot).join(",");
  useEffect(() => {
    const first = ready[0];
    if (!first) return;
    if (!ready.some((item) => item.slot === slot)) setSlot(first.slot);
  }, [ready, readyKey, slot]);

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

  function pick(nextCode: string, nextSlot?: number) {
    setCode(nextCode);
    if (typeof nextSlot === "number" && ready.some((item) => item.slot === nextSlot)) {
      setSlot(nextSlot);
    }
    setError("");
  }

  async function copyAnswer(text: string, index: number) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(index);
      window.setTimeout(() => setCopied((current) => (current === index ? null : current)), 1600);
    } catch {
      setError("کپی نشد.");
    }
  }

  async function submit(nextCode = code, nextSlot = slot) {
    const value = nextCode.trim();
    if (!value) return;
    if (!online) {
      setError("دستگاه آفلاین است.");
      return;
    }
    if (known && !ready.some((item) => item.slot === nextSlot)) {
      setError(simBlockMessage(visibleSims));
      return;
    }
    const blocked = screenBlock(screen);
    if (blocked) {
      setError(blocked);
      return;
    }
    setCode(value);
    setSlot(nextSlot);
    setSending(true);
    setSendingCode(value);
    setError("");
    try {
      const result = await sendUssd(userId, deviceId, value, nextSlot);
      await load();
      if (!result.ok) {
        setError(ussdError(result.error));
      }
    } catch {
      setError("درخواست برقرار نشد.");
    } finally {
      setSending(false);
      setSendingCode("");
    }
  }

  const blocked = screenBlock(screen);
  const simNote = known && ready.length === 0 ? simBlockMessage(visibleSims) : "";
  const sendDisabled = sending || !code.trim() || (known && ready.length === 0) || Boolean(blocked);

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={load}>
        <div className="md-content">
          <section className="md-block md-ussd">
            <label className="md-field">
              <input
                value={code}
                dir="ltr"
                inputMode="tel"
                autoComplete="off"
                placeholder="*123#"
                onChange={(event) => setCode(event.target.value)}
              />
            </label>
            <div className="md-chips" role="group" aria-label="سیم‌کارت">
              {visibleSims.map((item) => {
                const usable = simUsable(item);
                return (
                  <button
                    key={item.slot}
                    type="button"
                    className={`md-chip${slot === item.slot && usable ? " md-chip--on" : ""}`}
                    disabled={!usable}
                    onClick={() => setSlot(item.slot)}
                  >
                    {simChipLabel(item)}
                  </button>
                );
              })}
            </div>
            {blocked ? <p className="md-field__note">{blocked}</p> : null}
            {simNote ? <p className="md-field__note">{simNote}</p> : null}
            <button type="button" className="md-action" disabled={sendDisabled} onClick={() => void submit()}>
              {sending && sendingCode === code.trim() ? "…" : "ارسال USSD"}
            </button>
          </section>
          {error ? <p className="md-banner">{error}</p> : null}
          <div className="md-chips md-ussd-modes" role="tablist" aria-label="کدهای USSD">
            <button
              type="button"
              role="tab"
              aria-selected={mode === "often"}
              className={`md-chip${mode === "often" ? " md-chip--on" : ""}`}
              onClick={() => setMode("often")}
            >
              پرکاربرد
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mode === "history"}
              className={`md-chip${mode === "history" ? " md-chip--on" : ""}`}
              onClick={() => setMode("history")}
            >
              سابقه
            </button>
          </div>
          {mode === "often" ? (
            frequent.length === 0 ? (
              <section className="md-block">
                <div className="md-group md-group--empty">
                  <span className="md-group__photo">*</span>
                  <span className="md-group__body">
                    <span className="md-group__title">کدی ذخیره نشده</span>
                    <span className="md-group__meta">هر کد یک‌بار ذخیره می‌شود و دفعهٔ بعد از اینجا زده می‌شود</span>
                  </span>
                </div>
              </section>
            ) : (
              <ul className="md-ussd-picks">
                {frequent.map((row) => {
                  const selected = code.trim() === row.code;
                  return (
                    <li key={row.code} className={`md-ussd-pick${selected ? " md-ussd-pick--on" : ""}`}>
                      <button type="button" className="md-group" onClick={() => pick(row.code, row.slot)}>
                        <span className="md-group__body">
                          <span className="md-group__title" dir="ltr">
                            {row.code}
                          </span>
                          <span className="md-group__meta">
                            {row.count.toLocaleString("fa-IR")} بار
                            {row.lastUsedAt ? ` · ${formatRelative(row.lastUsedAt)}` : ""}
                          </span>
                        </span>
                      </button>
                      <button
                        type="button"
                        className="md-copy"
                        disabled={sending || Boolean(blocked) || (known && ready.length === 0)}
                        onClick={() => void submit(row.code, ready.some((item) => item.slot === row.slot) ? row.slot : slot)}
                      >
                        {sending && sendingCode === row.code ? "…" : "بزن"}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )
          ) : history.length === 0 ? (
            <section className="md-block">
              <div className="md-group md-group--empty">
                <span className="md-group__photo">U</span>
                <span className="md-group__body">
                  <span className="md-group__title">سابقه‌ای نیست</span>
                  <span className="md-group__meta">درخواست‌ها و جوابشان اینجا می‌مانند</span>
                </span>
              </div>
            </section>
          ) : (
            <ul className="md-ussd-log">
              {history.map((row, index) => (
                <li key={`${row.createdAt}-${row.code}-${index}`} className="md-ussd-log__item">
                  <div className="md-ussd-log__head">
                    <button type="button" className="md-ussd-log__code" dir="ltr" onClick={() => pick(row.code, row.slot)}>
                      {row.code}
                    </button>
                    <span className="md-ussd-log__meta">
                      سیم {(row.slot ?? 0) + 1}
                      {row.createdAt ? ` · ${formatRelative(row.createdAt)}` : ""}
                    </span>
                  </div>
                  {row.ok && row.text ? (
                    <>
                      <p className="md-ussd-log__text" dir="auto">
                        {row.text}
                      </p>
                      <button type="button" className="md-copy md-ussd-log__copy" onClick={() => void copyAnswer(row.text || "", index)}>
                        {copied === index ? "کپی شد" : "کپی متن"}
                      </button>
                    </>
                  ) : (
                    <p className="md-ussd-log__err">{ussdError(row.error) || "ناموفق"}</p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </PullToRefresh>
    </div>
  );
}

function codesFromHistory(items: UssdLogEntry[]): UssdCodeEntry[] {
  const map = new Map<string, UssdCodeEntry>();
  for (const row of items) {
    if (!row.code) continue;
    const prev = map.get(row.code);
    if (!prev) {
      map.set(row.code, { code: row.code, count: 1, slot: row.slot ?? 0, lastUsedAt: row.createdAt || 0 });
      continue;
    }
    prev.count += 1;
    if ((row.createdAt || 0) >= prev.lastUsedAt) {
      prev.lastUsedAt = row.createdAt || prev.lastUsedAt;
      prev.slot = row.slot ?? prev.slot;
    }
  }
  return [...map.values()];
}

function noServiceLabel(label?: string): boolean {
  const value = (label || "").trim().toLowerCase().replace(/\u200c/g, "");
  if (!value) return false;
  return [
    "بدون سرویس",
    "بدون خدمات",
    "no service",
    "no network",
    "emergency calls only",
    "فقط تماس اضطراری",
    "خارج از دسترس",
    "not registered",
  ].some((phrase) => value.includes(phrase));
}

function simUsable(sim: SimSlot): boolean {
  return sim.state === "on" && !noServiceLabel(sim.label);
}

function simChipLabel(sim: SimSlot): string {
  const n = sim.slot + 1;
  if (sim.state === "empty") return `سیم ${n} خالی`;
  if (sim.state === "noservice" || noServiceLabel(sim.label)) return `سیم ${n} بدون سرویس`;
  if (sim.state !== "on") return `${sim.label?.trim() || `سیم ${n}`} خاموش`;
  return sim.label?.trim() || `سیم ${n}`;
}

function simBlockMessage(sims: SimSlot[]): string {
  if (sims.some((item) => item.state === "noservice" || noServiceLabel(item.label))) {
    return "سیم بدون سرویس قابل استفاده نیست.";
  }
  return "سیم‌کارتی برای USSD روشن نیست.";
}

function screenBlock(screen?: DeviceScreen): string {
  if (!screen) return "";
  if (!screen.on) return "صفحه گوشی خاموش است.";
  if (screen.locked) return "صفحه گوشی قفل است.";
  return "";
}

function ussdError(error?: string): string {
  if (!error) return "انجام نشد.";
  const value = error.trim();
  if (value.includes("داده") && value.includes("ورودی")) {
    return "کد روی این مسیر جواب نداد؛ دوباره بزن یا از شماره‌گیر گوشی امتحان کن.";
  }
  switch (value) {
    case "permission":
      return "روی گوشی دسترسی تماس داده نشده.";
    case "offline":
      return "دستگاه آفلاین است.";
    case "timeout":
      return "پاسخ نداد.";
    case "invalid":
    case "invalid ussd":
      return "کد USSD نامعتبر است.";
    case "ussd failed":
      return "USSD انجام نشد (دسترسی Accessibility یا سیم را چک کن).";
    case "sim off":
      return "این سیم‌کارت خاموش است.";
    case "sim empty":
      return "این اسلات سیم خالی است.";
    case "no service":
      return "این سیم بدون سرویس است.";
    case "screen off":
      return "صفحه گوشی خاموش است.";
    case "screen locked":
      return "صفحه گوشی قفل است.";
    default:
      return value;
  }
}
