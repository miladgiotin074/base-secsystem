#!/usr/bin/env bash
# Rebuild + restart without re-asking secrets (uses existing .env).
#   cd /opt/base && sudo bash deploy/ubuntu/update.sh
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash deploy/ubuntu/update.sh" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
APP_ROOT="${APP_ROOT:-/opt/base}"
BRANCH="${BRANCH:-main}"

echo "=== Base Ubuntu update ==="
echo "Repo: ${REPO_ROOT}"

if [[ -d "${REPO_ROOT}/.git" ]]; then
  echo "==> git pull"
  git -C "${REPO_ROOT}" fetch origin
  git -C "${REPO_ROOT}" checkout "${BRANCH}"
  git -C "${REPO_ROOT}" pull --ff-only origin "${BRANCH}"
fi

REPO_REAL="$(realpath "${REPO_ROOT}")"
APP_REAL="$(realpath "${APP_ROOT}")"
if [[ "${REPO_REAL}" != "${APP_REAL}" ]]; then
  echo "==> Sync to ${APP_ROOT}"
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
    --exclude 'server/.env' \
    --exclude 'panel/.env' \
    --exclude 'deploy-info.txt' \
    --exclude 'tools/apksigner/base' \
    "${REPO_ROOT}/" "${APP_ROOT}/"
fi

if [[ ! -f "${APP_ROOT}/server/.env" ]] || [[ ! -f "${APP_ROOT}/panel/.env" ]]; then
  echo "Missing .env — run first install: sudo bash deploy/ubuntu/setup.sh" >&2
  exit 1
fi

chown -R base:base "${APP_ROOT}"
# keep secrets root-readable by services via ownership
chown base:base "${APP_ROOT}/server/.env" "${APP_ROOT}/panel/.env" 2>/dev/null || true

echo "==> Build realtime"
sudo -u base bash -lc "cd '${APP_ROOT}/server' && /usr/local/go/bin/go build -o realtime ./cmd/realtime"

echo "==> Build panel"
sudo -u base bash -lc "cd '${APP_ROOT}/panel' && npm ci && npm run build"

systemctl daemon-reload
systemctl restart base-realtime
sleep 1
systemctl restart base-panel

echo
echo "=== Updated ==="
systemctl --no-pager --full status base-realtime base-panel | sed -n '1,40p' || true
echo
echo "Logs: journalctl -u base-realtime -u base-panel -f"
