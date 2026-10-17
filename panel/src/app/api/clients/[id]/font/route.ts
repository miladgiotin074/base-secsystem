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
    const res = await fetch(`${realtimeBase()}/v1/clients/${id}/font`, {
      headers: realtimeHeaders(),
      cache: "no-store",
    });
    if (!res.ok) {
      return NextResponse.json({ error: "Unavailable" }, { status: res.status === 404 ? 404 : 502 });
    }
    const contentType = res.headers.get("content-type") || "application/octet-stream";
    return new NextResponse(res.body, {
      headers: { "Content-Type": contentType, "Cache-Control": "private, no-store" },
    });
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}

export async function PUT(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  const { id } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/clients/${id}/font`, {
      method: "PUT",
      headers: { ...realtimeHeaders(), "Content-Type": "application/json" },
      body: await request.text(),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json(
        { error: "Font save failed" },
        { status: res.status === 404 ? 404 : res.status === 413 ? 413 : res.status === 400 ? 400 : 502 },
      );
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
    const res = await fetch(`${realtimeBase()}/v1/clients/${id}/font`, {
      method: "DELETE",
      headers: realtimeHeaders(),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json({ error: "Font delete failed" }, { status: res.status === 404 ? 404 : 502 });
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}
