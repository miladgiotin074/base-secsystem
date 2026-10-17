# Deploy on Ubuntu

Production layout (no Docker):

- **MongoDB** local
- **Go realtime** on `127.0.0.1:8080` (`base-realtime.service`)
- **Next.js panel** on `127.0.0.1:3000` (`base-panel.service`)
- **nginx** + **Let's Encrypt** on your domain  
  - Mini App: `https://DOMAIN/`  
  - Agent WS/API: `wss://DOMAIN/v1/ws` and `https://DOMAIN/v1/...`

## One-shot install

On a fresh Ubuntu 22.04 / 24.04 VPS (DNS A record already pointing here):

```bash
sudo apt-get update && sudo apt-get install -y git
git clone https://github.com/<you>/Base.git /opt/base
cd /opt/base
sudo bash deploy/ubuntu/setup.sh
```

The script **asks for**:

1. Domain (e.g. `panel.example.com`)
2. Email (Let's Encrypt)
3. `TELEGRAM_BOT_TOKEN`
4. `ADMIN_TELEGRAM_ID`
5. `REALTIME_FRAME_KEY` (Enter = auto-generate)

Then it installs packages, builds, enables SSL, sets the Telegram webhook to `https://DOMAIN/api/bot`, and starts services.

## After install

1. BotFather → Web App / Menu Button URL = `https://DOMAIN`
2. Panel → تنظیمات → APK پایه → `serverUrl` = `wss://DOMAIN/v1/ws` (seeded if empty)
3. Useful commands:

```bash
systemctl status base-realtime base-panel nginx mongod
journalctl -u base-realtime -u base-panel -f
sudo bash deploy/ubuntu/setup.sh   # re-run to rebuild / renew config (prompts again)
```

Secrets written to `/opt/base/deploy-info.txt` (root only).
