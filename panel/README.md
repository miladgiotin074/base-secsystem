# SecSystem — پنل Telegram Mini App

پنل مدیریت اپ Agent (حالت واقعی) روی **Telegram Bot + Mini App**. اسکلت از `telegram-mini-app` گرفته شده و به سرور Go realtime در `../server` وصل می‌شود.

## اجرا

```bash
cd panel
npm install
cp .env.example .env.local
npm run dev
```

**ربات (لوکال):** بدون webhook — در ترمینال جدا:

```bash
npm run bot:poll
```

`/start` را در چت خصوصی با ربات بزن. اسکript webhook را حذف می‌کند و `getUpdates` می‌گیرد.

**Mini App:** به **HTTPS عمومی** نیاز است (`ngrok http 3000` + `npm run dev`). URL را در `TELEGRAM_MINI_APP_URL` و BotFather بگذار.

**سرور (بعداً):** `npm run bot:webhook` — همان `.env` با URL دامنهٔ production.

**پورت:** در Mini App تب «پورت‌ها» کد بساز، تنظیمات همان پورت را باز کن و «انتخاب گروه» را بزن تا لیست چت تلگرام باز شود.

**گوشی با USB (Developer mode):** MongoDB لوکال + `go run ./cmd/realtime` در `server/` (پورت 8080). اگر Agent به `http://127.0.0.1:8080` وصل می‌شود: `adb reverse tcp:8080 tcp:8080` (با کابل و USB debugging). روی فرایند Go همان `TELEGRAM_BOT_TOKEN` را بگذار تا لاگ پورت به گروه برود.

## مسیرها

| مسیر | کار |
| --- | --- |
| `/` | داشبورد Mini App (فقط ادمین‌های `ADMIN_TELEGRAM_ID`) |
| `POST /api/bot` | Webhook ربات؛ `/start` دکمهٔ باز کردن Mini App |
| `GET /api/me` | اعتبارسنجی initData + پیکربندی realtime |
| `GET /api/overview` | پروکسی به `REALTIME_API_URL/v1/overview` |

## بعداً

- فرمان‌های `panel.*` روی WebSocket برای دستگاه‌های Agent
- نمایش لاگ/مجوزها از payloadهای realtime
