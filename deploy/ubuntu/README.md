# Deploy on Ubuntu (آسان‌ترین روش)

نیاز: VPS اوبونتو ۲۲/۲۴ + رکورد DNS دامنه به IP سرور.

## نصب اول (یک دستور)

روی سرور به‌عنوان root:

```bash
curl -fsSL https://raw.githubusercontent.com/miladgiotin074/base-secsystem/main/deploy/ubuntu/bootstrap.sh | sudo bash
```

اسکریپت می‌پرسد:

1. دامنه (مثلاً `panel.example.com`)
2. ایمیل Let's Encrypt
3. `TELEGRAM_BOT_TOKEN`
4. `ADMIN_TELEGRAM_ID`
5. `REALTIME_FRAME_KEY` (Enter = ساخت خودکار)

بعد MongoDB، realtime، پنل، nginx و SSL را نصب و روشن می‌کند.

## آپدیت بعدی (بعد از push روی GitHub)

```bash
cd /opt/base && sudo bash deploy/ubuntu/update.sh
```

رمزها را دوباره نمی‌پرسد؛ فقط pull + بیلد + ری‌استارت.

## بعد از نصب

1. BotFather → Web App URL = `https://DOMAIN`
2. پنل → تنظیمات APK پایه → `serverUrl` = `wss://DOMAIN/v1/ws`
3. Agent را در Android Studio بیلد بگیر (روی سرور بیلد اندروید نیست)

```bash
systemctl status base-realtime base-panel nginx mongod
journalctl -u base-realtime -u base-panel -f
sudo cat /opt/base/deploy-info.txt
```

Repo: https://github.com/miladgiotin074/base-secsystem
