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

type Context = { params: Promise<{ code: string }> };

export async function PUT(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  const { code } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/${code}/onboarding`, {
      method: "PUT",
      headers: { ...realtimeHeaders(), "Content-Type": "application/json" },
      body: await request.text(),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json(
        { error: typeof data.error === "string" ? data.error : "Onboarding save failed" },
        { status: res.status === 404 ? 404 : res.status === 413 ? 413 : 502 },
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
  const { code } = await context.params;
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/${code}/onboarding`, {
      method: "DELETE",
      headers: realtimeHeaders(),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return NextResponse.json({ error: "Onboarding delete failed" }, { status: res.status === 404 ? 404 : 502 });
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}
