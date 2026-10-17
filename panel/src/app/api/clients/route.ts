import { NextResponse } from "next/server";

import { isAdminTelegramUser } from "@/server/auth/admin";
import { initDataFromRequest, parseInitData } from "@/server/auth/initData";
import { realtimeBase, realtimeHeaders } from "@/server/realtime/client";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function allow(request: Request): boolean {
  const user = parseInitData(initDataFromRequest(request));
  const localDev = process.env.NODE_ENV !== "production";
  return localDev || (!!user && isAdminTelegramUser(user.id));
}

export async function GET(request: Request) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  try {
    const res = await fetch(`${realtimeBase()}/v1/clients`, {
      headers: realtimeHeaders(),
      cache: "no-store",
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json({ error: "Clients unavailable" }, { status: 502 });
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}

export async function POST(request: Request) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  try {
    const res = await fetch(`${realtimeBase()}/v1/clients`, {
      method: "POST",
      headers: { ...realtimeHeaders(), "Content-Type": "application/json" },
      body: await request.text(),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json({ error: "Create failed" }, { status: res.status === 400 ? 400 : 502 });
    }
    return NextResponse.json(data, { status: 201 });
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}
