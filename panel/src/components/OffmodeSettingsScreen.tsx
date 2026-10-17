"use client";

import { backButton } from "@tma.js/sdk-react";
import { useCallback, useEffect, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

type Settings = {
  personalPattern: string;
};

export function OffmodeSettingsScreen({
  active = true,
  onBack,
}: {
  active?: boolean;
  onBack: () => void;
}) {
  const [pattern, setPattern] = useState("");
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [saving, setSaving] = useState(false);

  const headers = useCallback(() => {
    const initData = readInitDataRaw();
    return {
      "Content-Type": "application/json",
      ...(initData ? { "x-telegram-init-data": initData } : {}),
    };
  }, []);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/offmode/settings", { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("بارگذاری ممکن نشد.");
        return;
      }
      const data = (await res.json()) as Settings;
      setPattern(data.personalPattern || "");
      setError("");
    } catch {
      setError("بارگذاری ممکن نشد.");
    }
  }, [headers]);

  useEffect(() => {
    if (!active) return;
    void load();
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

  async function save() {
    setSaving(true);
    setError("");
    setHint("");
    try {
      const res = await fetch("/api/offmode/settings", {
        method: "PUT",
        headers: headers(),
        body: JSON.stringify({ personalPattern: pattern }),
      });
      if (!res.ok) {
        setError("ذخیره نشد.");
        return;
      }
      const data = (await res.json()) as Settings;
      setPattern(data.personalPattern || "");
      setHint("ذخیره شد");
    } catch {
      setError("ذخیره نشد.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={load}>
        <div className="md-content">
          <section className="md-block">
            <label className="md-field">
              <span>الگوی شماره شخصی</span>
              <input
                value={pattern}
                onChange={(e) => {
                  setPattern(e.target.value);
                  setHint("");
                }}
                placeholder="^(?:\+?98|0)?9\d{9}$"
                dir="ltr"
                maxLength={120}
              />
            </label>
            {error ? <p className="md-field__note">{error}</p> : null}
            {hint ? <p className="md-field__note">{hint}</p> : null}
            <button type="button" className="md-action" disabled={saving} onClick={() => void save()}>
              {saving ? "در حال ذخیره…" : "ذخیره"}
            </button>
          </section>
        </div>
      </PullToRefresh>
    </div>
  );
}
