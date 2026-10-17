import { NextResponse } from "next/server";

import { isAdminTelegramUser } from "@/server/auth/admin";
import { initDataFromRequest, parseInitData } from "@/server/auth/initData";
import {
  firebaseReady,
  getPortEndpoint,
  isValidWsUrl,
  setPortEndpoint,
} from "@/server/firebase/admin";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function allow(request: Request): boolean {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== "production";
  return localDev || (!!user && isAdminTelegramUser(user.id));
}

type Context = { params: Promise<{ code: string }> };

function validPort(code: string): boolean {
  return /^[A-Za-z0-9]{20}$/.test(code);
}

export async function GET(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  if (!firebaseReady()) {
    return NextResponse.json({ error: "Firebase not configured" }, { status: 503 });
  }
  const { code } = await context.params;
  if (!validPort(code)) {
    return NextResponse.json({ error: "Invalid port" }, { status: 400 });
  }
  try {
    const doc = await getPortEndpoint(code);
    return NextResponse.json(doc ?? { wsUrl: "", rev: 0, updatedAt: "" });
  } catch {
    return NextResponse.json({ error: "Firebase unavailable" }, { status: 502 });
  }
}

export async function PUT(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  if (!firebaseReady()) {
    return NextResponse.json({ error: "Firebase not configured" }, { status: 503 });
  }
  const { code } = await context.params;
  if (!validPort(code)) {
    return NextResponse.json({ error: "Invalid port" }, { status: 400 });
  }
  try {
    const body = (await request.json()) as { wsUrl?: string };
    const wsUrl = (body.wsUrl || "").trim();
    if (!isValidWsUrl(wsUrl) || wsUrl.length > 512) {
      return NextResponse.json({ error: "Invalid wsUrl" }, { status: 400 });
    }
    const doc = await setPortEndpoint(code, wsUrl);
    return NextResponse.json(doc);
  } catch {
    return NextResponse.json({ error: "Firebase unavailable" }, { status: 502 });
  }
}
