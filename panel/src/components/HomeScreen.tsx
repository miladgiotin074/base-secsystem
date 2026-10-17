"use client";

import { popup } from "@tma.js/sdk-react";
import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent, type PointerEvent as ReactPointerEvent, type TransitionEvent } from "react";

import { PullToRefresh } from "@/components/PullToRefresh";
import { builtinFolders, customFolders, homeFoldersWithMuted, pinsFor, readFolders, writeFolders, type HomeFolder } from "@/lib/homeFolders";
import { formatRelative } from "@/lib/format";
import type { Overview, Port, Snapshot } from "@/lib/realtime/types";
import { readInitDataRaw } from "@/lib/telegram/initDataClient";
import { setUserBlocked } from "@/lib/users/block";

const STATS_PORT_KEY = "secsystem.statsPort";

function installAt(user: Snapshot): number {
  let latest = 0;
  for (const device of user.devices) {
    if (device.firstSeen && device.firstSeen > latest) latest = device.firstSeen;
  }
  return latest;
}

export function HomeScreen({
  active = true,
  onOpenUser,
}: {
  active?: boolean;
  onOpenUser?: (userId: string) => void;
}) {
  const [query, setQuery] = useState("");
  const [overview, setOverview] = useState<Overview | null>(null);
  const [error, setError] = useState("");
  const [folders, setFolders] = useState(builtinFolders);
  const [members, setMembers] = useState<Record<string, string[]>>({});
  const [pins, setPins] = useState<Record<string, string[]>>({});
  const [ports, setPorts] = useState<Port[]>([]);
  const [statsPort, setStatsPort] = useState("");
  const [managerOnly, setManagerOnly] = useState(false);
  const [pickingPort, setPickingPort] = useState(false);
  const [tab, setTab] = useState(0);
  const [holdUser, setHoldUser] = useState<Snapshot | null>(null);
  const [creating, setCreating] = useState(false);
  const [folderName, setFolderName] = useState("");
  const [deleteFolder, setDeleteFolder] = useState<{ id: string; title: string } | null>(null);
  const [drag, setDrag] = useState(0);
  const [settling, setSettling] = useState(false);
  const swipeRef = useRef<HTMLDivElement>(null);
  const swipe = useRef({ x: 0, y: 0, dx: 0, active: false, axis: "" as "" | "x" | "y", pointerId: -1 });
  const pendingTab = useRef<number | null>(null);
  const suppressUntil = useRef(0);
  const blockHold = useRef(false);
  const settleTimer = useRef<number | null>(null);

  const loadFolders = useCallback(() => {
    const stored = readFolders();
    const hasMuted = (overview?.items ?? []).some((user) => Boolean(user.watchMuted));
    setFolders(homeFoldersWithMuted(customFolders(stored), hasMuted));
    setMembers(stored.members);
    setPins(stored.pins);
  }, [overview]);

  useEffect(() => {
    loadFolders();
  }, [loadFolders]);

  const load = useCallback(async () => {
    try {
      const initData = readInitDataRaw();
      const headers = initData ? { "x-telegram-init-data": initData } : {};
      const [overviewRes, portsRes] = await Promise.all([
        fetch("/api/overview", { headers, cache: "no-store" }),
        fetch("/api/ports", { headers, cache: "no-store" }),
      ]);
      if (!overviewRes.ok) {
        setError("خواندن وضعیت دستگاه‌ها ممکن نشد.");
        return;
      }
      const body = (await overviewRes.json()) as Overview;
      setOverview(body);
      if (portsRes.ok) {
        const portsBody = (await portsRes.json()) as { items?: Port[] };
        setPorts(portsBody.items || []);
      }
      setError("");
    } catch {
      setError("ارتباط با سرور برقرار نشد.");
    }
  }, []);

  useEffect(() => {
    try {
      setManagerOnly(sessionStorage.getItem("secsystem.panelRole") === "manager");
      setStatsPort(localStorage.getItem(STATS_PORT_KEY) || "");
    } catch {
      setStatsPort("");
    }
  }, []);

  useEffect(() => {
    if (!managerOnly || ports.length === 0) return;
    const codes = new Set(ports.map((p) => p.code));
    if (ports.length === 1) {
      const only = ports[0].code;
      if (statsPort !== only) {
        setStatsPort(only);
        try {
          localStorage.setItem(STATS_PORT_KEY, only);
        } catch {
          /* ignore */
        }
      }
      return;
    }
    if (statsPort && !codes.has(statsPort)) {
      setStatsPort("");
      try {
        localStorage.removeItem(STATS_PORT_KEY);
      } catch {
        /* ignore */
      }
    }
  }, [managerOnly, ports, statsPort]);

  useEffect(() => {
    if (!active) return;
    void load();
    const timer = setInterval(() => void load(), 5000);
    return () => clearInterval(timer);
  }, [active, load]);

  useEffect(() => {
    const max = Math.max(0, folders.length - 1);
    if (tab > max) setTab(max);
  }, [folders.length, tab]);

  const itemsFor = useCallback(
    (folder: HomeFolder) => {
      const q = query.trim().toLowerCase();
      const filtered = (overview?.items ?? []).filter((user) => {
        if (user.blocked) return false;
        if (folder.kind === "muted") {
          if (!user.watchMuted) return false;
        } else if (folder.kind === "online" && !user.online) {
          return false;
        } else if (folder.kind === "offline" && user.online) {
          return false;
        } else if (folder.kind === "custom" && !(members[user.userId] || []).includes(folder.id)) {
          return false;
        }
        if (!q) return true;
        if (user.userId.toLowerCase().includes(q)) return true;
        if ((user.displayName || "").toLowerCase().includes(q)) return true;
        return user.devices.some((d) => d.deviceId.toLowerCase().includes(q) || (d.port || "").toLowerCase().includes(q));
      });
      const rank = new Map(pinsFor(pins, folder.id).map((id, index) => [id, index]));
      return filtered.slice().sort((a, b) => {
        const pa = rank.get(a.userId);
        const pb = rank.get(b.userId);
        const aPinned = pa !== undefined;
        const bPinned = pb !== undefined;
        if (aPinned || bPinned) {
          if (aPinned && bPinned) return pa! - pb!;
          return aPinned ? -1 : 1;
        }
        if (folder.kind === "all") {
          return installAt(b) - installAt(a);
        }
        return 0;
      });
    },
    [members, overview, pins, query],
  );

  const paneItems = useMemo(() => folders.map((folder) => itemsFor(folder)), [folders, itemsFor]);

  const stats = useMemo(() => {
    const items = overview?.items ?? [];
    const scoped = (statsPort
      ? items.filter((user) => user.devices.some((device) => device.port === statsPort))
      : items
    ).filter((user) => !user.blocked);
    let today = 0;
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    const startMs = start.getTime();
    for (const user of scoped) {
      let first = 0;
      for (const device of user.devices) {
        if (device.firstSeen && (!first || device.firstSeen < first)) first = device.firstSeen;
      }
      if (first >= startMs) today += 1;
    }
    return {
      users: scoped.length,
      online: scoped.filter((user) => user.online).length,
      today,
    };
  }, [overview, statsPort]);

  function persist(nextFolders: { id: string; title: string }[], nextMembers: Record<string, string[]>, nextPins?: Record<string, string[]>) {
    const stored = readFolders();
    const pinsNext = nextPins ?? stored.pins;
    writeFolders({ folders: nextFolders, members: nextMembers, pins: pinsNext });
    const hasMuted = (overview?.items ?? []).some((user) => Boolean(user.watchMuted));
    setFolders(homeFoldersWithMuted(nextFolders.map((folder) => ({ ...folder, kind: "custom" as const })), hasMuted));
    setMembers(nextMembers);
    setPins(pinsNext);
  }

  function togglePin(userId: string, folderId: string) {
    const stored = readFolders();
    const current = pinsFor(stored.pins, folderId);
    const list = current.includes(userId) ? current.filter((id) => id !== userId) : [userId, ...current];
    const pinsNext = { ...stored.pins };
    if (list.length) pinsNext[folderId] = list;
    else delete pinsNext[folderId];
    writeFolders({ ...stored, pins: pinsNext });
    setPins(pinsNext);
  }

  function chooseStatsPort(code: string) {
    setStatsPort(code);
    setPickingPort(false);
    try {
      if (code) localStorage.setItem(STATS_PORT_KEY, code);
      else localStorage.removeItem(STATS_PORT_KEY);
    } catch {
      // ignore
    }
  }

  async function askBlockUser(user: Snapshot) {
    const title = (user.displayName || "").trim() || user.userId;
    try {
      const id = await popup.show({
        title: "مسدود کردن",
        message: `کاربر «${title}» مسدود شود؟ دیگر به سرور وصل نمی‌شود.`,
        buttons: [
          { id: "block", type: "destructive", text: "مسدود کن" },
          { type: "cancel" },
        ],
      });
      if (id !== "block") return;
    } catch {
      const ok = window.confirm(`کاربر «${title}» مسدود شود؟`);
      if (!ok) return;
    }
    const ok = await setUserBlocked(user.userId, true);
    setHoldUser(null);
    if (ok) void load();
  }

  function addFolder(title: string) {
    const name = title.trim().slice(0, 24);
    if (!name) return;
    const stored = readFolders();
    if (stored.folders.length >= 12) return;
    const id = `f_${Math.random().toString(36).slice(2, 10)}`;
    const foldersNext = [...stored.folders, { id, title: name }];
    persist(foldersNext, stored.members);
    selectTab(builtinFolders.length + foldersNext.length - 1);
    setCreating(false);
    setFolderName("");
  }

  function removeFolder(id: string) {
    const stored = readFolders();
    const foldersNext = stored.folders.filter((folder) => folder.id !== id);
    const membersNext: Record<string, string[]> = {};
    for (const [userId, ids] of Object.entries(stored.members)) {
      const kept = ids.filter((item) => item !== id);
      if (kept.length) membersNext[userId] = kept;
    }
    const pinsNext = { ...stored.pins };
    delete pinsNext[id];
    persist(foldersNext, membersNext, pinsNext);
    setDeleteFolder(null);
    selectTab(0);
  }

  async function askRemoveFolder(folder: { id: string; title: string }) {
    try {
      const id = await popup.show({
        title: "حذف تب",
        message: `تب «${folder.title}» حذف شود؟`,
        buttons: [
          { id: "delete", type: "destructive", text: "حذف" },
          { type: "cancel" },
        ],
      });
      if (id === "delete") removeFolder(folder.id);
    } catch {
      setDeleteFolder(folder);
    }
  }

  function toggleMember(userId: string, folderId: string) {
    const stored = readFolders();
    const currentIds = new Set(stored.members[userId] || []);
    if (currentIds.has(folderId)) currentIds.delete(folderId);
    else currentIds.add(folderId);
    const membersNext = { ...stored.members, [userId]: [...currentIds] };
    if (membersNext[userId].length === 0) delete membersNext[userId];
    persist(stored.folders, membersNext);
    setHoldUser(null);
  }

  function selectTab(index: number) {
    const next = Math.max(0, Math.min(folders.length - 1, index));
    if (settleTimer.current != null) {
      window.clearTimeout(settleTimer.current);
      settleTimer.current = null;
    }
    pendingTab.current = null;
    setSettling(false);
    setDrag(0);
    setTab(next);
  }

  function paneWidth() {
    return swipeRef.current?.clientWidth || 1;
  }

  function releaseCapture(target: HTMLDivElement) {
    if (swipe.current.pointerId < 0) return;
    try {
      target.releasePointerCapture(swipe.current.pointerId);
    } catch {
      // already released
    }
    swipe.current.pointerId = -1;
  }

  function onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (settling) return;
    blockHold.current = false;
    swipe.current = { x: event.clientX, y: event.clientY, dx: 0, active: true, axis: "", pointerId: event.pointerId };
  }

  function onPointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    if (!swipe.current.active) return;
    const dx = event.clientX - swipe.current.x;
    const dy = event.clientY - swipe.current.y;
    swipe.current.dx = dx;
    if (!swipe.current.axis) {
      if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
      if (Math.abs(dx) > Math.abs(dy) * 1.15) {
        swipe.current.axis = "x";
        blockHold.current = true;
        swipe.current.pointerId = event.pointerId;
        event.currentTarget.setPointerCapture(event.pointerId);
      } else {
        swipe.current.axis = "y";
        swipe.current.active = false;
        return;
      }
    }
    if (swipe.current.axis !== "x") return;
    let shown = dx;
    if (tab <= 0 && shown < 0) shown *= 0.28;
    if (tab >= folders.length - 1 && shown > 0) shown *= 0.28;
    setDrag(shown);
  }

  function onPointerUp(event: ReactPointerEvent<HTMLDivElement>) {
    if (!swipe.current.active && swipe.current.axis !== "x") {
      blockHold.current = false;
      return;
    }
    const dx = swipe.current.dx;
    const axis = swipe.current.axis;
    releaseCapture(event.currentTarget);
    swipe.current = { x: 0, y: 0, dx: 0, active: false, axis: "", pointerId: -1 };
    blockHold.current = false;

    if (axis !== "x") {
      setDrag(0);
      return;
    }

    const w = paneWidth();
    const goNext = dx >= 56 && tab < folders.length - 1;
    const goPrev = dx <= -56 && tab > 0;
    suppressUntil.current = Date.now() + 450;

    if (goNext || goPrev) {
      const next = goNext ? tab + 1 : tab - 1;
      beginSettle((next - tab) * w, next);
      return;
    }

    beginSettle(0, null);
  }

  function finishSettle() {
    if (settleTimer.current != null) {
      window.clearTimeout(settleTimer.current);
      settleTimer.current = null;
    }
    const next = pendingTab.current;
    pendingTab.current = null;
    setSettling(false);
    if (next != null) setTab(next);
    setDrag(0);
  }

  function beginSettle(nextDrag: number, nextTab: number | null) {
    pendingTab.current = nextTab;
    setSettling(true);
    setDrag(nextDrag);
    if (settleTimer.current != null) window.clearTimeout(settleTimer.current);
    settleTimer.current = window.setTimeout(finishSettle, 280);
  }

  function onTrackTransitionEnd(event: TransitionEvent<HTMLDivElement>) {
    if (event.target !== event.currentTarget) return;
    finishSettle();
  }

  function onClickCapture(event: MouseEvent) {
    if (Date.now() >= suppressUntil.current) return;
    event.preventDefault();
    event.stopPropagation();
  }

  const safeTab = Math.min(tab, Math.max(0, folders.length - 1));
  const activeFolder = folders[safeTab] ?? folders[0];
  const activePins = pinsFor(pins, activeFolder?.id || "all");
  const selectedPort = ports.find((port) => port.code === statsPort);
  const statsPortLabel = statsPort
    ? (selectedPort?.name || "").trim() || selectedPort?.code || statsPort
    : managerOnly
      ? "پورت‌های من"
      : "همه پورت‌ها";
  const canPickPort = !(managerOnly && ports.length <= 1);

  return (
    <PullToRefresh onRefresh={load}>
      <div className="md-content">
        <section className="md-stats" aria-label="خلاصه">
          {canPickPort ? (
            <button type="button" className="md-stats__pick" aria-haspopup="dialog" aria-expanded={pickingPort} onClick={() => setPickingPort(true)}>
              <span className="md-stats__pick-icon" aria-hidden="true">
                <PortPickIcon />
              </span>
              <span className="md-stats__pick-body">
                <span className="md-stats__pick-value" dir="auto">
                  {statsPortLabel}
                </span>
                {statsPort && selectedPort?.description?.trim() ? (
                  <span className="md-stats__pick-meta" dir="auto">
                    {selectedPort.description.trim()}
                  </span>
                ) : null}
              </span>
              <span className="md-stats__pick-chev" aria-hidden="true">
                <ExpandDownIcon />
              </span>
            </button>
          ) : (
            <div className="md-stats__pick" aria-hidden="false">
              <span className="md-stats__pick-icon" aria-hidden="true">
                <PortPickIcon />
              </span>
              <span className="md-stats__pick-body">
                <span className="md-stats__pick-value" dir="auto">
                  {statsPortLabel}
                </span>
                {selectedPort?.description?.trim() ? (
                  <span className="md-stats__pick-meta" dir="auto">
                    {selectedPort.description.trim()}
                  </span>
                ) : null}
              </span>
            </div>
          )}
          <div className="md-stats__cards">
            <article className="md-stat-card md-stat-card--users">
              <span className="md-stat-card__icon" aria-hidden="true">
                <UsersStatIcon />
              </span>
              <p className="md-stat-card__value">{stats.users}</p>
              <p className="md-stat-card__label">کل کاربران</p>
            </article>
            <article className="md-stat-card md-stat-card--online">
              <span className="md-stat-card__icon" aria-hidden="true">
                <span className={`md-live-dot${stats.online > 0 ? " md-live-dot--on" : ""}`} />
              </span>
              <p className="md-stat-card__value">{stats.online}</p>
              <p className="md-stat-card__label">آنلاین</p>
            </article>
            <article className="md-stat-card md-stat-card--today">
              <span className="md-stat-card__icon" aria-hidden="true">
                <TodayStatIcon />
              </span>
              <p className="md-stat-card__value">{stats.today}</p>
              <p className="md-stat-card__label">امروز</p>
            </article>
          </div>
        </section>

        <label className="md-search">
          <SearchIcon />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="جستجوی کاربر یا دستگاه" dir="auto" />
          {query ? (
            <button type="button" className="md-search__clear" aria-label="پاک کردن جستجو" onClick={() => setQuery("")}>
              <CloseIcon />
            </button>
          ) : null}
        </label>

        <div className="md-folders" role="tablist" aria-label="تب‌ها">
          {folders.map((folder, index) => (
            <button
              key={folder.id}
              type="button"
              role="tab"
              aria-selected={index === safeTab}
              className={`md-folder${index === safeTab ? " md-folder--on" : ""}`}
              onClick={() => selectTab(index)}
              onContextMenu={(event) => {
                if (folder.kind !== "custom") return;
                event.preventDefault();
                void askRemoveFolder({ id: folder.id, title: folder.title });
              }}
            >
              {folder.title}
            </button>
          ))}
          <button type="button" className="md-folder md-folder--add" aria-label="تب جدید" onClick={() => setCreating(true)}>
            +
          </button>
        </div>

        {error && <p className="md-banner">{error}</p>}

        <div
          ref={swipeRef}
          className="md-swipe"
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={(event) => {
            releaseCapture(event.currentTarget);
            swipe.current = { x: 0, y: 0, dx: 0, active: false, axis: "", pointerId: -1 };
            blockHold.current = false;
            pendingTab.current = null;
            setSettling(false);
            setDrag(0);
          }}
          onClickCapture={onClickCapture}
        >
          <div
            className={`md-swipe__track${settling ? " md-swipe__track--settle" : ""}`}
            style={{ transform: `translate3d(calc(${safeTab * 100}% + ${drag}px), 0, 0)` }}
            onTransitionEnd={onTrackTransitionEnd}
          >
            {folders.map((folder, index) => {
              const items = paneItems[index] ?? [];
              return (
                <section key={folder.id} className="md-swipe__pane md-sheet" aria-hidden={index !== safeTab}>
                  {items.length === 0 ? (
                    <div className="md-empty">
                      <EmptyIcon />
                      <p>
                        {(overview?.items.length ?? 0) === 0
                          ? "هنوز کسی وصل نشده. با اتصال کلاینت، اینجا دیده می‌شود."
                          : "موردی در این تب پیدا نشد."}
                      </p>
                    </div>
                  ) : (
                    items.map((user) => (
                      <UserRow
                        key={user.userId}
                        user={user}
                        pinned={pinsFor(pins, folder.id).includes(user.userId)}
                        blockHold={blockHold}
                        onOpen={() => {
                          if (Date.now() < suppressUntil.current) return;
                          onOpenUser?.(user.userId);
                        }}
                        onHold={() => setHoldUser(user)}
                      />
                    ))
                  )}
                </section>
              );
            })}
          </div>
        </div>
      </div>

      {pickingPort ? (
        <div className="md-dialog" role="dialog" aria-modal="true" aria-label="انتخاب پورت آمار" onClick={() => setPickingPort(false)}>
          <div
            className="md-dialog__card md-stats-pick"
            onClick={(event) => {
              event.stopPropagation();
            }}
          >
            <button type="button" className="md-dialog__x" aria-label="بستن" onClick={() => setPickingPort(false)}>
              <CloseIcon />
            </button>
            <div className="md-stats-pick__list">
              <button type="button" className={`md-stats-pick__item${!statsPort ? " md-stats-pick__item--on" : ""}`} onClick={() => chooseStatsPort("")}>
                <span className="md-stats-pick__mark" aria-hidden="true">
                  <PortPickIcon />
                </span>
                <span className="md-stats-pick__text">
                  <span className="md-stats-pick__name">{managerOnly ? "پورت‌های من" : "همه پورت‌ها"}</span>
                  <span className="md-stats-pick__hint">{managerOnly ? "فقط پورت‌هایی که مدیرشان هستید" : "آمار کل سیستم"}</span>
                </span>
                {!statsPort ? <span className="md-stats-pick__check" aria-hidden="true"><CheckIcon /></span> : null}
              </button>
              {ports.map((port) => {
                const on = statsPort === port.code;
                const name = (port.name || "").trim() || port.code;
                return (
                  <button key={port.code} type="button" className={`md-stats-pick__item${on ? " md-stats-pick__item--on" : ""}`} onClick={() => chooseStatsPort(port.code)}>
                    <span className="md-stats-pick__mark" aria-hidden="true">
                      <PortPickIcon />
                    </span>
                    <span className="md-stats-pick__text">
                      <span className="md-stats-pick__name" dir="auto">
                        {name}
                      </span>
                      {port.description?.trim() ? (
                        <span className="md-stats-pick__hint" dir="auto">
                          {port.description.trim()}
                        </span>
                      ) : (
                        <span className="md-stats-pick__hint" dir="ltr">
                          {port.code}
                        </span>
                      )}
                    </span>
                    {on ? <span className="md-stats-pick__check" aria-hidden="true"><CheckIcon /></span> : null}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      ) : null}

      {holdUser ? (
        <div className="md-dialog" role="dialog" aria-modal="true" aria-labelledby="folder-title" onClick={() => setHoldUser(null)}>
          <div
            className="md-dialog__card"
            onClick={(event) => {
              event.stopPropagation();
            }}
          >
            <button type="button" className="md-dialog__x" aria-label="بستن" onClick={() => setHoldUser(null)}>
              <CloseIcon />
            </button>
            <p className="md-dialog__text" dir="auto">
              {(holdUser.displayName || "").trim() || holdUser.userId}
            </p>
            <button
              type="button"
              className="md-action md-action--tonal md-action--with-icon"
              onClick={() => {
                togglePin(holdUser.userId, activeFolder.id);
                setHoldUser(null);
              }}
            >
              <PinIcon />
              {activePins.includes(holdUser.userId) ? "برداشتن پین" : "پین کردن"}
            </button>
            <button type="button" className="md-action md-action--danger md-action--with-icon" onClick={() => void askBlockUser(holdUser)}>
              <BlockIcon />
              مسدود کردن
            </button>
            <p id="folder-title" className="md-dialog__title">
              تب کاربر
            </p>
            <div className="md-folder-picks">
              {folders
                .filter((folder) => folder.kind === "custom")
                .map((folder) => {
                  const on = (members[holdUser.userId] || []).includes(folder.id);
                  return (
                    <button key={folder.id} type="button" className={`md-folder-pick${on ? " md-folder-pick--on" : ""}`} onClick={() => toggleMember(holdUser.userId, folder.id)}>
                      {folder.title}
                    </button>
                  );
                })}
            </div>
          </div>
        </div>
      ) : null}

      {deleteFolder ? (
        <div className="md-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-folder-title" onClick={() => setDeleteFolder(null)}>
          <div
            className="md-dialog__card"
            onClick={(event) => {
              event.stopPropagation();
            }}
          >
            <button type="button" className="md-dialog__x" aria-label="بستن" onClick={() => setDeleteFolder(null)}>
              <CloseIcon />
            </button>
            <p id="delete-folder-title" className="md-dialog__title">
              حذف تب
            </p>
            <p className="md-dialog__text" dir="auto">
              تب «{deleteFolder.title}» حذف شود؟
            </p>
            <div className="md-dialog__actions">
              <button type="button" className="md-action md-action--tonal" onClick={() => setDeleteFolder(null)}>
                انصراف
              </button>
              <button type="button" className="md-action md-action--danger" onClick={() => removeFolder(deleteFolder.id)}>
                حذف
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {creating && !holdUser && !deleteFolder ? (
        <div
          className="md-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="new-folder-title"
          onClick={() => {
            setCreating(false);
            setFolderName("");
          }}
        >
          <div
            className="md-dialog__card"
            onClick={(event) => {
              event.stopPropagation();
            }}
          >
            <button
              type="button"
              className="md-dialog__x"
              aria-label="بستن"
              onClick={() => {
                setCreating(false);
                setFolderName("");
              }}
            >
              <CloseIcon />
            </button>
            <p id="new-folder-title" className="md-dialog__title">
              تب جدید
            </p>
            <label className="md-field">
              <span>اسم تب</span>
              <input value={folderName} onChange={(e) => setFolderName(e.target.value)} placeholder="مثلاً شیفت شب" maxLength={24} dir="auto" />
            </label>
            <button type="button" className="md-action" disabled={!folderName.trim()} onClick={() => addFolder(folderName)}>
              ساخت
            </button>
          </div>
        </div>
      ) : null}
    </PullToRefresh>
  );
}

function UserRow({
  user,
  pinned,
  blockHold,
  onOpen,
  onHold,
}: {
  user: Snapshot;
  pinned: boolean;
  blockHold: { current: boolean };
  onOpen: () => void;
  onHold: () => void;
}) {
  const onlineDevices = user.devices.filter((d) => d.online).length;
  const title = (user.displayName || "").trim() || user.userId;
  const mark = title.slice(0, 1).toUpperCase() || "U";
  const timer = useRef<number | null>(null);
  const held = useRef(false);
  const moved = useRef(false);

  function clearHold() {
    if (timer.current != null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  }

  return (
    <article className="md-row">
      <button
        type="button"
        className="md-user"
        onPointerDown={() => {
          held.current = false;
          moved.current = false;
          clearHold();
          timer.current = window.setTimeout(() => {
            if (blockHold.current || moved.current) return;
            held.current = true;
            onHold();
          }, 480);
        }}
        onPointerMove={() => {
          if (blockHold.current) {
            moved.current = true;
            clearHold();
          }
        }}
        onPointerUp={clearHold}
        onPointerLeave={clearHold}
        onPointerCancel={clearHold}
        onContextMenu={(event) => {
          event.preventDefault();
          held.current = true;
          onHold();
        }}
        onClick={() => {
          if (held.current) {
            held.current = false;
            return;
          }
          onOpen();
        }}
      >
        <span className="md-avatar-wrap">
          <span className={`md-avatar ${user.online ? "md-avatar--on" : ""}`}>{/\d/.test(mark) ? <PersonIcon /> : mark}</span>
          <span className={`md-dot ${user.online ? "md-dot--on" : ""}`} />
        </span>
        <div className="md-user__body">
          <p className="md-user__id" dir="auto">
            {title}
          </p>
          <p className="md-user__meta">
            {user.online ? `${onlineDevices} از ${user.devices.length} دستگاه متصل` : `آخرین بازدید ${formatRelative(user.lastSeen)}`}
          </p>
        </div>
        {pinned ? (
          <span className="md-pin" aria-label="پین شده">
            <PinIcon />
          </span>
        ) : null}
        <span className="md-chevron" aria-hidden="true">
          <ChevronIcon />
        </span>
      </button>
    </article>
  );
}

function PortPickIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4" y="4.5" width="7" height="7" rx="1.8" stroke="currentColor" strokeWidth="1.7" />
      <rect x="13" y="12.5" width="7" height="7" rx="1.8" stroke="currentColor" strokeWidth="1.7" />
      <path d="M11 8h3.2A2.8 2.8 0 0 1 17 10.8V12.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
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

function CheckIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M5.5 12.5 10 17l8.5-9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function UsersStatIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="9" cy="8" r="3" stroke="currentColor" strokeWidth="1.7" />
      <path d="M3.5 18c.8-2.6 2.8-4 5.5-4s4.7 1.4 5.5 4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <circle cx="17" cy="9" r="2.4" stroke="currentColor" strokeWidth="1.7" />
      <path d="M14.2 18c.4-1.5 1.5-2.5 3-2.5 1.4 0 2.4.8 3 2.2" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function TodayStatIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4" y="5" width="16" height="15" rx="3" stroke="currentColor" strokeWidth="1.7" />
      <path d="M8 3.5V7M16 3.5V7M4 10h16" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M12 13v3.5M12 16.5h2.2" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function SearchIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="11" cy="11" r="6.5" stroke="currentColor" strokeWidth="1.8" />
      <path d="M16 16.5 20 20.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function CloseIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M7 7l10 10M17 7 7 17" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function EmptyIcon() {
  return (
    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="8" r="3.2" stroke="currentColor" strokeWidth="1.6" />
      <path d="M5.5 18.2c.8-3 3.3-4.7 6.5-4.7s5.7 1.7 6.5 4.7" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

function PersonIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="8.2" r="3" stroke="currentColor" strokeWidth="1.7" />
      <path d="M6 18c.9-2.8 3-4.3 6-4.3s5.1 1.5 6 4.3" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function PinIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M9 4.5h6l-1 6 2.2 2.2v1.6H12.8V20L12 19.2 11.2 20v-5.7H7.8v-1.6L10 10.5l-1-6Z" fill="currentColor" />
    </svg>
  );
}

function BlockIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="7.25" stroke="currentColor" strokeWidth="1.8" />
      <path d="M7 7l10 10" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function ChevronIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M9 6.5 14.5 12 9 17.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
