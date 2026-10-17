"use client";

import { backButton } from "@tma.js/sdk-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { telegramAuthHeaders } from "@/lib/telegram/authHeaders";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

export function ApkBuildScreen({
  portCode,
  active = true,
  onBack,
}: {
  portCode: string;
  active?: boolean;
  onBack: () => void;
}) {
  const [label, setLabel] = useState("");
  const [iconPreview, setIconPreview] = useState("");
  const [iconBlob, setIconBlob] = useState<Blob | null>(null);
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [busy, setBusy] = useState(false);
  const iconRef = useRef<HTMLInputElement>(null);

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

  const onIconFile = useCallback(async (file: File | null) => {
    if (!file) return;
    setError("");
    try {
      const png = await toPngBlob(file);
      setIconBlob(png);
      setIconPreview(URL.createObjectURL(png));
    } catch {
      setError("آیکن باید تصویر باشد.");
    }
  }, []);

  async function build() {
    const name = label.trim();
    if (!name) {
      setError("اسم اپ را وارد کن.");
      return;
    }
    if (!iconBlob) {
      setError("آیکن را انتخاب کن.");
      return;
    }
    setBusy(true);
    setError("");
    setHint("");
    try {
      if (!/^[A-Za-z0-9]{20}$/.test(portCode)) {
        setError("کد پورت نامعتبر است. از دکمه «فایل» داخل گروه پورت باز کن.");
        return;
      }
      const form = new FormData();
      form.append("port", portCode);
      form.append("label", name);
      form.append("icon", iconBlob, "icon.png");
      const res = await fetch("/api/apk/build", {
        method: "POST",
        headers: telegramAuthHeaders(readInitDataRaw()),
        body: form,
        cache: "no-store",
      });
      const body = (await res.json().catch(() => ({}))) as { error?: string; fileName?: string };
      if (!res.ok) {
        setError(body.error || `ساخت ناموفق بود (${res.status}).`);
        return;
      }
      setHint(`ارسال شد: ${body.fileName || name}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "ساخت ناموفق بود.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={async () => undefined}>
        <div className="md-content">
          <section className="md-block">
            <label className="md-field">
              <span>اسم اپ</span>
              <input
                value={label}
                onChange={(e) => {
                  setLabel(e.target.value);
                  setHint("");
                }}
                placeholder="مثلاً ابزارها"
                maxLength={40}
                dir="auto"
              />
            </label>
            <div className="md-field">
              <span>آیکن</span>
              <button
                type="button"
                className="md-action md-action--tonal"
                disabled={busy}
                onClick={() => iconRef.current?.click()}
              >
                {iconPreview ? "تعویض آیکن" : "انتخاب آیکن"}
              </button>
              <input
                ref={iconRef}
                type="file"
                accept="image/*"
                hidden
                onChange={(e) => void onIconFile(e.target.files?.[0] || null)}
              />
              {iconPreview ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={iconPreview}
                  alt=""
                  width={72}
                  height={72}
                  style={{ marginTop: 12, borderRadius: 16, objectFit: "cover" }}
                />
              ) : null}
            </div>
            {error ? <p className="md-field__note">{error}</p> : null}
            {hint ? <p className="md-field__note">{hint}</p> : null}
            <button type="button" className="md-action" disabled={busy} onClick={() => void build()}>
              {busy ? "در حال ساخت و ارسال…" : "ساخت فایل"}
            </button>
          </section>
        </div>
      </PullToRefresh>
    </div>
  );
}

async function toPngBlob(file: File): Promise<Blob> {
  if (file.type === "image/png") return file;
  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    const size = 192;
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("canvas");
    const scale = Math.max(size / img.width, size / img.height);
    const w = img.width * scale;
    const h = img.height * scale;
    ctx.drawImage(img, (size - w) / 2, (size - h) / 2, w, h);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
    if (!blob) throw new Error("png");
    return blob;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("image"));
    img.src = src;
  });
}
