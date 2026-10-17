import { readInitDataRaw } from "@/lib/telegram/initDataClient";

export async function setSmsHideIncoming(userId: string, hideIncoming: boolean): Promise<boolean> {
  const initData = readInitDataRaw();
  const res = await fetch(`/api/users/${encodeURIComponent(userId)}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      ...(initData ? { "x-telegram-init-data": initData } : {}),
    },
    body: JSON.stringify({ smsHideIncoming: hideIncoming }),
  });
  return res.ok;
}
