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

type Context = { params: Promise<{ id: string; file: string[] }> };

export async function GET(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  const { id, file } = await context.params;
  const name = (file || []).map((part) => decodeURIComponent(part)).join("/") || "index.html";
  const path = name
    .split("/")
    .map((part) => encodeURIComponent(part))
    .join("/");
  try {
    const res = await fetch(`${realtimeBase()}/v1/clients/${id}/files/${path}`, {
      headers: realtimeHeaders(),
      cache: "no-store",
    });
    if (!res.ok) {
      return NextResponse.json({ error: "Unavailable" }, { status: res.status === 404 ? 404 : 502 });
    }
    const contentType = res.headers.get("content-type") || "application/octet-stream";
    if (name === "index.html" || name === "index.htm") {
      let html = await res.text();
      if (!/<base\s/i.test(html)) {
        html = html.replace(/<head([^>]*)>/i, `<head$1><base href="/api/clients/${id}/files/">`);
      }
      return new NextResponse(html, {
        headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "private, no-store" },
      });
    }
    return new NextResponse(res.body, {
      headers: { "Content-Type": contentType, "Cache-Control": "private, no-store" },
    });
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}
