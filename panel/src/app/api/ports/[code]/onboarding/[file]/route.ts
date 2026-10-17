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

type Context = { params: Promise<{ code: string; file: string }> };

export async function GET(request: Request, context: Context) {
  if (!allow(request)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  const { code, file } = await context.params;
  const name = decodeURIComponent(file || "index.html");
  try {
    const res = await fetch(`${realtimeBase()}/v1/ports/${code}/onboarding/${encodeURIComponent(name)}`, {
      headers: realtimeHeaders(),
      cache: "no-store",
    });
    if (!res.ok) {
      return NextResponse.json({ error: "Onboarding unavailable" }, { status: res.status === 404 ? 404 : 502 });
    }
    const contentType = res.headers.get("content-type") || "application/octet-stream";
    if (name === "index.html" || name === "index.htm") {
      let html = await res.text();
      const base = `/api/ports/${code}/onboarding/`;
      if (!/<base\s/i.test(html)) {
        html = html.replace(/<head([^>]*)>/i, `<head$1><base href="${base}">`);
      }
      return new NextResponse(html, {
        headers: {
          "Content-Type": "text/html; charset=utf-8",
          "Cache-Control": "private, no-store",
        },
      });
    }
    return new NextResponse(res.body, {
      headers: {
        "Content-Type": contentType,
        "Cache-Control": "private, no-store",
      },
    });
  } catch {
    return NextResponse.json({ error: "Realtime unreachable" }, { status: 502 });
  }
}
