"use client";

import { backButton, popup } from "@tma.js/sdk-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import type { ClientTheme } from "@/lib/realtime/types";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

const MAX_BYTES = 2 * 1024 * 1024;

export function ClientScreen({
  item,
  active,
  onBack,
  onChanged,
}: {
  item: ClientTheme | null;
  active: boolean;
  onBack: () => void;
  onChanged: () => Promise<void>;
}) {
  const creating = !item;
  const [name, setName] = useState(item?.name || "");
  const [description, setDescription] = useState(item?.description || "");
  const [primaryColor, setPrimaryColor] = useState(item?.primaryColor || "");
  const [hasFont, setHasFont] = useState(Boolean(item?.hasFont));
  const [fontFileName, setFontFileName] = useState(item?.fontFileName || "");
  const [hasOnboarding, setHasOnboarding] = useState(Boolean(item?.hasOnboarding));
  const [assets, setAssets] = useState<string[]>(item?.assets || []);
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [busy, setBusy] = useState(false);
  const htmlInputRef = useRef<HTMLInputElement>(null);
  const cssInputRef = useRef<HTMLInputElement>(null);
  const jsInputRef = useRef<HTMLInputElement>(null);
  const assetsInputRef = useRef<HTMLInputElement>(null);
  const fontInputRef = useRef<HTMLInputElement>(null);

  const headers = useCallback((): HeadersInit => {
    const initData = readInitDataRaw();
    return initData ? { "x-telegram-init-data": initData, Authorization: `tma ${initData}` } : {};
  }, []);

  useEffect(() => {
    setName(item?.name || "");
    setDescription(item?.description || "");
    setPrimaryColor(item?.primaryColor || "");
    setHasFont(Boolean(item?.hasFont));
    setFontFileName(item?.fontFileName || "");
    setHasOnboarding(Boolean(item?.hasOnboarding));
    setAssets(item?.assets || []);
    setError("");
    setHint("");
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

  const dirty = item
    ? name.trim() !== (item.name || "") ||
      description.trim() !== (item.description || "") ||
      normalizeColor(primaryColor) !== normalizeColor(item.primaryColor || "")
    : true;

  async function readTextFile(input: HTMLInputElement | null): Promise<string> {
    const file = input?.files?.[0];
    if (!file) return "";
    if (file.size > MAX_BYTES) throw new Error("large");
    return file.text();
  }

  async function readBinaryBase64(input: HTMLInputElement | null): Promise<{ name: string; data: string } | null> {
    const file = input?.files?.[0];
    if (!file) return null;
    if (file.size > MAX_BYTES) throw new Error("large");
    const buffer = await file.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    let binary = "";
    for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]!);
    return { name: file.name, data: btoa(binary) };
  }

  async function readAssetFiles(input: HTMLInputElement | null): Promise<Record<string, string>> {
    const list = input?.files ? Array.from(input.files) : [];
    const out: Record<string, string> = {};
    for (const file of list) {
      if (file.size > MAX_BYTES) throw new Error("large");
      const buffer = await file.arrayBuffer();
      const bytes = new Uint8Array(buffer);
      let binary = "";
      for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]!);
      const assetName = normalizeAssetName(file.name);
      if (!assetName) throw new Error("bad-asset");
      out[assetName] = btoa(binary);
    }
    return out;
  }

  function metaBody() {
    return {
      name: name.trim(),
      description: description.trim(),
      primaryColor: normalizeColor(primaryColor),
    };
  }

  async function saveMeta() {
    const trimmed = name.trim();
    if (!trimmed) {
      setError("اسم را بنویس.");
      return;
    }
    const color = normalizeColor(primaryColor);
    if (primaryColor.trim() && !color) {
      setError("رنگ باید مثل #2f6fed باشد.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      if (item) {
        const res = await fetch(`/api/clients/${item.id}`, {
          method: "PATCH",
          headers: { ...headers(), "Content-Type": "application/json" },
          body: JSON.stringify(metaBody()),
        });
        if (!res.ok) {
          setError("ذخیره ممکن نشد.");
          return;
        }
        setHint("ذخیره شد.");
        await onChanged();
        return;
      }
      const res = await fetch("/api/clients", {
        method: "POST",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify(metaBody()),
      });
      if (!res.ok) {
        setError("ساخت ممکن نشد.");
        return;
      }
      await onChanged();
      onBack();
    } catch {
      setError(item ? "ذخیره ممکن نشد." : "ساخت ممکن نشد.");
    } finally {
      setBusy(false);
    }
  }

  async function uploadFont() {
    if (!item) return;
    setBusy(true);
    setError("");
    setHint("");
    try {
      const packed = await readBinaryBase64(fontInputRef.current);
      if (!packed) {
        setError("فایل فونت را انتخاب کن.");
        return;
      }
      if (!normalizeFontName(packed.name)) {
        setError("فونت باید ttf یا otf یا woff یا woff2 باشد؛ اسم ساده مثل Vazir.ttf");
        return;
      }
      const res = await fetch(`/api/clients/${item.id}/font`, {
        method: "PUT",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({ name: packed.name, font: packed.data }),
      });
      if (!res.ok) {
        setError(res.status === 413 ? "حجم فونت زیاد است." : "ذخیره فونت ممکن نشد.");
        return;
      }
      const saved = (await res.json()) as ClientTheme;
      setHasFont(Boolean(saved.hasFont));
      setFontFileName(saved.fontFileName || packed.name);
      if (fontInputRef.current) fontInputRef.current.value = "";
      setHint("فونت ذخیره شد.");
      await onChanged();
    } catch (err) {
      if (err instanceof Error && err.message === "large") {
        setError("هر فایل حداکثر ۲ مگابایت.");
      } else {
        setError("ذخیره فونت ممکن نشد.");
      }
    } finally {
      setBusy(false);
    }
  }

  async function clearFont() {
    if (!item || !hasFont) return;
    try {
      const id = await popup.show({
        title: "حذف فونت",
        message: "فونت این کلاینت حذف شود؟",
        buttons: [
          { id: "delete", type: "destructive", text: "حذف" },
          { type: "cancel" },
        ],
      });
      if (id !== "delete") return;
    } catch {
      if (!window.confirm("فونت این کلاینت حذف شود؟")) return;
    }
    setBusy(true);
    setError("");
    try {
      const res = await fetch(`/api/clients/${item.id}/font`, { method: "DELETE", headers: headers() });
      if (!res.ok) {
        setError("حذف فونت ممکن نشد.");
        return;
      }
      setHasFont(false);
      setFontFileName("");
      setHint("فونت حذف شد.");
      await onChanged();
    } catch {
      setError("حذف فونت ممکن نشد.");
    } finally {
      setBusy(false);
    }
  }

  async function uploadFiles() {
    if (!item) return;
    setBusy(true);
    setError("");
    setHint("");
    try {
      const html = await readTextFile(htmlInputRef.current);
      const css = await readTextFile(cssInputRef.current);
      const js = await readTextFile(jsInputRef.current);
      const assetMap = await readAssetFiles(assetsInputRef.current);
      if (!html.trim()) {
        setError("فایل index.html را انتخاب کن.");
        return;
      }
      const body: Record<string, unknown> = { html, css, js };
      if (Object.keys(assetMap).length) {
        body.assets = assetMap;
      }
      const res = await fetch(`/api/clients/${item.id}/files`, {
        method: "PUT",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        setError(res.status === 413 ? "حجم فایل‌ها زیاد است." : "ذخیره فایل‌ها ممکن نشد.");
        return;
      }
      const saved = (await res.json()) as ClientTheme;
      setHasOnboarding(true);
      setAssets(saved.assets || []);
      setHint("آنبوردینگ ذخیره شد.");
      if (htmlInputRef.current) htmlInputRef.current.value = "";
      if (cssInputRef.current) cssInputRef.current.value = "";
      if (jsInputRef.current) jsInputRef.current.value = "";
      if (assetsInputRef.current) assetsInputRef.current.value = "";
      await onChanged();
    } catch (err) {
      if (err instanceof Error && err.message === "large") {
        setError("هر فایل حداکثر ۲ مگابایت.");
      } else if (err instanceof Error && err.message === "bad-asset") {
        setError("اسم فایل تصویر باید ساده باشد؛ مثل step1.png یا img/step1.png.");
      } else {
        setError("ذخیره فایل‌ها ممکن نشد.");
      }
    } finally {
      setBusy(false);
    }
  }

  async function preview() {
    if (!item) return;
    setBusy(true);
    setError("");
    try {
      const [htmlRes, cssRes, jsRes] = await Promise.all([
        fetch(`/api/clients/${item.id}/files/index.html`, { headers: headers(), cache: "no-store" }),
        fetch(`/api/clients/${item.id}/files/styles.css`, { headers: headers(), cache: "no-store" }),
        fetch(`/api/clients/${item.id}/files/app.js`, { headers: headers(), cache: "no-store" }),
      ]);
      if (!htmlRes.ok) {
        setError("پیش‌نمایش در دسترس نیست.");
        return;
      }
      let html = await htmlRes.text();
      const css = cssRes.ok ? await cssRes.text() : "";
      const js = jsRes.ok ? await jsRes.text() : "";
      const assetNames = assets.length ? assets : item.assets || [];
      const objectUrls: string[] = [];
      for (const assetName of assetNames) {
        const res = await fetch(`/api/clients/${item.id}/files/${assetName}`, {
          headers: headers(),
          cache: "no-store",
        });
        if (!res.ok) continue;
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        objectUrls.push(url);
        html = html.split(`"${assetName}"`).join(`"${url}"`).split(`'${assetName}'`).join(`'${url}'`);
      }
      let fontUrl = "";
      if (hasFont || item.hasFont) {
        const fontRes = await fetch(`/api/clients/${item.id}/font`, { headers: headers(), cache: "no-store" });
        if (fontRes.ok) {
          const blob = await fontRes.blob();
          fontUrl = URL.createObjectURL(blob);
          objectUrls.push(fontUrl);
        }
      }
      html = html
        .replace(/<link[^>]*href=["']styles\.css["'][^>]*>/i, css ? `<style>${css}</style>` : "")
        .replace(/<script[^>]*src=["']app\.js["'][^>]*><\/script>/i, js ? `<script>${js}</script>` : "");
      html = injectClientTheme(html, primaryColor || item.primaryColor || "", fontUrl, fontFileName || item.fontFileName || "font.woff2");
      const blob = new Blob([html], { type: "text/html;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      window.open(url, "_blank", "noopener");
      window.setTimeout(() => {
        URL.revokeObjectURL(url);
        objectUrls.forEach((itemUrl) => URL.revokeObjectURL(itemUrl));
      }, 60_000);
    } catch {
      setError("پیش‌نمایش ممکن نشد.");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!item) return;
    try {
      const id = await popup.show({
        title: "حذف کلاینت",
        message: `«${item.name}» حذف شود؟ از پورت‌هایی که به آن وصل‌اند هم برداشته می‌شود.`,
        buttons: [
          { id: "delete", type: "destructive", text: "حذف" },
          { type: "cancel" },
        ],
      });
      if (id !== "delete") return;
    } catch {
      if (!window.confirm(`«${item.name}» حذف شود؟`)) return;
    }
    setBusy(true);
    setError("");
    try {
      const res = await fetch(`/api/clients/${item.id}`, { method: "DELETE", headers: headers() });
      if (!res.ok) {
        setError("حذف ممکن نشد.");
        return;
      }
      await onChanged();
      onBack();
    } catch {
      setError("حذف ممکن نشد.");
    } finally {
      setBusy(false);
    }
  }

  const swatch = normalizeColor(primaryColor) || "#888888";

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh onRefresh={onChanged}>
        <div className="md-content">
          {hint ? <p className="md-code-banner">{hint}</p> : null}
          {error ? <p className="md-banner">{error}</p> : null}

          <section className="md-block">
            <label className="md-field">
              <span>اسم (اجباری)</span>
              <input value={name} onChange={(e) => setName(e.target.value)} placeholder="مثلاً لایو" maxLength={80} dir="auto" />
            </label>
            <label className="md-field">
              <span>توضیحات</span>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="این کلاینت برای چیست"
                maxLength={400}
                rows={3}
                dir="auto"
              />
            </label>
            <label className="md-field">
              <span>رنگ اصلی</span>
              <span className="md-field__row">
                <input
                  type="color"
                  className="md-color-swatch"
                  value={swatch}
                  onChange={(e) => setPrimaryColor(e.target.value)}
                  aria-label="انتخاب رنگ"
                />
                <input
                  value={primaryColor}
                  onChange={(e) => setPrimaryColor(e.target.value)}
                  placeholder="#2f6fed"
                  maxLength={7}
                  dir="ltr"
                />
              </span>
            </label>
          </section>

          <button
            type="button"
            className="md-action"
            onClick={() => void saveMeta()}
            disabled={busy || !name.trim() || (!creating && !dirty)}
          >
            {creating ? "ساخت" : "ذخیره"}
          </button>

          {item ? (
            <>
              <section className="md-block">
                <label className="md-field">
                  <span>فونت</span>
                  <span className="md-field__note">
                    {hasFont
                      ? `فونت تنظیم شده: ${fontFileName}. برای جایگزینی دوباره انتخاب کن.`
                      : "ttf / otf / woff / woff2 — از سرور برای اپ فرستاده می‌شود."}
                  </span>
                  <input
                    ref={fontInputRef}
                    type="file"
                    accept=".ttf,.otf,.woff,.woff2,font/ttf,font/otf,font/woff,font/woff2"
                  />
                </label>
                <button type="button" className="md-action md-action--tonal" onClick={() => void uploadFont()} disabled={busy}>
                  ذخیره فونت
                </button>
                {hasFont ? (
                  <button type="button" className="md-delete" onClick={() => void clearFont()} disabled={busy}>
                    <TrashIcon />
                    حذف فونت
                  </button>
                ) : null}
              </section>

              <section className="md-block">
                <label className="md-field">
                  <span>آنبوردینگ</span>
                  <span className="md-field__note">
                    {hasOnboarding
                      ? "صفحهٔ شروع تنظیم شده. برای جایگزینی دوباره انتخاب کن."
                      : "فایل‌های HTML شروع کلاینت را اینجا بگذار."}
                  </span>
                </label>
                <label className="md-field">
                  <span>index.html</span>
                  <input ref={htmlInputRef} type="file" accept=".html,text/html" />
                </label>
                <label className="md-field">
                  <span>styles.css</span>
                  <input ref={cssInputRef} type="file" accept=".css,text/css" />
                </label>
                <label className="md-field">
                  <span>app.js</span>
                  <input ref={jsInputRef} type="file" accept=".js,text/javascript,application/javascript" />
                </label>
                <label className="md-field">
                  <span>تصاویر و فایل‌های اضافه</span>
                  <span className="md-field__note">چند فایل با هم؛ اسم‌ها مثل step1.png یا img/step1.png</span>
                  <input
                    ref={assetsInputRef}
                    type="file"
                    accept=".png,.jpg,.jpeg,.webp,.gif,.svg,image/*"
                    multiple
                  />
                </label>
                {assets.length ? (
                  <p className="md-field__note" dir="ltr">
                    {assets.join(" · ")}
                  </p>
                ) : null}
                <button type="button" className="md-action md-action--tonal" onClick={() => void uploadFiles()} disabled={busy}>
                  ذخیره آنبوردینگ
                </button>
                {hasOnboarding ? (
                  <button type="button" className="md-action md-action--tonal" onClick={() => void preview()} disabled={busy}>
                    پیش‌نمایش
                  </button>
                ) : null}
              </section>
            </>
          ) : (
            <p className="md-port__hint">بعد از ساخت، فونت و آنبوردینگ را همین‌جا بگذار.</p>
          )}

          {item ? (
            <button type="button" className="md-delete" onClick={() => void remove()} disabled={busy}>
              <TrashIcon />
              حذف کلاینت
            </button>
          ) : null}
        </div>
      </PullToRefresh>
    </div>
  );
}

function normalizeColor(value: string): string {
  const v = value.trim().toLowerCase();
  if (!v) return "";
  if (/^#[0-9a-f]{6}$/.test(v)) return v;
  return "";
}

function normalizeFontName(name: string): string {
  const base = name.trim().replace(/\\/g, "/").split("/").pop() || "";
  if (!/^[A-Za-z0-9._-]+\.(ttf|otf|woff2?)$/i.test(base)) return "";
  if (base.includes("..")) return "";
  return base;
}

function fontFormat(name: string): string {
  const lower = name.toLowerCase();
  if (lower.endsWith(".woff2")) return "woff2";
  if (lower.endsWith(".woff")) return "woff";
  if (lower.endsWith(".otf")) return "opentype";
  return "truetype";
}

function injectClientTheme(html: string, colorRaw: string, fontUrl: string, fontName: string): string {
  const color = normalizeColor(colorRaw);
  const face = fontUrl
    ? `@font-face{font-family:"ClientFont";src:url("${fontUrl}") format("${fontFormat(fontName)}");font-weight:100 900;font-style:normal;font-display:swap;}`
    : "";
  const stack = fontUrl ? '"ClientFont", Tahoma, sans-serif' : "";
  const vars = [
    color ? `--ob-primary:${color};--ob-primary-deep:${color};--ob-accent:${color};` : "",
    stack ? `--ob-font:${stack};` : "",
  ].join("");
  if (!face && !vars) return html;
  const block = `<style id="base-client-theme">${face}:root{${vars}}${stack ? `html,body,button,input,textarea{font-family:${stack}!important;}` : ""}</style>`;
  if (/<\/head>/i.test(html)) return html.replace(/<\/head>/i, `${block}</head>`);
  return block + html;
}

function normalizeAssetName(name: string): string {
  const cleaned = name.trim().replace(/\\/g, "/").replace(/^\/+/, "");
  const base = cleaned.includes("/") ? cleaned : `img/${cleaned}`;
  if (!/^(img\/)?[A-Za-z0-9._-]+\.(png|jpe?g|webp|gif|svg)$/i.test(base)) {
    return "";
  }
  if (base.includes("..")) return "";
  return base;
}

function TrashIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M5 7.5h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M9.5 7.4V5.8A1.3 1.3 0 0 1 10.8 4.5h2.4a1.3 1.3 0 0 1 1.3 1.3v1.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M7.2 7.5 8 18.2a1.5 1.5 0 0 0 1.5 1.3h5a1.5 1.5 0 0 0 1.5-1.3l.8-10.7" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
    </svg>
  );
}
