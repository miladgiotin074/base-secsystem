import { readInitDataRaw } from "@/lib/telegram/initDataClient";

export async function setUserBlocked(userId: string, blocked: boolean): Promise<boolean> {
  const initData = readInitDataRaw();
  const res = await fetch(`/api/users/${encodeURIComponent(userId)}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      ...(initData ? { "x-telegram-init-data": initData } : {}),
    },
    body: JSON.stringify({ blocked }),
  });
  return res.ok;
}
