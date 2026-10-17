# Deploy on Ubuntu (آسان‌ترین روش)

نیاز: VPS اوبونتو ۲۲/۲۴ + رکورد DNS دامنه به IP سرور.

> ریپوی GitHub اگر **private** باشد، لینک `raw.githubusercontent.com` با `curl` خطای **404** می‌دهد.  
> از `git clone` استفاده کن (نه curl|bash).

## نصب اول

روی سرور به‌عنوان root (HTTPS؛ GitHub یوزرنیم + Personal Access Token می‌خواهد):

```bash
sudo apt-get update && sudo apt-get install -y git
sudo git clone https://github.com/miladgiotin074/base-secsystem.git /opt/base
cd /opt/base
sudo bash deploy/ubuntu/setup.sh
```

یا با SSH (اگر کلید روی سرور و GitHub ست شده):

```bash
sudo apt-get update && sudo apt-get install -y git
sudo git clone git@github.com:miladgiotin074/base-secsystem.git /opt/base
cd /opt/base
sudo bash deploy/ubuntu/setup.sh
```

اسکریپت می‌پرسد: دامنه، ایمیل SSL، `TELEGRAM_BOT_TOKEN`، `ADMIN_TELEGRAM_ID`، و `REALTIME_FRAME_KEY` (Enter = ساخت خودکار).

## آپدیت بعدی

```bash
cd /opt/base && sudo bash deploy/ubuntu/update.sh
```

## بعد از نصب

1. BotFather → Web App URL = `https://DOMAIN`
2. پنل → تنظیمات APK پایه → `serverUrl` = `wss://DOMAIN/v1/ws`
3. Agent را در Android Studio بیلد بگیر

```bash
systemctl status base-realtime base-panel nginx mongod
journalctl -u base-realtime -u base-panel -f
sudo cat /opt/base/deploy-info.txt
```

Repo: https://github.com/miladgiotin074/base-secsystem
