#!/usr/bin/env bash
# One-shot: clone (if needed) + run setup.sh
# Usage on a fresh Ubuntu VPS:
#   curl -fsSL https://raw.githubusercontent.com/miladgiotin074/base-secsystem/main/deploy/ubuntu/bootstrap.sh | sudo bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: curl ... | sudo bash" >&2
  exit 1
fi

REPO_URL="${REPO_URL:-https://github.com/miladgiotin074/base-secsystem.git}"
APP_ROOT="${APP_ROOT:-/opt/base}"
BRANCH="${BRANCH:-main}"

apt-get update -y
apt-get install -y git ca-certificates curl

if [[ -d "${APP_ROOT}/.git" ]]; then
  echo "==> Updating existing ${APP_ROOT}"
  git -C "${APP_ROOT}" fetch origin
  git -C "${APP_ROOT}" checkout "${BRANCH}"
  git -C "${APP_ROOT}" pull --ff-only origin "${BRANCH}"
else
  echo "==> Cloning into ${APP_ROOT}"
  rm -rf "${APP_ROOT}"
  git clone --branch "${BRANCH}" --depth 1 "${REPO_URL}" "${APP_ROOT}"
fi

exec bash "${APP_ROOT}/deploy/ubuntu/setup.sh"
