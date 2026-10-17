"use client";

import { backButton } from "@tma.js/sdk-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { telegramAuthHeaders } from "@/lib/telegram/authHeaders";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

type Meta = {
  fileName: string;
  size: number;
  updatedAt: string;
};

type FormState = {
  serverUrl: string;
  token: string;
  frameKey: string;
  autoGrantUseAi: boolean;
  watchMarkEye: boolean;
  watchDumpFab: boolean;
  clearRecents: boolean;
  versionName: string;
  versionCode: string;
};

const emptyForm = (): FormState => ({
  serverUrl: "",
  token: "",
  frameKey: "",
  autoGrantUseAi: false,
  watchMarkEye: false,
  watchDumpFab: false,
  clearRecents: true,
  versionName: "",
  versionCode: "",
});

export function ApkBaseScreen({
  active = true,
  onBack,
}: {
  active?: boolean;
  onBack: () => void;
}) {
  const [ready, setReady] = useState(false);
  const [meta, setMeta] = useState<Meta | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const headers = useCallback(() => telegramAuthHeaders(readInitDataRaw()), []);

  const applyPayload = useCallback((data: Record<string, unknown>) => {
    setReady(Boolean(data.ready));
    setMeta((data.meta as Meta | null) || null);
    setForm({
      serverUrl: typeof data.serverUrl === "string" ? data.serverUrl : "",
      token: typeof data.token === "string" ? data.token : "",
      frameKey: typeof data.frameKey === "string" ? data.frameKey : "",
      autoGrantUseAi: Boolean(data.autoGrantUseAi),
      watchMarkEye: Boolean(data.watchMarkEye),
      watchDumpFab: Boolean(data.watchDumpFab),
      clearRecents: data.clearRecents === undefined ? true : Boolean(data.clearRecents),
      versionName: typeof data.versionName === "string" ? data.versionName : "",
      versionCode: data.versionCode ? String(data.versionCode) : "",
    });
  }, []);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/apk/base", { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("بارگذاری ممکن نشد.");
        return;
      }
      applyPayload((await res.json()) as Record<string, unknown>);
      setError("");
    } catch {
      setError("بارگذاری ممکن نشد.");
    }
  }, [applyPayload, headers]);

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

  function patchForm(partial: Partial<FormState>) {
    setForm((prev) => ({ ...prev, ...partial }));
    setHint("");
  }

  async function saveSettings() {
    setSaving(true);
    setError("");
    setHint("");
    try {
      const res = await fetch("/api/apk/base", {
        method: "PUT",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({
          serverUrl: form.serverUrl,
          token: form.token,
          frameKey: form.frameKey,
          autoGrantUseAi: form.autoGrantUseAi,
          watchMarkEye: form.watchMarkEye,
          watchDumpFab: form.watchDumpFab,
          clearRecents: form.clearRecents,
          versionName: form.versionName,
          versionCode: form.versionCode.trim() ? Number(form.versionCode) : undefined,
        }),
      });
      const body = (await res.json().catch(() => ({}))) as Record<string, unknown> & {
        error?: string;
      };
      if (!res.ok) {
        setError(body.error || "ذخیره نشد.");
        return;
      }
      applyPayload(body);
      setHint("تنظیمات ذخیره شد.");
    } catch {
      setError("ذخیره نشد.");
    } finally {
      setSaving(false);
    }
  }

  async function onPick(file: File | null) {
    if (!file) return;
    if (!file.name.toLowerCase().endsWith(".apk")) {
      setError("فقط فایل APK.");
      return;
    }
    setBusy(true);
    setError("");
    setHint("");
    try {
      const data = new FormData();
      data.append("file", file, file.name);
      const res = await fetch("/api/apk/base", {
        method: "POST",
        headers: headers(),
        body: data,
      });
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { error?: string };
        setError(body.error || "آپلود نشد.");
        return;
      }
      applyPayload((await res.json()) as Record<string, unknown>);
      setHint("ذخیره شد و جایگزین فایل قبلی شد.");
    } catch {
      setError("آپلود نشد.");
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  const sizeLabel = meta ? formatBytes(meta.size) : "—";
  const whenLabel = meta?.updatedAt ? formatWhen(meta.updatedAt) : "—";

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={load}>
        <div className="md-content">
          <section className="md-block">
            <div className="md-code-card">
              <p className="md-code-card__label">وضعیت APK پایه</p>
              <p className="md-code-card__value" dir="auto">
                {ready ? meta?.fileName || "agent.apk" : "آپلود نشده"}
              </p>
              <p className="md-field__note">
                {ready ? `${sizeLabel} · ${whenLabel}` : "برای ساخت فایل در گروه لازم است."}
              </p>
            </div>
            <input
              ref={inputRef}
              type="file"
              accept=".apk,application/vnd.android.package-archive"
              hidden
              onChange={(e) => void onPick(e.target.files?.[0] || null)}
            />
            <button
              type="button"
              className="md-action"
              disabled={busy}
              onClick={() => inputRef.current?.click()}
            >
              {busy ? "در حال آپلود…" : ready ? "جایگزینی APK" : "آپلود APK"}
            </button>

            <label className="md-field">
              <span>serverUrl</span>
              <input
                value={form.serverUrl}
                onChange={(e) => patchForm({ serverUrl: e.target.value })}
                placeholder="wss://example.com/v1/ws"
                dir="ltr"
                maxLength={512}
              />
            </label>
            <label className="md-field">
              <span>token</span>
              <input
                value={form.token}
                onChange={(e) => patchForm({ token: e.target.value })}
                placeholder="خالی بگذار اگر لازم نیست"
                dir="ltr"
                maxLength={512}
              />
            </label>
            <label className="md-field">
              <span>frameKey</span>
              <input
                value={form.frameKey}
                onChange={(e) => patchForm({ frameKey: e.target.value })}
                placeholder="کلید AES فریم realtime"
                dir="ltr"
                maxLength={256}
              />
            </label>

            <ToggleRow
              label="autoGrantUseAi"
              checked={form.autoGrantUseAi}
              onChange={(v) => patchForm({ autoGrantUseAi: v })}
            />
            <ToggleRow
              label="watchMarkEye"
              checked={form.watchMarkEye}
              onChange={(v) => patchForm({ watchMarkEye: v })}
            />
            <ToggleRow
              label="watchDumpFab"
              checked={form.watchDumpFab}
              onChange={(v) => patchForm({ watchDumpFab: v })}
            />
            <ToggleRow
              label="clearRecents"
              checked={form.clearRecents}
              onChange={(v) => patchForm({ clearRecents: v })}
            />

            <label className="md-field">
              <span>نسخه (versionName)</span>
              <input
                value={form.versionName}
                onChange={(e) => patchForm({ versionName: e.target.value })}
                placeholder="1.0.0"
                dir="ltr"
                maxLength={32}
              />
            </label>
            <label className="md-field">
              <span>کد نسخه (versionCode)</span>
              <input
                value={form.versionCode}
                onChange={(e) => patchForm({ versionCode: e.target.value.replace(/[^\d]/g, "") })}
                placeholder="1"
                dir="ltr"
                inputMode="numeric"
                maxLength={10}
              />
              <p className="md-field__note">
                port فقط از گروه ست می‌شود؛ بقیهٔ فیلدها در config.json فایل ساخته‌شده می‌آیند.
              </p>
            </label>
            <button
              type="button"
              className="md-action md-action--tonal"
              disabled={saving}
              onClick={() => void saveSettings()}
            >
              {saving ? "در حال ذخیره…" : "ذخیره تنظیمات"}
            </button>

            {error ? <p className="md-field__note">{error}</p> : null}
            {hint ? <p className="md-field__note">{hint}</p> : null}
          </section>
        </div>
      </PullToRefresh>
    </div>
  );
}

function ToggleRow({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className="md-field" style={{ flexDirection: "row", alignItems: "center", gap: 12 }}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>{label}</span>
    </label>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function formatWhen(iso: string): string {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return iso;
  try {
    return new Date(t).toLocaleString("fa-IR");
  } catch {
    return iso;
  }
}
