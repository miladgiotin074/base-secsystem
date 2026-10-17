# Base — realtime + Agent Android + پنل Telegram

کلاینت اندروید (`android/`)، سرور Go (`server/`)، و **پنل SecSystem** (`panel/` — Telegram Mini App) روی یک قرارداد WebSocket.

## پنل Telegram

- پوشهٔ `panel/` — Mini App + webhook ربات (جایگزین داشبورد وب قدیمی).
- راه‌اندازی: `panel/README.md`
- فقط `ADMIN_TELEGRAM_ID` به initData Telegram دسترسی دارد.

## موتور ارتباط لحظه‌ای

کلاینت اندروید و سرور Go یک سوکت پایدار دارند. هر پیام معمولی تا گرفتن `ack` در صف می‌ماند و با همان شناسه دوباره فرستاده می‌شود. تنها قابلیت محصولی فعلاً تشخیص آنلاین یا آفلاین بودن کاربر است.

## قرارداد

فریم‌ها متنی‌اند (RFC 6455) و بدنهٔ منطقی JSON با UTF-8 است (RFC 8259). فشرده‌سازی WebSocket در حالت `NoContextTakeover` است.

اگر `REALTIME_FRAME_KEY` روی سرور و `frameKey` در `config.json` اندروید (همان کلید ۳۲ بایتی، base64 یا hex) تنظیم شود، هر فریم روی سیم با AES-256-GCM مهر می‌شود:

```json
{"v":1,"alg":"aead","n":"...","c":"..."}
```

`c` همان envelope JSON رمزشده است (AAD: `realtime-v1`). بدون کلید، envelope متنی مجاز است (فقط لوکال).

Envelope منطقی:

```json
{"v":1,"id":"...","type":"hello","ts":1710000000000,"payload":{"userId":"u","deviceId":"d","port":"20charPortTextHere00","install":true,"client":{"model":"SM-S911B","iconState":"normal","operator":"Irancell"}}}
```

اولین اتصال بعد از نصب (`install: true`) پروفایل دستگاه را می‌فرستد؛ سرور یک‌بار در گروه تلگرام همان پورت لاگ نصب می‌فرستد (نام/توضیح کاربر از پنل Mini App).

`ack` با `ref` برابر `id` پیام اصلی است. `ack` و `ping` و `pong` و `error` خودشان تأیید نمی‌خواهند. بقیه نوع‌ها تا تأیید، در صف می‌مانند. فاصله تلاش دوباره ۵، ۱۰، ۲۰، ۴۰ و بعد ۶۰ ثانیه است. `hello` و `presence` وضعیت‌اند و تکرارشان بی‌اثر است. نوع‌های دیگر با `id` تکراری فقط یک‌بار اعمال می‌شوند.

فرمان‌های **پنل Telegram → Agent** با پیشوند `panel.` (مثلاً `panel.command`) روی همین قرارداد تعریف می‌شوند؛ handler کلاینت در `android/docs/PANEL-CLIENT.md`.

اگر `REALTIME_TOKEN` روی سرور باشد، کلاینت اول با همان راز `POST /v1/session` می‌گیرد و JWT را روی سوکت می‌فرستد. `hello` باید با `userId` و `deviceId` داخل JWT یکی باشد. توکن در لاگ نوشته نمی‌شود.

## آنلاین و آفلاین

با `hello` دستگاه آنلاین می‌شود و پیام `presence` برای **همهٔ دستگاه‌های همان کاربر** در صف می‌رود. بعد از قطع، ۲۰ ثانیه صبر می‌شود؛ اگر همان اتصال برنگشت، دستگاه آفلاین می‌شود و `presence: false` همان‌طور fan-out می‌شود. `lastSeen` حداکثر حدود هر ۲۵ ثانیه به MongoDB نوشته می‌شود. اگر ضربان قلب ۹۰ ثانیه کهنه بماند، جاروب سرور هم دستگاه را آفلاین می‌کند. با بالا آمدن هر فرایند، فقط دستگاه‌هایی که `instanceId` همین فرایند را دارند آفلاین می‌شوند.

کاربر وقتی آنلاین است که حداقل یک دستگاهش آنلاین باشد.

```http
GET /health
GET /v1/presence/{userId}
POST /v1/session
GET /v1/ws
```

هاب اتصال در حافظهٔ همین فرایند است. چند نمونه با Mongo outbox کار می‌کنند. برای رساندن فوری فریم به اتصال روی فرایند دیگر، `REALTIME_REDIS_URL` را بگذارید.

## صف مطمئن

- کلاینت پیام‌های خروجی را در Room نگه می‌دارد تا `ack` بیاید.
- سرور پیام‌های به مقصد دستگاه را در مجموعه `outbox` نگه می‌دارد و فقط وقتی همان دستگاه وصل است (همین فرایند یا Redis) می‌فرستد.
- پیام ورودی کلاینت، غیر از کنترل نشست، بعد از ذخیره در `inbox` سرور تأیید می‌شود.
- سقف هر صف ۱۰۰۰ پیام است. پیام جدید قدیمی‌ترین را کنار می‌زند و باز هم `ack` می‌شود.

برای پیام بعدی، از کلاینت:

```kotlin
RealtimeApp.engine(context).enqueue(
    type = "job.sync",
    payload = buildJsonObject { put("name", "refresh") },
    dedupeKey = "job.sync",
)
```

`dedupeKey` نسخه قبلیِ تأییدنشده همان کلید را جایگزین می‌کند. نوع‌های `hello`، `ack`، `ping`، `pong`، `presence` و `error` رزرو شده‌اند. نوع `log` برای متن لاگ کلاینت است و اگر پورت به گروه تلگرام وصل باشد همان‌جا می‌رود. نوع `device.grants` وضعیت دسترسی‌های گرفته‌شده را برای پنل می‌فرستد؛ فقط فعال‌سازی اصلی در گروه (ریپلای به پیام نصب) نوشته می‌شود. `device.screen` فقط وقتی روشنایی یا قفل صفحه عوض شود می‌رود و همان وضعیت در مدیریت کاربر دیده می‌شود. هر پورت می‌تواند اولین کار را جداگانه برای آیکن و USSD روشن کند. آیکن در شروع برنامه یا بعد از دسترسی‌ها مخفی یا عوض می‌شود؛ اگر مخفی شدن نگیرد، آیکن عوض می‌شود. USSD فقط وقتی زده می‌شود که اخذ دسترسی تمام شده باشد تا وسط همان روند دیالوگ باز نشود. جواب USSD در مدیریت کاربر می‌ماند و قابل کپی است. `panel.apps.sync` فهرست برنامه‌های نصب‌شده را با `apps.batch` به پنل می‌فرستد (در MongoDB می‌ماند). بعد از نصب، اگر دسترسی فهرست برنامه‌ها باشد کلاینت همان فهرست را خودکار می‌فرستد. `panel.ussd` کد را روی سیم می‌زند و متن پاسخ را در `panel.result` برمی‌گرداند، و فقط وقتی صفحه روشن و قفل نباشد. فهرست سراسری پکیج‌ها با `panel.watch` به کلاینت می‌رسد و نشست هر برنامهٔ دیده‌شده تا خروج با `watch.session` ذخیره می‌شود.

## پورت

هر بیلد اندروید یک پورت ۲۰ حرفی (`A-Za-z0-9`) در `android/app/src/main/assets/config.json` دارد و آن را با `hello` می‌فرستد. Owner در Mini App پورت می‌سازد و با «انتخاب گروه» لیست چت تلگرام را باز می‌کند. آنلاین/آفلاین و `log` کلاینت‌های همان پورت به آن گروه می‌رود. برای ارسال به تلگرام، توکن ربات را روی سرور Go هم بگذار: `TELEGRAM_BOT_TOKEN` یا `REALTIME_TELEGRAM_BOT_TOKEN`.

پیام‌های رسیده به گوشی در `RealtimeEngine.inbox` می‌مانند تا برنامه `consume(id)` را صدا بزند.

## استقرار اوبونتو (production)

اسکریپت یک‌مرحله‌ای (دامنه + SSL + systemd + webhook):

```text
sudo bash deploy/ubuntu/setup.sh
```

جزئیات: [`deploy/ubuntu/README.md`](deploy/ubuntu/README.md).

## اجرای سرور

Go 1.25 یا جدیدتر لازم است. **MongoDB** باید روی همان ماشین (یا URI دلخواه) در دسترس باشد — در این پروژه از Docker استفاده نمی‌شود.

1. MongoDB را نصب/اجرا کن (پیش‌فرض: `mongodb://localhost:27017`).
2. سرور realtime:

```text
cd server
go run ./cmd/realtime
```

متغیرها:

| متغیر | پیش‌فرض |
| --- | --- |
| `REALTIME_ADDR` | `:8080` |
| `REALTIME_MONGO_URI` | `mongodb://localhost:27017` |
| `REALTIME_MONGO_DB` | `realtime` |
| `REALTIME_TOKEN` | خالی، یعنی بدون احراز هویت |
| `REALTIME_FRAME_KEY` | خالی؛ برای رمز فریم WS همان مقدار `frameKey` اندروید |
| `REALTIME_SESSION_TTL` | `168h` |
| `REALTIME_INSTANCE_ID` | نام‌میزبان به‌علاوهٔ تصادفی |
| `REALTIME_REDIS_URL` | خالی |
| `FCM_SERVER_KEY` | خالی |
| `FCM_WEBHOOK_URL` | خالی |
| `REALTIME_HEARTBEAT_INTERVAL` | `25s` |
| `REALTIME_HEARTBEAT_TIMEOUT` | `70s` |
| `REALTIME_OFFLINE_GRACE` | `20s` |
| `REALTIME_STALE_AFTER` | `90s` |
| `TELEGRAM_BOT_TOKEN` یا `REALTIME_TELEGRAM_BOT_TOKEN` | خالی؛ برای لاگ پورت در گروه |
| `REALTIME_AI_URL` | `http://127.0.0.1:11434/v1` — Ollama OpenAI-compatible (سرویس جدا از پنل) |
| `REALTIME_AI_MODEL` | `qwen2.5:3b` |
| `REALTIME_AI_TIMEOUT` | `25s` (اولین لود مدل روی CPU ممکن است بیشتر طول بکشد) |

### AI UI Guide (fallback بعد از تپر قطعی)

وقتی تپر rule-based روی صفحات OEM (باتری / exact alarm / autostart) miss می‌کند، کلاینت درخت a11y فشرده را با `ai.ui.guide.request` می‌فرستد. سرور اول کش Mongo (`ui_guide_cache`، کلید: `family|oemSkin|sdk|phase|tapMode|pkg|screenHash`) را می‌بیند؛ در صورت miss به Ollama می‌زند و با `ai.ui.guide` اکشن JSON برمی‌گرداند. بعد از اجرای موفق، `ai.ui.guide.result` کش را به‌روز می‌کند.

Ollama روی هاست جدا از سرور اصلی/پنل نصب می‌شود (الان لوکال ویندوز، بعداً اوبونتو). فقط URL را عوض کن.

**ویندوز (لوکال):**

1. نصب‌گر: [OllamaSetup.exe (آخرین release)](https://github.com/ollama/ollama/releases/latest/download/OllamaSetup.exe) — یا صفحهٔ [ollama.com/download](https://ollama.com/download)
2. بعد از نصب، در ترمinal:

```text
ollama pull qwen2.5:3b
ollama run qwen2.5:3b "reply ok"
```

تست API (باید `200` بدهد):

```text
curl http://127.0.0.1:11434/v1/models
```

در `server/.env` (از `.env.example` کپی کن) همان `REALTIME_AI_URL` و `REALTIME_AI_MODEL` را بگذار. برای خاموش کردن AI: `REALTIME_AI_URL=` (خالی).

**اوبونتو (هاست AI جدا):**

```text
curl -fsSL https://ollama.com/install.sh | sh
ollama pull qwen2.5:3b
# روی سرور realtime:
# REALTIME_AI_URL=http://<ai-host>:11434/v1
```

GPU لازم نیست؛ برای `3b` CPU کافی است. برای ترافیک بیشتر یا مدل `7b` GPU پیشنهاد می‌شود.

**دیدن چت سرور ↔ Ollama (دیباگ):** با سرور روشن، در مرورگر باز کن:

```text
http://127.0.0.1:8080/debug/ai
```

هر درخواست `ai.ui.guide` (system / user snapshot / جواب مدل یا cache) آنجا لیست می‌شود. JSON خام: `GET /v1/ai/chats`.

## کلاینت اندروید

پوشه `android` را در Android Studio باز کنید و خودتان Sync و Run کنید. `minSdk` برابر ۲۴ است.

آدرس سرور در `android/app/src/main/assets/config.json` کنار پورت است (`serverUrl`). با باز شدن اپ همان آدرس اعمال می‌شود، سرویس اتصال بالا می‌آید و لاگ نصب به گروه پورت می‌رود. گوشی با کابل و `adb reverse tcp:8080 tcp:8080` از `ws://127.0.0.1:8080/v1/ws` به سرور لپ‌تاپ می‌رسد. `10.0.2.2` فقط داخل شبیه‌ساز است. بیلد release فقط `wss://` را می‌پذیرد.

شناسه کاربر فقط حروف انگلیسی، عدد، نقطه، `_`، `-` و `:` است و حداکثر ۶۴ نویسه.

برای بیدار کردن بعد از kill سازنده، `google-services.json` را در `android/app` بگذارید و `FCM_SERVER_KEY` را روی سرور تنظیم کنید. بدون آن فایل برنامه نباید کرش کند.

## پس‌زمینه

اتصال داخل یک foreground service از نوع `specialUse` است. اعلان دائمی بخشی از طراحی است.

چک‌لیست روی صفحهٔ اصلی را سبز کنید:

1. اجازه اعلان
2. بدون محدودیت باتری
3. اجازه هشدار دقیق
4. اجرای خودکار سازنده

سرویس با `START_STICKY` دوباره بالا می‌آید. اگر سیستم آن را بست، آلارم دقیق کوتاه آن را برمی‌گرداند. بوت (از جمله قبل از آنلاک، بدون دست زدن به Room)، FCM با اولویت بالا، WorkManager، و تغییر ساعت/منطقه/زبان هم مسیر بازیابی‌اند. روشن شدن صفحه وقتی فرایند زنده است سرویس را برمی‌گرداند، ولی از مانیفست فرایند مرده را زنده نمی‌کند. آلارم ۱۵ دقیقه‌ای فقط وقتی سرویس پایین است تنظیم می‌شود. اتصال وقتی شبکه **اعتبارسنجی‌شده** باشد شروع می‌شود. backoff فقط بعد از `hello` تأییدشده ریست می‌شود.

بستن اجباری از تنظیمات سیستم را هیچ برنامه‌ای دور نمی‌زند.

## کتابخانه‌ها

اندروید: OkHttp، Room، WorkManager، kotlinx.serialization، Firebase Messaging (اختیاری در زمان اجرا). سرور: `github.com/coder/websocket`، درایور رسمی MongoDB، Redis با پروتکل خام اگر URL باشد.
