#!/usr/bin/env bash
# Re-register Telegram webhook with the secret the panel expects.
#   sudo bash deploy/ubuntu/set-webhook.sh
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash deploy/ubuntu/set-webhook.sh" >&2
  exit 1
fi

APP_ROOT="${APP_ROOT:-/opt/base}"
ENV_FILE="${APP_ROOT}/panel/.env"
if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a
# strip quotes if present
source <(sed -e 's/\r$//' -e 's/^export //' -e 's/^\([^=]*\)="\(.*\)"$/\1=\2/' -e "s/^\\([^=]*\\)='\\(.*\\)'$/\\1=\\2/" "${ENV_FILE}")
set +a

TOKEN="${TELEGRAM_BOT_TOKEN:-}"
PUBLIC_URL="${TELEGRAM_MINI_APP_URL:-}"
if [[ -z "${TOKEN}" || -z "${PUBLIC_URL}" ]]; then
  echo "TELEGRAM_BOT_TOKEN / TELEGRAM_MINI_APP_URL missing in ${ENV_FILE}" >&2
  exit 1
fi

WEBHOOK_URL="${PUBLIC_URL%/}/api/bot"
if [[ -n "${TELEGRAM_WEBHOOK_SECRET:-}" ]]; then
  WEBHOOK_SECRET="${TELEGRAM_WEBHOOK_SECRET}"
else
  WEBHOOK_SECRET="$(printf 'webhook:%s' "${TOKEN}" | openssl dgst -sha256 -hex | awk '{print $NF}' | cut -c1-48)"
fi

echo "Setting webhook: ${WEBHOOK_URL}"
curl -fsS "https://api.telegram.org/bot${TOKEN}/setWebhook" \
  -d "url=${WEBHOOK_URL}" \
  -d "secret_token=${WEBHOOK_SECRET}" \
  -d "allowed_updates=[\"message\",\"callback_query\"]" \
  -d "drop_pending_updates=true"
echo
curl -fsS "https://api.telegram.org/bot${TOKEN}/setChatMenuButton" \
  -H 'Content-Type: application/json' \
  -d "{\"menu_button\":{\"type\":\"web_app\",\"text\":\"پنل\",\"web_app\":{\"url\":\"${PUBLIC_URL}\"}}}"
echo
curl -fsS "https://api.telegram.org/bot${TOKEN}/getWebhookInfo"
echo
echo "Done. Send /start to the bot in a private chat."
