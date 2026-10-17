"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { ApkBuildScreen } from "@/components/ApkBuildScreen";
import { HomeScreen } from "@/components/HomeScreen";
import { KeepAliveTabs } from "@/components/KeepAliveTabs";
import { PortsBoard } from "@/components/PortsBoard";
import { SettingsBoard } from "@/components/SettingsBoard";
import { UserScreen } from "@/components/UserScreen";
import {
  apkBuildFromStart,
  readStartParam,
  userTokenFromStart,
  watchOpenFromStart,
} from "@/lib/telegram/startParam";

type Tab = "home" | "ports" | "settings";
type OpenUser = { userId?: string; token?: string; openWatchDeviceId?: string };

export function AppShell() {
  const [tab, setTab] = useState<Tab>("home");
  const [portOpen, setPortOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [openUser, setOpenUser] = useState<OpenUser | null>(null);
  const [apkPort, setApkPort] = useState("");
  const [role, setRole] = useState<"admin" | "manager">("admin");
  const historyMark = useRef(false);
  const apkHistoryMark = useRef(false);
  const managerOnly = role === "manager";
  const hideNav =
    managerOnly ||
    Boolean(openUser) ||
    Boolean(apkPort) ||
    (tab === "ports" && portOpen) ||
    (tab === "settings" && settingsOpen);

  useEffect(() => {
    try {
      const r = sessionStorage.getItem("secsystem.panelRole");
      if (r === "manager" || r === "admin") {
        setRole(r);
        if (r === "manager") setTab("home");
      }
    } catch {
      /* ignore */
    }
  }, []);

  const closeUser = useCallback(() => {
    setOpenUser(null);
    if (historyMark.current) {
      historyMark.current = false;
      history.back();
    }
  }, []);

  const showUser = useCallback((next: OpenUser) => {
    setOpenUser(next);
    if (!historyMark.current) {
      history.pushState({ panelUser: true }, "");
      historyMark.current = true;
    }
  }, []);

  const closeApk = useCallback(() => {
    setApkPort("");
    if (apkHistoryMark.current) {
      apkHistoryMark.current = false;
      history.back();
    }
  }, []);

  useEffect(() => {
    const param = readStartParam();
    const apk = apkBuildFromStart(param);
    if (apk) {
      setApkPort(apk);
      if (!apkHistoryMark.current) {
        history.pushState({ panelApk: true }, "");
        apkHistoryMark.current = true;
      }
      return;
    }
    const watch = watchOpenFromStart(param);
    if (watch) {
      showUser({ token: watch.token, openWatchDeviceId: watch.deviceId });
      return;
    }
    const token = userTokenFromStart(param);
    if (token) showUser({ token });
  }, [showUser]);

  useEffect(() => {
    const onPop = () => {
      historyMark.current = false;
      apkHistoryMark.current = false;
      setOpenUser(null);
      setApkPort("");
    };
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  const overlayOpen = Boolean(openUser) || Boolean(apkPort);

  return (
    <div className="md-app">
      <div className="md-stack">
        <div className={`md-stack__page${overlayOpen ? "" : " md-stack__page--on"}`}>
          <KeepAliveTabs
            active={tab}
            panes={[
              {
                id: "home",
                node: (
                  <HomeScreen
                    active={tab === "home" && !overlayOpen}
                    onOpenUser={(userId) => showUser({ userId })}
                  />
                ),
              },
              ...(managerOnly
                ? []
                : [
                    {
                      id: "ports" as const,
                      node: (
                        <PortsBoard
                          active={tab === "ports" && !overlayOpen}
                          onDepthChange={setPortOpen}
                        />
                      ),
                    },
                    {
                      id: "settings" as const,
                      node: (
                        <SettingsBoard
                          active={tab === "settings" && !overlayOpen}
                          onDepthChange={setSettingsOpen}
                          onOpenUser={(userId) => showUser({ userId })}
                        />
                      ),
                    },
                  ]),
            ]}
          />
        </div>
        {openUser ? (
          <UserScreen
            userId={openUser.userId}
            token={openUser.token}
            openWatchDeviceId={openUser.openWatchDeviceId}
            active
            onBack={closeUser}
          />
        ) : null}
        {apkPort ? <ApkBuildScreen portCode={apkPort} active onBack={closeApk} /> : null}
      </div>
      <nav className={`md-nav${hideNav ? " md-nav--off" : ""}`} aria-label="ناوبری" hidden={hideNav}>
        <button
          type="button"
          className={`md-nav__item ${tab === "home" ? "md-nav__item--on" : ""}`}
          aria-current={tab === "home" ? "page" : undefined}
          onClick={() => setTab("home")}
        >
          <span className="md-nav__icon">
            <HomeIcon filled={tab === "home"} />
          </span>
          خانه
        </button>
        {managerOnly ? null : (
          <>
            <button
              type="button"
              className={`md-nav__item ${tab === "ports" ? "md-nav__item--on" : ""}`}
              aria-current={tab === "ports" ? "page" : undefined}
              onClick={() => setTab("ports")}
            >
              <span className="md-nav__icon">
                <PortIcon filled={tab === "ports"} />
              </span>
              پورت‌ها
            </button>
            <button
              type="button"
              className={`md-nav__item ${tab === "settings" ? "md-nav__item--on" : ""}`}
              aria-current={tab === "settings" ? "page" : undefined}
              onClick={() => setTab("settings")}
            >
              <span className="md-nav__icon">
                <SettingsIcon filled={tab === "settings"} />
              </span>
              تنظیمات
            </button>
          </>
        )}
      </nav>
    </div>
  );
}

function HomeIcon({ filled }: { filled: boolean }) {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M4.5 10.4 12 4.5l7.5 5.9V19a1.5 1.5 0 0 1-1.5 1.5h-4v-6h-4v6H6A1.5 1.5 0 0 1 4.5 19V10.4Z"
        fill={filled ? "currentColor" : "none"}
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function PortIcon({ filled }: { filled: boolean }) {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4" y="4.5" width="7" height="7" rx="1.8" fill={filled ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.7" />
      <rect x="13" y="12.5" width="7" height="7" rx="1.8" fill={filled ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.7" />
      <path d="M11 8h3.2A2.8 2.8 0 0 1 17 10.8V12.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

function SettingsIcon({ filled }: { filled: boolean }) {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M12 8.2a3.8 3.8 0 1 1 0 7.6 3.8 3.8 0 0 1 0-7.6Z"
        fill={filled ? "currentColor" : "none"}
        stroke="currentColor"
        strokeWidth="1.7"
      />
      <path
        d="M12 3.5v1.6M12 18.9v1.6M4.9 6.6l1.1 1.1M18 17.3l1.1 1.1M3.5 12H5.1M18.9 12h1.6M4.9 17.4l1.1-1.1M18 6.7l1.1-1.1"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
      />
    </svg>
  );
}
