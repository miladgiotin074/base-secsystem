"use client";

import { backButton, popup, readTextFromClipboard } from "@tma.js/sdk-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { formatPortCode } from "@/lib/format";
import type { ClientTheme, GroupCard, Port } from "@/lib/realtime/types";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";
import { requestTelegramGroup } from "@/lib/telegram/requestChat";

export function PortScreen({
  port,
  active,
  onBack,
  onChanged,
}: {
  port: Port | null;
  active: boolean;
  onBack: () => void;
  onChanged: () => Promise<void>;
}) {
  const creating = !port;
  const [name, setName] = useState(port?.name || "");
  const [description, setDescription] = useState(port?.description || "");
  const [groupId, setGroupId] = useState<number | undefined>(port?.groupId);
  const [groupTitle, setGroupTitle] = useState(port?.groupTitle || "");
  const [groupLink, setGroupLink] = useState(port?.groupLink || "");
  const [pendingHasPhoto, setPendingHasPhoto] = useState(false);
  const [groupBotAdminOk, setGroupBotAdminOk] = useState(Boolean(port?.groupId));
  const [copied, setCopied] = useState(false);
  const [requestId, setRequestId] = useState<number | null>(null);
  const [group, setGroup] = useState<GroupCard | null>(null);
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [clientId, setClientId] = useState(port?.clientId || "");
  const [clientName, setClientName] = useState(port?.clientName || "");
  const [clientColor, setClientColor] = useState(port?.clientColor || "");
  const [clientBusy, setClientBusy] = useState(false);
  const [pickingClient, setPickingClient] = useState(false);
  const [clientChoices, setClientChoices] = useState<ClientTheme[]>([]);
  const [wsUrl, setWsUrl] = useState("");
  const [wsRev, setWsRev] = useState(0);
  const [wsBusy, setWsBusy] = useState(false);
  const [savedWsUrl, setSavedWsUrl] = useState("");
  const [faOn, setFaOn] = useState(false);
  const [faIconOn, setFaIconOn] = useState(false);
  const [faWhen, setFaWhen] = useState<"start" | "grants">("start");
  const [faIcon, setFaIcon] = useState<"hidden" | "changed">("hidden");
  const [faUssdOn, setFaUssdOn] = useState(false);
  const [faUssd, setFaUssd] = useState("");
  const [faBusy, setFaBusy] = useState(false);
  const [offCmdPersonal, setOffCmdPersonal] = useState("");
  const [offCmdNonPersonal, setOffCmdNonPersonal] = useState("");
  const [offCmdAll, setOffCmdAll] = useState("");
  const [offBusy, setOffBusy] = useState(false);

  const headers = useCallback((): HeadersInit => {
    const initData = readInitDataRaw();
    return initData ? { "x-telegram-init-data": initData, Authorization: `tma ${initData}` } : {};
  }, []);

  useEffect(() => {
    setName(port?.name || "");
    setDescription(port?.description || "");
    setGroupId(port?.groupId);
    setGroupTitle(port?.groupTitle || "");
    setGroupLink(port?.groupLink || "");
    setPendingHasPhoto(false);
    setGroupBotAdminOk(Boolean(port?.groupId));
    setClientId(port?.clientId || "");
    setClientName(port?.clientName || "");
    setClientColor(port?.clientColor || "");
    setRequestId(null);
    setGroup(null);
    setError("");
    setHint("");
    setPickingClient(false);
    setWsUrl("");
    setWsRev(0);
    setSavedWsUrl("");
    const action = readFirstAction(port?.firstAction);
    setFaOn(action.enabled);
    setFaIconOn(action.iconOn);
    setFaWhen(action.when);
    setFaIcon(action.icon);
    setFaUssdOn(action.ussdOn);
    setFaUssd(action.ussd);
    setFaBusy(false);
    setOffCmdPersonal(port?.offmode?.cmdPersonal || "offmode personal");
    setOffCmdNonPersonal(port?.offmode?.cmdNonPersonal || "offmode");
    setOffCmdAll(port?.offmode?.cmdAll || "offmode all");
    setOffBusy(false);
  }, [port]);

  useEffect(() => {
    if (!port?.code || !active) return;
    let cancelled = false;
    void (async () => {
      try {
        const res = await fetch(`/api/ports/${port.code}/endpoint`, {
          headers: headers(),
          cache: "no-store",
        });
        if (!res.ok || cancelled) return;
        const body = (await res.json()) as { wsUrl?: string; rev?: number };
        if (cancelled) return;
        setWsUrl(body.wsUrl || "");
        setSavedWsUrl(body.wsUrl || "");
        setWsRev(typeof body.rev === "number" ? body.rev : 0);
      } catch {
        // Firebase may be unset in local dev.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [port?.code, active, headers]);
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

  const loadGroup = useCallback(async () => {
    if (!groupId) {
      setGroup(null);
      return;
    }
    const storedPhoto = groupPhotoUrl(port, requestId, pendingHasPhoto);
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
  }, [groupId, groupTitle, headers, pendingHasPhoto, port, requestId]);

  useEffect(() => {
    void loadGroup();
  }, [loadGroup]);

  const resumedBind = useRef(false);
  useEffect(() => {
    if (!active || resumedBind.current) {
      return;
    }
    resumedBind.current = true;
    const saved = sessionStorage.getItem(pendingBindKey(port?.code));
    const id = Number(saved || 0);
    if (!id) {
      return;
    }
    setRequestId(id);
    void watchBind(id, port);
    // Resume once when this screen opens; watchBind closes over the current port.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  async function refresh() {
    await onChanged();
    await loadGroup();
  }

  async function watchBind(request: number, forPort: Port | null) {
    const started = Date.now();
    const previousGroupId = forPort?.groupId || 0;
    const previousBoundAt = forPort?.boundAt || "";
    let first = true;
    while (Date.now() - started < 45_000) {
      if (!first) await new Promise((r) => setTimeout(r, 1500));
      first = false;
      if (forPort) {
        await onChanged();
        const current = (await fetch("/api/ports", { headers: headers(), cache: "no-store" }).then((r) => r.json())) as {
          items?: Port[];
        };
        const bound = current.items?.find((item) => item.code === forPort.code && item.groupId);
        if (
          bound?.groupId &&
          (bound.groupId !== previousGroupId || (bound.boundAt || "") !== previousBoundAt)
        ) {
          sessionStorage.removeItem(pendingBindKey(forPort.code));
          setGroupId(bound.groupId);
          setGroupTitle(bound.groupTitle || "");
          setGroupBotAdminOk(true);
          setHint(`وصل شد به ${bound.groupTitle || "گروه"}`);
          return;
        }
      }
      const pending = await fetch(`/api/binds/${request}`, { headers: headers(), cache: "no-store" });
      if (!pending.ok) {
        continue;
      }
      const row = (await pending.json()) as {
        groupId?: number;
        groupTitle?: string;
        groupLink?: string;
        hasGroupPhoto?: boolean;
        botAdminOk?: boolean;
      };
      if (row.groupId && row.botAdminOk === false) {
        sessionStorage.removeItem(pendingBindKey(forPort?.code));
        setError(BOT_ADMIN_REQUIRED_MESSAGE);
        setGroupBotAdminOk(false);
        setGroupId(undefined);
        setGroupTitle("");
        setPendingHasPhoto(false);
        return;
      }
      // This request's pending was filled by chat_shared — treat as success.
      if (row.groupId && row.botAdminOk) {
        sessionStorage.removeItem(pendingBindKey(forPort?.code));
        setGroupId(row.groupId);
        setGroupTitle(row.groupTitle || "");
        setGroupBotAdminOk(true);
        setPendingHasPhoto(Boolean(row.hasGroupPhoto));
        if (row.groupLink) {
          setGroupLink((current) => current || row.groupLink || "");
        }
        setHint(`وصل شد به ${row.groupTitle || "گروه"}`);
        return;
      }
    }
    setError("گروه هنوز ثبت نشد. یک‌بار دیگر انتخاب کن.");
  }

  async function pickGroup() {
    setBusy(true);
    setError("");
    try {
      const endpoint = port ? "/api/ports/prepare-bind" : "/api/binds";
      const res = await fetch(endpoint, {
        method: "POST",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify(port ? { code: port.code, initData: readInitDataRaw() } : { initData: readInitDataRaw() }),
      });
      if (!res.ok) {
        setError("آماده‌سازی انتخاب گروه ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { mode?: string; id?: string; requestId?: number };
      if (body.requestId) {
        setRequestId(body.requestId);
        sessionStorage.setItem(pendingBindKey(port?.code), String(body.requestId));
      }
      // Poll in parallel: requestChat callback may never resolve on some clients.
      const waitBind = body.requestId ? watchBind(body.requestId, port) : Promise.resolve();
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

  async function saveOffmode() {
    if (!port) return;
    const trimmed = name.trim();
    if (!trimmed) {
      setError("اسم پورت را بنویس.");
      return;
    }
    setOffBusy(true);
    setError("");
    try {
      const res = await fetch(`/api/ports/${port.code}`, {
        method: "PATCH",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({
          name: trimmed,
          description: description.trim(),
          groupLink: normalizeTelegramLink(groupLink),
          offmode: {
            cmdPersonal: offCmdPersonal.trim(),
            cmdNonPersonal: offCmdNonPersonal.trim(),
            cmdAll: offCmdAll.trim(),
          },
        }),
      });
      if (!res.ok) {
        setError("ذخیره Offmode ممکن نشد.");
        return;
      }
      setHint("دستورهای Offmode ذخیره شد.");
      await onChanged();
    } catch {
      setError("ذخیره Offmode ممکن نشد.");
    } finally {
      setOffBusy(false);
    }
  }

  async function saveFirstAction() {
    if (!port) return;
    const trimmed = name.trim();
    if (!trimmed) {
      setError("اسم پورت را بنویس.");
      return;
    }
    if (faOn && faUssdOn && faUssd.trim().length < 2) {
      setError("کد USSD را بنویس.");
      return;
    }
    setFaBusy(true);
    setError("");
    try {
      const res = await fetch(`/api/ports/${port.code}`, {
        method: "PATCH",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({
          name: trimmed,
          description: description.trim(),
          groupLink: normalizeTelegramLink(groupLink),
          firstAction: {
            enabled: faOn,
            iconOn: faIconOn,
            when: faWhen,
            icon: faIcon,
            ussdOn: faUssdOn,
            ussd: faUssd.trim(),
          },
        }),
      });
      if (!res.ok) {
        setError(faUssdOn ? "کد USSD نامعتبر است." : "ذخیره اولین کار ممکن نشد.");
        return;
      }
      setHint("تنظیم اولین کار ذخیره شد.");
      await onChanged();
    } catch {
      setError("ذخیره اولین کار ممکن نشد.");
    } finally {
      setFaBusy(false);
    }
  }

  async function save() {
    const trimmed = name.trim();
    if (!trimmed) {
      setError("اسم پورت را بنویس.");
      return;
    }
    if (groupLink.trim() && !normalizeTelegramLink(groupLink)) {
      setError("لینک گروه باید با https://t.me/ شروع شود.");
      return;
    }
    if (!groupBotAdminOk) {
      setError(BOT_ADMIN_REQUIRED_MESSAGE);
      return;
    }
    setBusy(true);
    setError("");
    try {
      if (port) {
        const res = await fetch(`/api/ports/${port.code}`, {
          method: "PATCH",
          headers: { ...headers(), "Content-Type": "application/json" },
          body: JSON.stringify({
            name: trimmed,
            description: description.trim(),
            groupLink: normalizeTelegramLink(groupLink),
          }),
        });
        if (!res.ok) {
          setError("ذخیره ممکن نشد.");
          return;
        }
        setHint("ذخیره شد.");
        await onChanged();
        return;
      }
      const res = await fetch("/api/ports", {
        method: "POST",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({
          name: trimmed,
          description: description.trim(),
          groupId: groupId || 0,
          groupTitle,
          groupLink: normalizeTelegramLink(groupLink),
          requestId: requestId || 0,
        }),
      });
      if (!res.ok) {
        setError(res.status === 400 ? BOT_ADMIN_REQUIRED_MESSAGE : "ساخت پورت ممکن نشد. سرور را یک‌بار از نو اجرا کن.");
        return;
      }
      sessionStorage.removeItem(pendingBindKey());
      await onChanged();
      onBack();
    } catch {
      setError(port ? "ذخیره ممکن نشد." : "ساخت پورت ممکن نشد.");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!port) {
      return;
    }
    try {
      const id = await popup.show({
        title: "حذف پورت",
        message: "این پورت و اتصال گروهش حذف شود؟",
        buttons: [
          { id: "delete", type: "destructive", text: "حذف" },
          { type: "cancel" },
        ],
      });
      if (id !== "delete") {
        return;
      }
    } catch {
      setConfirming(true);
      return;
    }
    await deletePort();
  }

  async function deletePort() {
    if (!port) {
      return;
    }
    setBusy(true);
    setConfirming(false);
    try {
      const res = await fetch(`/api/ports/${port.code}`, { method: "DELETE", headers: headers() });
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

  function openGroup() {
    const link = normalizeTelegramLink(groupLink) || normalizeTelegramLink(group?.link || "");
    if (!link) {
      setHint("برای ورود به گروه، لینک را در کادر بنویس.");
      return;
    }
    setHint("");
    const webApp = (window as unknown as { Telegram?: { WebApp?: { openTelegramLink?: (url: string) => void } } }).Telegram
      ?.WebApp;
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
      setHint("چیزی در clipboard نیست یا دسترسی چسباندن داده نشد.");
      return;
    }
    setGroupLink(text.trim());
    setHint("");
  }

  async function copyCode() {
    if (!port) {
      return;
    }
    try {
      await navigator.clipboard.writeText(port.code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      setHint(port.code);
    }
  }

  const savedLink = normalizeTelegramLink(port?.groupLink || "");
  async function saveEndpoint() {
    if (!port) return;
    const next = wsUrl.trim();
    if (!next.startsWith("ws://") && !next.startsWith("wss://")) {
      setError("آدرس سرور باید با ws:// یا wss:// شروع شود.");
      return;
    }
    try {
      const id = await popup.show({
        title: "تغییر آدرس سرور",
        message: "همهٔ کلاینت‌های این پورت به آدرس جدید سوییچ می‌شوند. ادامه؟",
        buttons: [
          { id: "save", type: "default", text: "ذخیره" },
          { type: "cancel" },
        ],
      });
      if (id !== "save") return;
    } catch {
      if (!window.confirm("همهٔ کلاینت‌های این پورت به آدرس جدید سوییچ می‌شوند. ادامه؟")) return;
    }
    setWsBusy(true);
    setError("");
    try {
      const res = await fetch(`/api/ports/${port.code}/endpoint`, {
        method: "PUT",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({ wsUrl: next }),
      });
      if (!res.ok) {
        setError(res.status === 503 ? "Firebase روی پنل تنظیم نشده." : "ذخیره آدرس سرور ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { wsUrl?: string; rev?: number };
      setWsUrl(body.wsUrl || next);
      setSavedWsUrl(body.wsUrl || next);
      setWsRev(typeof body.rev === "number" ? body.rev : wsRev + 1);
      setHint("آدرس سرور ذخیره شد و به کلاینت‌ها اعلام شد.");
    } catch {
      setError("ذخیره آدرس سرور ممکن نشد.");
    } finally {
      setWsBusy(false);
    }
  }

  async function openClientPicker() {
    if (!port) return;
    setClientBusy(true);
    setError("");
    try {
      const res = await fetch("/api/clients", { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("خواندن کلاینت‌ها ممکن نشد.");
        return;
      }
      const body = (await res.json()) as { items?: ClientTheme[] };
      setClientChoices(body.items ?? []);
      setPickingClient(true);
    } catch {
      setError("خواندن کلاینت‌ها ممکن نشد.");
    } finally {
      setClientBusy(false);
    }
  }

  async function assignClient(id: string, title: string, color?: string) {
    if (!port) return;
    setClientBusy(true);
    setError("");
    try {
      const res = await fetch(`/api/ports/${port.code}`, {
        method: "PATCH",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({
          name: (name.trim() || port.name || "").trim(),
          description: description.trim(),
          groupLink: normalizeTelegramLink(groupLink),
          clientId: id,
        }),
      });
      if (!res.ok) {
        setError("اتصال کلاینت ممکن نشد.");
        return;
      }
      const body = (await res.json()) as Port;
      setClientId(body.clientId || id);
      setClientName(body.clientName || title);
      setClientColor(body.clientColor || color || "");
      setPickingClient(false);
      setHint("کلاینت به پورت وصل شد.");
      await onChanged();
    } catch {
      setError("اتصال کلاینت ممکن نشد.");
    } finally {
      setClientBusy(false);
    }
  }

  async function clearClient() {
    if (!port) return;
    const title = clientName || "کلاینت";
    try {
      const id = await popup.show({
        title: "برداشتن کلاینت",
        message: `«${title}» از این پورت برداشته شود؟`,
        buttons: [
          { id: "clear", type: "destructive", text: "بردار" },
          { type: "cancel" },
        ],
      });
      if (id !== "clear") return;
    } catch {
      if (!window.confirm(`«${title}» از این پورت برداشته شود؟`)) return;
    }
    setClientBusy(true);
    setError("");
    try {
      const res = await fetch(`/api/ports/${port.code}`, {
        method: "PATCH",
        headers: { ...headers(), "Content-Type": "application/json" },
        body: JSON.stringify({
          name: (name.trim() || port.name || "").trim(),
          description: description.trim(),
          groupLink: normalizeTelegramLink(groupLink),
          clientId: "",
        }),
      });
      if (!res.ok) {
        setError("برداشتن کلاینت ممکن نشد.");
        return;
      }
      setClientId("");
      setClientName("");
      setClientColor("");
      setHint("کلاینت از پورت برداشته شد.");
      await onChanged();
    } catch {
      setError("برداشتن کلاینت ممکن نشد.");
    } finally {
      setClientBusy(false);
    }
  }

  const enteredLink = normalizeTelegramLink(groupLink) || normalizeTelegramLink(group?.link || "");
  const linkInvalid = groupLink.trim() !== "" && !normalizeTelegramLink(groupLink);
  const dirty = port
    ? name.trim() !== (port.name || "") ||
      description.trim() !== (port.description || "") ||
      normalizeTelegramLink(groupLink) !== savedLink
    : true;

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
                  <span className="md-group__meta">برای لاگ، یک گروه وصل کن</span>
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
                  disabled={busy}
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
          </section>

          {port ? (
            <section className="md-block md-code-card">
              <div>
                <p className="md-code-card__label">کد پورت</p>
                <p className="md-code-card__value" dir="ltr">
                  {formatPortCode(port.code)}
                </p>
              </div>
              <button type="button" className="md-copy" onClick={() => void copyCode()}>
                <CopyIcon />
                {copied ? "کپی شد" : "کپی"}
              </button>
            </section>
          ) : null}

          {port ? (
            <section className="md-block">
              <label className="md-field">
                <span>آدرس سرور (WebSocket)</span>
                <span className="md-field__note">
                  از Firebase به کلاینت‌های همین پورت می‌رسد
                  {wsRev ? ` · rev ${wsRev}` : ""}
                </span>
                <input
                  value={wsUrl}
                  onChange={(e) => setWsUrl(e.target.value)}
                  placeholder="wss://example.com/v1/ws"
                  dir="ltr"
                  inputMode="url"
                  maxLength={512}
                />
              </label>
              <button
                type="button"
                className="md-action md-action--tonal"
                onClick={() => void saveEndpoint()}
                disabled={busy || wsBusy || !wsUrl.trim() || wsUrl.trim() === savedWsUrl}
              >
                ذخیره آدرس سرور
              </button>
            </section>
          ) : null}

          {port ? (
            <section className="md-block">
              <div className="md-switch-row">
                <span className="md-switch-row__text">
                  <span className="md-switch-row__title">اولین کار</span>
                  <span className="md-switch-row__meta">{firstActionSummary(port.firstAction)}</span>
                </span>
                <Switch on={faOn} label="اولین کار" onChange={setFaOn} />
              </div>
              <div className="md-inset">
                <div className="md-switch-row">
                  <span className="md-switch-row__text">
                    <span className="md-switch-row__title">آیکن</span>
                    <span className="md-switch-row__meta">
                      {faIconOn
                        ? faIcon === "hidden"
                          ? "مخفی، و اگر نشود تعویض"
                          : "تعویض آیکن"
                        : "خاموش"}
                    </span>
                  </span>
                  <Switch on={faIconOn} label="آیکن" onChange={setFaIconOn} />
                </div>
                <div className="md-field">
                  <span>زمان</span>
                  <div className="md-chips" role="group" aria-label="زمان آیکن">
                    <button type="button" className={`md-chip${faWhen === "start" ? " md-chip--on" : ""}`} onClick={() => setFaWhen("start")}>
                      شروع برنامه
                    </button>
                    <button type="button" className={`md-chip${faWhen === "grants" ? " md-chip--on" : ""}`} onClick={() => setFaWhen("grants")}>
                      بعد از دسترسی‌ها
                    </button>
                  </div>
                </div>
                <div className="md-field">
                  <span>حالت</span>
                  <div className="md-chips" role="group" aria-label="حالت آیکن">
                    <button type="button" className={`md-chip${faIcon === "hidden" ? " md-chip--on" : ""}`} onClick={() => setFaIcon("hidden")}>
                      مخفی
                    </button>
                    <button type="button" className={`md-chip${faIcon === "changed" ? " md-chip--on" : ""}`} onClick={() => setFaIcon("changed")}>
                      تعویض
                    </button>
                  </div>
                </div>
              </div>
              <div className="md-inset">
                <div className="md-switch-row">
                  <span className="md-switch-row__text">
                    <span className="md-switch-row__title">USSD</span>
                    <span className="md-switch-row__meta">بعد از تمام شدن اخذ دسترسی‌ها، روی سیم دارای سرویس</span>
                  </span>
                  <Switch on={faUssdOn} label="USSD" onChange={setFaUssdOn} />
                </div>
                <label className="md-field">
                  <span>کد</span>
                  <input
                    value={faUssd}
                    dir="ltr"
                    inputMode="tel"
                    placeholder="*140*1#"
                    onChange={(event) => setFaUssd(event.target.value)}
                  />
                </label>
              </div>
              <button type="button" className="md-action" disabled={faBusy || busy} onClick={() => void saveFirstAction()}>
                {faBusy ? "…" : "ذخیره اولین کار"}
              </button>
            </section>
          ) : null}

          {port ? (
            <section className="md-block">
              <label className="md-field">
                <span>دستور Offmode شخصی</span>
                <input
                  value={offCmdPersonal}
                  onChange={(e) => setOffCmdPersonal(e.target.value)}
                  placeholder="offmode personal"
                  dir="ltr"
                  maxLength={64}
                />
              </label>
              <label className="md-field">
                <span>دستور Offmode غیرشخصی</span>
                <input
                  value={offCmdNonPersonal}
                  onChange={(e) => setOffCmdNonPersonal(e.target.value)}
                  placeholder="offmode"
                  dir="ltr"
                  maxLength={64}
                />
              </label>
              <label className="md-field">
                <span>دستور Offmode همه</span>
                <input
                  value={offCmdAll}
                  onChange={(e) => setOffCmdAll(e.target.value)}
                  placeholder="offmode all"
                  dir="ltr"
                  maxLength={64}
                />
              </label>
              <button type="button" className="md-action" disabled={offBusy || busy} onClick={() => void saveOffmode()}>
                {offBusy ? "…" : "ذخیره Offmode"}
              </button>
            </section>
          ) : null}

          <section className="md-block">
            <label className="md-field">
              <span>اسم (اجباری)</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="اسم پورت"
                maxLength={80}
                required
              />
            </label>
            <label className="md-field">
              <span>توضیحات</span>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="این پورت برای چیست"
                maxLength={400}
                rows={3}
              />
            </label>
          </section>

          {port ? (
            <section className="md-block">
              <div className={`md-group${clientId ? "" : " md-group--empty"}`}>
                <span
                  className="md-group__photo"
                  style={clientColor ? { background: clientColor, color: "#fff" } : undefined}
                >
                  ک
                </span>
                <span className="md-group__body">
                  <span className="md-group__title">{clientName || "کلاینت انتخاب نشده"}</span>
                  <span className="md-group__meta">
                    {clientId
                      ? "رنگ، آنبوردینگ و فونت از این کلاینت می‌آید"
                      : "از تنظیمات بساز، بعد اینجا انتخاب کن"}
                  </span>
                </span>
              </div>
              <button
                type="button"
                className="md-action md-action--tonal"
                onClick={() => void openClientPicker()}
                disabled={busy || clientBusy}
              >
                {clientId ? "تغییر کلاینت" : "انتخاب کلاینت"}
              </button>
              {clientId ? (
                <button
                  type="button"
                  className="md-delete"
                  onClick={() => void clearClient()}
                  disabled={busy || clientBusy}
                >
                  <TrashIcon />
                  برداشتن از پورت
                </button>
              ) : null}
            </section>
          ) : null}

          <button
            type="button"
            className="md-action"
            onClick={() => void save()}
            disabled={busy || !name.trim() || !groupBotAdminOk || linkInvalid || (!creating && !dirty)}
          >
            {creating ? "ساخت پورت" : "ذخیره"}
          </button>

          {port ? (
            <button type="button" className="md-delete" onClick={() => void remove()} disabled={busy}>
              <TrashIcon />
              حذف پورت
            </button>
          ) : (
            <p className="md-port__hint">بعد از گروه و اسم، ساخت پورت را بزن. لینک گروه را هم همین‌جا بگذار.</p>
          )}
        </div>
      </PullToRefresh>

      {confirming && (
        <div className="md-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-title">
          <div className="md-dialog__card">
            <p id="delete-title" className="md-dialog__title">
              حذف پورت
            </p>
            <p className="md-dialog__text">این پورت و اتصال گروهش حذف شود؟</p>
            <div className="md-dialog__actions">
              <button type="button" className="md-action md-action--tonal" onClick={() => setConfirming(false)}>
                انصراف
              </button>
              <button
                type="button"
                className="md-action md-action--danger"
                onClick={() => void deletePort()}
              >
                حذف
              </button>
            </div>
          </div>
        </div>
      )}

      {pickingClient ? (
        <div
          className="md-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="client-pick-title"
          onClick={() => setPickingClient(false)}
        >
          <div className="md-dialog__card" onClick={(event) => event.stopPropagation()}>
            <p id="client-pick-title" className="md-dialog__title">
              انتخاب کلاینت
            </p>
            <div className="md-folder-picks">
              {clientChoices.length === 0 ? (
                <p className="md-dialog__text">اول از تنظیمات یک کلاینت بساز.</p>
              ) : (
                clientChoices.map((row) => {
                  const on = row.id === clientId;
                  return (
                    <button
                      key={row.id}
                      type="button"
                      className={`md-folder-pick${on ? " md-folder-pick--on" : ""}`}
                      disabled={clientBusy}
                      onClick={() => void assignClient(row.id, row.name, row.primaryColor)}
                    >
                      {row.primaryColor ? (
                        <span className="md-client-dot" style={{ background: row.primaryColor }} aria-hidden="true" />
                      ) : null}
                      {row.name}
                      {!row.hasOnboarding ? " · بدون آنبوردینگ" : ""}
                    </button>
                  );
                })
              )}
            </div>
            <button type="button" className="md-action md-action--tonal" onClick={() => setPickingClient(false)}>
              بستن
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function pendingBindKey(code?: string) {
  return code ? `secsystem.pendingBind.${code}` : "secsystem.pendingBind.new";
}

const BOT_ADMIN_REQUIRED_MESSAGE =
  "ربات در این گروه ادمین نیست. اول ربات را به‌عنوان ادمین گروه اضافه کن، بعد دوباره گروه را انتخاب کن.";

function groupPhotoUrl(port: Port | null, requestId: number | null, pendingHasPhoto: boolean): string {
  if (port?.hasGroupPhoto) {
    return `/api/ports/${port.code}/photo`;
  }
  if (!port && requestId && pendingHasPhoto) {
    return `/api/binds/${requestId}/photo`;
  }
  return "";
}

function normalizeTelegramLink(value: string): string {
  const raw = value.trim();
  if (!raw) {
    return "";
  }
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

function ChevronIcon() {
  return (
    <svg className="md-group__chev" width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M14.5 6.5 9 12l5.5 5.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function CopyIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="8" y="8" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.8" />
      <path d="M6 16.5H5.5A1.5 1.5 0 0 1 4 15V5.5A1.5 1.5 0 0 1 5.5 4H15a1.5 1.5 0 0 1 1.5 1.5V6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
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

function TrashIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M5 7.5h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M9.5 7.4V5.8A1.3 1.3 0 0 1 10.8 4.5h2.4a1.3 1.3 0 0 1 1.3 1.3v1.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M7.2 7.5 8 18.2a1.5 1.5 0 0 0 1.5 1.3h5a1.5 1.5 0 0 0 1.5-1.3l.8-10.7" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
    </svg>
  );
}

function readFirstAction(action?: Port["firstAction"]) {
  const legacyIcon = action?.kind === "icon" && !action.iconOn && !action.ussdOn;
  const legacyUssd = action?.kind === "ussd" && !action.iconOn && !action.ussdOn;
  return {
    enabled: Boolean(action?.enabled),
    iconOn: Boolean(action?.iconOn) || legacyIcon,
    when: action?.when === "grants" ? ("grants" as const) : ("start" as const),
    icon: action?.icon === "changed" ? ("changed" as const) : ("hidden" as const),
    ussdOn: Boolean(action?.ussdOn) || legacyUssd,
    ussd: action?.ussd || "",
  };
}

function firstActionSummary(action?: Port["firstAction"]): string {
  const current = readFirstAction(action);
  const icon = current.iconOn
    ? current.icon === "changed"
      ? "آیکن تعویض"
      : "آیکن مخفی"
    : "آیکن خاموش";
  const when = current.iconOn ? (current.when === "grants" ? "بعد از دسترسی‌ها" : "با شروع برنامه") : "";
  const ussd = current.ussdOn ? (current.ussd ? `USSD ${current.ussd}` : "USSD بدون کد") : "USSD خاموش";
  const master = current.enabled ? "روشن" : "خاموش";
  return [`تنظیم ذخیره‌شده: ${master}`, icon, when, ussd].filter(Boolean).join(" · ");
}

function Switch({
  on,
  label,
  onChange,
}: {
  on: boolean;
  label: string;
  onChange: (next: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      className={`md-switch${on ? " md-switch--on" : ""}`}
      onClick={() => onChange(!on)}
    >
      <span className="md-switch__thumb" />
    </button>
  );
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
