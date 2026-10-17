"use client";

import { backButton } from "@tma.js/sdk-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { formatSmsBubbleTime, formatSmsListTime } from "@/lib/format";
import { fetchSmsMessages, fetchSmsThreads, sendSms, syncSms, type SmsMessage, type SmsThread } from "@/lib/users/sms";

export function SmsScreen({
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
  const [threads, setThreads] = useState<SmsThread[]>([]);
  const [threadKey, setThreadKey] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [syncing, setSyncing] = useState(false);
  const [composeOpen, setComposeOpen] = useState(false);

  const loadThreads = useCallback(async () => {
    try {
      const items = await fetchSmsThreads(userId, deviceId);
      setThreads(items);
      return true;
    } catch {
      setError("خواندن پیامک‌ها ممکن نشد.");
      return false;
    }
  }, [deviceId, userId]);

  /** DB first (works offline), then live sync when the device is online. */
  const refresh = useCallback(async () => {
    setError("");
    setHint("");
    await loadThreads();
    if (!online) {
      setHint("نمایش ذخیره‌شده — دستگاه آفلاین است");
      return;
    }
    setSyncing(true);
    try {
      const result = await syncSms(userId, deviceId);
      if (!result.ok) {
        const err = result.error || "همگام‌سازی نشد";
        if (err === "offline") {
          setHint("نمایش ذخیره‌شده — دستگاه آفلاین است");
        } else {
          setError(
            err === "permission"
              ? "روی گوشی دسترسی پیامک را بده."
              : err === "timeout"
                ? "پاسخ گوشی نرسید — لیست ذخیره‌شده نشان داده شد"
                : err,
          );
        }
      } else if (typeof result.count === "number") {
        setHint(`${result.count.toLocaleString("fa-IR")} پیام همگام شد`);
      }
      await loadThreads();
    } catch {
      setError("همگام‌سازی برقرار نشد — لیست ذخیره‌شده نشان داده شد");
      await loadThreads();
    } finally {
      setSyncing(false);
    }
  }, [deviceId, loadThreads, online, userId]);

  useEffect(() => {
    if (!active || threadKey || composeOpen) return;
    void refresh();
  }, [active, threadKey, composeOpen, refresh]);

  useEffect(() => {
    if (!active || threadKey || composeOpen) {
      if (!active) backButton.hide.ifAvailable();
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
  }, [active, composeOpen, onBack, threadKey]);

  if (composeOpen) {
    return (
      <ComposeScreen
        userId={userId}
        deviceId={deviceId}
        online={online}
        active={active}
        onBack={() => setComposeOpen(false)}
        onSent={async (key) => {
          setComposeOpen(false);
          await loadThreads();
          if (key) setThreadKey(key);
        }}
      />
    );
  }

  if (threadKey) {
    const thread = threads.find((t) => t.threadKey === threadKey);
    return (
      <ThreadScreen
        userId={userId}
        deviceId={deviceId}
        threadKey={threadKey}
        address={thread?.address || threadKey}
        online={online}
        active={active}
        onBack={() => setThreadKey(null)}
        onChanged={async () => {
          await loadThreads();
        }}
      />
    );
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={refresh}>
        <div className="md-content md-content--fab md-sms-inbox">
          {hint ? <p className="md-code-banner">{hint}</p> : null}
          {error ? <p className="md-banner">{error}</p> : null}
          {syncing ? <p className="md-sms-syncing">در حال همگام‌سازی…</p> : null}
          {threads.length === 0 && !syncing ? (
            <div className="md-sms-empty">
              <span className="md-sms-empty__icon" aria-hidden="true">
                <EmptySmsIcon />
              </span>
              <p className="md-sms-empty__title">هنوز گفتگویی نیست</p>
              <p className="md-sms-empty__meta">
                {online ? "برای همگام‌سازی به پایین بکش یا پیامک جدید بفرست" : "وقتی دستگاه آنلاین شد، همگام می‌شود"}
              </p>
            </div>
          ) : (
            <ul className="md-sms-convos">
              {threads.map((thread) => (
                <li key={thread.threadKey}>
                  <button type="button" className="md-sms-convo" onClick={() => setThreadKey(thread.threadKey)}>
                    <span className={`md-sms-avatar md-sms-avatar--${avatarTone(thread.threadKey)}`} aria-hidden="true">
                      {avatarLetter(thread.address || thread.threadKey)}
                    </span>
                    <span className="md-sms-convo__main">
                      <span className="md-sms-convo__top">
                        <span className={`md-sms-convo__name${thread.unread > 0 ? " md-sms-convo__name--unread" : ""}`} dir="ltr">
                          {thread.address || thread.threadKey}
                        </span>
                        <span className="md-sms-convo__time">{thread.dateMs ? formatSmsListTime(thread.dateMs) : ""}</span>
                      </span>
                      <span className="md-sms-convo__bottom">
                        <span className={`md-sms-convo__snippet${thread.unread > 0 ? " md-sms-convo__snippet--unread" : ""}`}>
                          {thread.snippet || " "}
                        </span>
                        {thread.unread > 0 ? <span className="md-sms-badge">{thread.unread}</span> : null}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </PullToRefresh>
      <button type="button" className="md-fab md-fab--sms" aria-label="پیامک جدید" onClick={() => setComposeOpen(true)}>
        <ComposeIcon />
      </button>
    </div>
  );
}

function ThreadScreen({
  userId,
  deviceId,
  threadKey,
  address,
  online,
  active,
  onBack,
  onChanged,
}: {
  userId: string;
  deviceId: string;
  threadKey: string;
  address: string;
  online: boolean;
  active: boolean;
  onBack: () => void;
  onChanged: () => Promise<void>;
}) {
  const [messages, setMessages] = useState<SmsMessage[]>([]);
  const [body, setBody] = useState("");
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [sending, setSending] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  const load = useCallback(async () => {
    try {
      const items = await fetchSmsMessages(userId, deviceId, threadKey);
      setMessages(items);
      return true;
    } catch {
      setError("خواندن گفتگو ممکن نشد.");
      return false;
    }
  }, [deviceId, threadKey, userId]);

  const refresh = useCallback(async () => {
    setError("");
    setHint("");
    await load();
    if (!online) {
      setHint("نمایش ذخیره‌شده — دستگاه آفلاین است");
      return;
    }
    setSyncing(true);
    try {
      const result = await syncSms(userId, deviceId);
      if (!result.ok && result.error === "permission") {
        setError("روی گوشی دسترسی پیامک را بده.");
      } else if (!result.ok && result.error === "offline") {
        setHint("نمایش ذخیره‌شده — دستگاه آفلاین است");
      }
      await load();
      await onChanged();
    } catch {
      await load();
    } finally {
      setSyncing(false);
    }
  }, [deviceId, load, onChanged, online, userId]);

  useEffect(() => {
    if (!active) return;
    void refresh();
  }, [active, threadKey, refresh]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "auto", block: "end" });
  }, [messages.length]);

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

  const groups = useMemo(() => groupByDay(messages), [messages]);

  async function onSend() {
    const text = body.trim();
    if (!text) return;
    if (!online) {
      setError("دستگاه آفلاین است.");
      return;
    }
    setSending(true);
    setError("");
    try {
      const result = await sendSms(userId, deviceId, address, text);
      if (!result.ok) {
        setError(result.error === "permission" ? "روی گوشی دسترسی پیامک را بده." : result.error || "ارسال نشد");
        return;
      }
      setBody("");
      await load();
      await onChanged();
    } catch {
      setError("ارسال برقرار نشد.");
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={refresh}>
        <div className="md-sms-chat">
          <div className="md-sms-chat__peer">
            <span className={`md-sms-avatar md-sms-avatar--sm md-sms-avatar--${avatarTone(threadKey)}`} aria-hidden="true">
              {avatarLetter(address)}
            </span>
            <span className="md-sms-chat__peer-name" dir="ltr">
              {address}
            </span>
          </div>
          {hint ? <p className="md-code-banner">{hint}</p> : null}
          {error ? <p className="md-banner">{error}</p> : null}
          {syncing ? <p className="md-sms-syncing">در حال همگام‌سازی…</p> : null}
          <div className="md-sms-chat__scroll" ref={listRef}>
            {groups.map((group) => (
              <div key={group.label} className="md-sms-day">
                <span className="md-sms-day__label">{group.label}</span>
                {group.items.map((msg) => {
                  const outgoing = msg.type === 2;
                  return (
                    <div key={msg.providerId || msg.id} className={`md-sms-bubble${outgoing ? " md-sms-bubble--out" : " md-sms-bubble--in"}`}>
                      <p className="md-sms-bubble__body" dir="auto">
                        {msg.body}
                      </p>
                      <span className="md-sms-bubble__time">{msg.dateMs ? formatSmsBubbleTime(msg.dateMs) : ""}</span>
                    </div>
                  );
                })}
              </div>
            ))}
            <div ref={bottomRef} />
          </div>
          <div className="md-sms-bar">
            <div className="md-sms-bar__input">
              <textarea
                value={body}
                onChange={(e) => setBody(e.target.value)}
                placeholder="پیامک"
                dir="auto"
                rows={1}
                maxLength={1000}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    void onSend();
                  }
                }}
              />
            </div>
            <button
              type="button"
              className="md-sms-send"
              disabled={sending || !body.trim()}
              aria-label="ارسال"
              onClick={() => void onSend()}
            >
              <SendIcon />
            </button>
          </div>
        </div>
      </PullToRefresh>
    </div>
  );
}

function ComposeScreen({
  userId,
  deviceId,
  online,
  active,
  onBack,
  onSent,
}: {
  userId: string;
  deviceId: string;
  online: boolean;
  active: boolean;
  onBack: () => void;
  onSent: (threadKey: string) => Promise<void>;
}) {
  const [to, setTo] = useState("");
  const [body, setBody] = useState("");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);

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

  async function onSend() {
    if (!online) {
      setError("دستگاه آفلاین است.");
      return;
    }
    setSending(true);
    setError("");
    try {
      const result = await sendSms(userId, deviceId, to, body);
      if (!result.ok) {
        setError(result.error === "permission" ? "روی گوشی دسترسی پیامک را بده." : result.error || "ارسال نشد");
        return;
      }
      const key = to.replace(/[^\d+]/g, "") || to.trim();
      await onSent(key);
    } catch {
      setError("ارسال برقرار نشد.");
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <div className="md-content md-sms-compose">
        {error ? <p className="md-banner">{error}</p> : null}
        <label className="md-sms-compose__to">
          <span>به</span>
          <input value={to} onChange={(e) => setTo(e.target.value)} placeholder="شماره موبایل" dir="ltr" maxLength={32} autoFocus />
        </label>
        <div className="md-sms-compose__body">
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            placeholder="نوشتن پیامک"
            dir="auto"
            rows={6}
            maxLength={1000}
          />
        </div>
        <button
          type="button"
          className="md-action md-action--with-icon"
          disabled={sending || !to.trim() || !body.trim()}
          onClick={() => void onSend()}
        >
          <SendIcon />
          {sending ? "در حال ارسال…" : "ارسال"}
        </button>
      </div>
    </div>
  );
}

function groupByDay(messages: SmsMessage[]): { label: string; items: SmsMessage[] }[] {
  const out: { label: string; items: SmsMessage[] }[] = [];
  for (const msg of messages) {
    const label = dayLabel(msg.dateMs);
    const last = out[out.length - 1];
    if (last && last.label === label) last.items.push(msg);
    else out.push({ label, items: [msg] });
  }
  return out;
}

function dayLabel(ms: number): string {
  if (!ms) return "";
  const now = new Date();
  const d = new Date(ms);
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startYesterday = startToday - 86_400_000;
  if (ms >= startToday) return "امروز";
  if (ms >= startYesterday) return "دیروز";
  return new Intl.DateTimeFormat("fa-IR", { weekday: "long", month: "long", day: "numeric" }).format(ms);
}

function avatarLetter(value: string): string {
  const t = value.trim();
  if (!t) return "?";
  const digit = t.replace(/\D/g, "");
  if (digit.length >= 2) return digit.slice(-2);
  return t.slice(0, 1).toUpperCase();
}

function avatarTone(key: string): string {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) >>> 0;
  return String((h % 6) + 1);
}

function ComposeIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M5 19.5 6.2 14.8 16.8 4.2a1.6 1.6 0 0 1 2.3 0l.7.7a1.6 1.6 0 0 1 0 2.3L9.2 17.8 5 19.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function SendIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M4.2 11.2 19.5 4.5 12.8 19.8l-1.8-6.2-6.8-2.4Z" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
    </svg>
  );
}

function EmptySmsIcon() {
  return (
    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M5.5 5.5h13A1.5 1.5 0 0 1 20 7v8.5a1.5 1.5 0 0 1-1.5 1.5H10l-3.5 3v-3H5.5A1.5 1.5 0 0 1 4 15.5V7A1.5 1.5 0 0 1 5.5 5.5Z"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
    </svg>
  );
}
