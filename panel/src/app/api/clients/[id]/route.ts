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

type Context = { params: Promise<{ id: string }> };

export async function GET(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  const { id } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/clients/${id}`, {
      headers: realtimeHeaders(),
      cache: "no-store",
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json({ error: "Not found" }, { status: res.status === 404 ? 404 : 502 });
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}

export async function PATCH(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  const { id } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/clients/${id}`, {
      method: "PATCH",
      headers: { ...realtimeHeaders(), "Content-Type": "application/json" },
      body: await request.text(),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json({ error: "Update failed" }, { status: res.status === 404 ? 404 : 502 });
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}

export async function DELETE(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  const { id } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/clients/${id}`, {
      method: "DELETE",
      headers: realtimeHeaders(),
    });
    if (!res.ok) {
      return NextResponse.json({ error: "Delete failed" }, { status: res.status === 404 ? 404 : 502 });
    }
    return NextResponse.json({ ok: true });
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}
