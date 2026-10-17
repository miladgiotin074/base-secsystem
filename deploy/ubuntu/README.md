# Deploy on Ubuntu

نیاز: VPS اوبونتو ۲۲/۲۴ + DNS دامنه به IP سرور.

> اگر ریپو private باشد، `curl` به raw گیت‌هاب ممکن است 404 بدهد. ترجیح: `git clone` سپس `setup.sh`.

## اگر `/opt/base` از قبل هست (همین الان شما)

```bash
cd /opt/base
sudo git pull origin main
sudo bash deploy/ubuntu/setup.sh
```

سوال‌های دامنه / ایمیل / توکن بات / ادمین را جواب بده و آخرش `y` بزن.  
تا وقتی این مرحله کامل نشود، `base-realtime` / `base-panel` ساخته نمی‌شوند.

## نصب از صفر

```bash
sudo apt-get update && sudo apt-get install -y git
sudo git clone https://github.com/miladgiotin074/base-secsystem.git /opt/base
cd /opt/base
sudo bash deploy/ubuntu/setup.sh
```

## آپدیت بعدی

```bash
cd /opt/base && sudo bash deploy/ubuntu/update.sh
```

## بعد از نصب

1. BotFather → Web App = `https://DOMAIN`
2. پنل → APK پایه → `serverUrl` = `wss://DOMAIN/v1/ws`
3. Agent را در Android Studio بیلد بگیر

```bash
systemctl status base-realtime base-panel nginx mongod
journalctl -u base-realtime -u base-panel -f
sudo cat /opt/base/deploy-info.txt
```
